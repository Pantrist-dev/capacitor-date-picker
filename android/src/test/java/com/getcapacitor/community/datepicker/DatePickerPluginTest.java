package com.getcapacitor.community.datepicker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;
import com.getcapacitor.MessageHandler;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginResult;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class DatePickerPluginTest {

    public static class TestActivity extends AppCompatActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light);
            super.onCreate(savedInstanceState);
        }
    }

    private HandlerThread pluginThread;
    private MessageHandler msgHandler;
    private DatePickerPlugin plugin;

    @Before
    public void setUp() {
        AppCompatActivity activity = Robolectric.buildActivity(TestActivity.class).setup().get();

        // Same as Bridge.executeOnMainThread: post to the main looper.
        Bridge bridge = mock(Bridge.class);
        when(bridge.getActivity()).thenReturn(activity);
        when(bridge.getContext()).thenReturn(activity);
        doAnswer(inv -> {
            new Handler(Looper.getMainLooper()).post(inv.getArgument(0));
            return null;
        }).when(bridge).executeOnMainThread(any(Runnable.class));

        plugin = new DatePickerPlugin();
        plugin.setBridge(bridge);
        DatePickerOptions defaults = new DatePickerOptions();
        defaults.theme = "light";
        defaults.mode = "date";
        defaults.format = "yyyy-MM-dd";
        defaults.timezone = "UTC";
        defaults.is24h = true;
        plugin.options = defaults;

        msgHandler = mock(MessageHandler.class);
        // Capacitor runs plugin methods on a HandlerThread named like this one.
        pluginThread = new HandlerThread("CapacitorPlugins");
        pluginThread.start();
    }

    @After
    public void tearDown() {
        pluginThread.quitSafely();
    }

    /** Calls present() on the plugin thread, as Capacitor does, and lets the main looper run. */
    private AlertDialog present(JSObject data) throws Exception {
        PluginCall call = new PluginCall(msgHandler, "DatePicker", "cb-1", "present", data);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        new Handler(pluginThread.getLooper()).post(() -> {
            try {
                plugin.present(call);
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        // Let the plugin thread finish anything it queued for itself, then the main thread.
        CountDownLatch drained = new CountDownLatch(1);
        new Handler(pluginThread.getLooper()).post(drained::countDown);
        assertTrue(drained.await(5, TimeUnit.SECONDS));
        shadowOf(Looper.getMainLooper()).idle();

        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("no dialog was shown", dialog);
        assertTrue(dialog.isShowing());
        return (AlertDialog) dialog;
    }

    private static JSObject dateMode() {
        JSObject data = new JSObject();
        data.put("mode", "date");
        data.put("format", "yyyy-MM-dd");
        data.put("date", "2026-11-24");
        return data;
    }

    private List<PluginResult> successes(int times) {
        ArgumentCaptor<PluginResult> success = ArgumentCaptor.forClass(PluginResult.class);
        verify(msgHandler, org.mockito.Mockito.times(times)).sendResponseMessage(any(PluginCall.class), success.capture(), isNull());
        return success.getAllValues();
    }

    /** The data the call resolved with, as JS receives it. */
    private static String valueOf(PluginResult result) throws Exception {
        return new org.json.JSONObject(result.toString()).getString("value");
    }

    @Test
    public void dialogSurvivesAConfigurationChangeOnTheMainThread() throws Exception {
        AlertDialog dialog = present(dateMode());

        // The crash: ActivityThread dispatches a configuration change on the main thread, and
        // TextView.onConfigurationChanged -> requestLayout -> ViewRootImpl.checkThread threw
        // because the dialog's window (its ViewRootImpl) was created on the plugin thread.
        View decor = dialog.getWindow().getDecorView();
        Object viewRoot = decor.getParent();
        assertEquals("android.view.ViewRootImpl", viewRoot.getClass().getName());

        java.lang.reflect.Field owner = viewRoot.getClass().getDeclaredField("mThread");
        owner.setAccessible(true);
        assertSame(Looper.getMainLooper().getThread(), owner.get(viewRoot));

        java.lang.reflect.Method checkThread = viewRoot.getClass().getDeclaredMethod("checkThread");
        checkThread.setAccessible(true);
        checkThread.invoke(viewRoot); // throws CalledFromWrongThreadException for the old code

        decor.dispatchConfigurationChanged(dialog.getContext().getResources().getConfiguration());
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(dialog.isShowing());
    }

    @Test
    public void doneResolvesWithTheChosenDate() throws Exception {
        AlertDialog dialog = present(dateMode());

        dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        List<PluginResult> results = successes(1);
        assertEquals("2026-11-24", valueOf(results.get(0)));
    }

    @Test
    public void cancelButtonResolvesOnceWithoutValue() throws Exception {
        AlertDialog dialog = present(dateMode());

        dialog.getButton(Dialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(dialog.isShowing());
        assertFalse(new org.json.JSONObject(successes(1).get(0).toString()).has("value"));
    }

    @Test
    public void backButtonResolvesWithoutValue() throws Exception {
        AlertDialog dialog = present(dateMode());

        dialog.onBackPressed();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(dialog.isShowing());
        assertFalse(new org.json.JSONObject(successes(1).get(0).toString()).has("value"));
    }

    @Test
    public void timeModeCancelResolvesOnceWithoutValue() throws Exception {
        JSObject data = new JSObject();
        data.put("mode", "time");
        AlertDialog dialog = present(data);

        dialog.onBackPressed();
        shadowOf(Looper.getMainLooper()).idle();

        assertFalse(new org.json.JSONObject(successes(1).get(0).toString()).has("value"));
    }
}
