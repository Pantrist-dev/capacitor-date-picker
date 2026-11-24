package com.getcapacitor.community.datepicker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import com.getcapacitor.Bridge;
import com.getcapacitor.JSObject;
import com.getcapacitor.MessageHandler;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginResult;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/**
 * Opens the picker from Capacitor's plugin thread on a device, as the app does.
 *
 * Crashlytics reported CalledFromWrongThreadException ("Expected: CapacitorPlugins Calling: main")
 * out of TextView.onConfigurationChanged, ten ViewGroups deep: the date picker dialog's own
 * hierarchy, whose window was created on the plugin thread. Stock Android delivers a dialog's
 * configuration change on the window's own thread, so it does not crash on an emulator; the
 * device that crashed delivered it on the main thread. The test therefore checks the cause (which
 * thread owns the dialog's window) and that the picker keeps working through real rotation and
 * dark mode changes.
 */
@RunWith(AndroidJUnit4.class)
public class DatePickerConfigChangeTest {

    private static final String DONE = "PickerDone";
    private static final String CANCEL = "PickerCancel";
    private static final long WAIT_MS = 5000;
    // Material dialog buttons show their text in capitals.
    private static final BySelector DONE_BUTTON = By.text(Pattern.compile(DONE, Pattern.CASE_INSENSITIVE));
    private static final BySelector CANCEL_BUTTON = By.text(Pattern.compile(CANCEL, Pattern.CASE_INSENSITIVE));

    private UiDevice device;
    private ActivityScenario<ConfigChangeTestActivity> scenario;
    private HandlerThread pluginThread;
    private MessageHandler msgHandler;
    private DatePickerPlugin plugin;

    @Before
    public void setUp() throws Exception {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.setOrientationNatural();
        shell("cmd uimode night no");

        scenario = ActivityScenario.launch(ConfigChangeTestActivity.class);
        AtomicReference<AppCompatActivity> activity = new AtomicReference<>();
        scenario.onActivity(activity::set);

        Bridge bridge = mock(Bridge.class);
        when(bridge.getActivity()).thenReturn(activity.get());
        when(bridge.getContext()).thenReturn(activity.get());
        // What Bridge.executeOnMainThread does.
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
        pluginThread = new HandlerThread("CapacitorPlugins");
        pluginThread.start();
    }

    @After
    public void tearDown() throws Exception {
        if (pluginThread != null) {
            pluginThread.quitSafely();
        }
        if (scenario != null) {
            scenario.close();
        }
        device.setOrientationNatural();
        device.unfreezeRotation();
        shell("cmd uimode night no");
    }

    private void shell(String command) throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command).close();
    }

    private void presentFromPluginThread(String mode) {
        JSObject data = new JSObject();
        data.put("mode", mode);
        data.put("format", "yyyy-MM-dd");
        data.put("date", "2026-11-24");
        data.put("doneText", DONE);
        data.put("cancelText", CANCEL);
        PluginCall call = new PluginCall(msgHandler, "DatePicker", "cb-1", "present", data);
        new Handler(pluginThread.getLooper()).post(() -> {
            try {
                plugin.present(call);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertNotNull("picker did not open", device.wait(Until.findObject(DONE_BUTTON), WAIT_MS));
    }

    private JSONObject resolvedData() throws Exception {
        ArgumentCaptor<PluginResult> success = ArgumentCaptor.forClass(PluginResult.class);
        verify(msgHandler, timeout(WAIT_MS)).sendResponseMessage(any(PluginCall.class), success.capture(), isNull());
        return new JSONObject(success.getValue().toString());
    }

    @Test
    public void dialogWindowBelongsToTheMainThread() throws Exception {
        presentFromPluginThread("date");

        AtomicReference<String> owners = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            StringBuilder threads = new StringBuilder();
            for (View root : rootViews()) {
                threads.append(root.getHandler().getLooper().getThread().getName()).append(' ');
            }
            owners.set(threads.toString().trim());
        });
        // Activity window + picker dialog. Before the fix: "main CapacitorPlugins".
        assertEquals("main main", owners.get());
    }

    @SuppressWarnings({ "unchecked", "PrivateApi" })
    private static List<View> rootViews() {
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object instance = global.getMethod("getInstance").invoke(null);
            Field views = global.getDeclaredField("mViews");
            views.setAccessible(true);
            return new ArrayList<>((List<View>) views.get(instance));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void datePickerSurvivesRotationAndDarkMode() throws Exception {
        presentFromPluginThread("date");

        device.setOrientationLandscape();
        device.waitForIdle();
        shell("cmd uimode night yes");
        device.waitForIdle();
        device.setOrientationNatural();
        device.waitForIdle();

        assertNotNull("picker gone after the configuration changes", device.wait(Until.findObject(DONE_BUTTON), WAIT_MS));
        device.findObject(DONE_BUTTON).click();
        assertEquals("2026-11-24", resolvedData().getString("value"));
    }

    @Test
    public void timePickerSurvivesRotation() throws Exception {
        presentFromPluginThread("time");

        device.setOrientationLandscape();
        device.waitForIdle();
        device.setOrientationNatural();
        device.waitForIdle();

        assertNotNull(device.wait(Until.findObject(CANCEL_BUTTON), WAIT_MS));
        device.findObject(CANCEL_BUTTON).click();
        assertFalse(resolvedData().has("value"));
    }

    @Test
    public void backButtonAnswersTheCall() throws Exception {
        presentFromPluginThread("date");

        device.pressBack();

        assertTrue(device.wait(Until.gone(DONE_BUTTON), WAIT_MS));
        assertFalse(resolvedData().has("value"));
    }
}
