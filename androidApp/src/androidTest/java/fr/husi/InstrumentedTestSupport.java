package fr.husi;

import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class InstrumentedTestSupport {
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;
    private static final long POLL_MS = 250L;

    private InstrumentedTestSupport() {
    }

    public static Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    public static String targetPackage() {
        return targetContext().getPackageName();
    }

    public static ComponentName mainActivityComponent() {
        return new ComponentName(targetPackage(), "fr.husi.ui.MainActivity");
    }

    public static UiDevice device() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    public static void launchMainActivity() {
        Context context = targetContext();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(mainActivityComponent());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    public static void waitForProcessRunning() {
        waitUntil(InstrumentedTestSupport::isProcessRunning, "process did not start");
    }

    public static void waitForProcessStopped() {
        waitUntil(() -> !isProcessRunning(), "process did not stop");
    }

    public static void waitForMainActivityResumed() {
        waitUntil(InstrumentedTestSupport::isMainActivityResumed, "MainActivity did not resume");
    }

    public static void waitMillis(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    public static void pressHome() {
        device().pressHome();
    }

    public static void lockScreen() {
        try {
            device().sleep();
        } catch (RemoteException exception) {
            throw new AssertionError("lockScreen failed", exception);
        }
    }

    public static void unlockScreen() {
        try {
            device().wakeUp();
            device().pressKeyCode(android.view.KeyEvent.KEYCODE_MENU);
        } catch (RemoteException exception) {
            throw new AssertionError("unlockScreen failed", exception);
        }
    }

    public static void forceStopTargetApp() throws IOException {
        executeShellCommand("am force-stop " + targetPackage());
    }

    public static void killTargetApp() throws IOException {
        device().waitForIdle(5_000);
        executeShellCommand("am kill " + targetPackage());
    }

    public static void launchExternalAppForSwitch() {
        Context context = targetContext();
        Intent settingsIntent = new Intent(android.provider.Settings.ACTION_SETTINGS);
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(settingsIntent);
    }

    public static boolean isProcessRunning() {
        ActivityManager activityManager =
            (ActivityManager) targetContext().getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return false;
        }
        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }
        String targetPackage = targetPackage();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (targetPackage.equals(process.processName)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMainActivityResumed() {
        try {
            String output = executeShellCommand("dumpsys activity activities");
            return output.contains("fr.husi.ui.MainActivity")
                && (output.contains("mResumedActivity") || output.contains("ResumedActivity"));
        } catch (IOException exception) {
            return false;
        }
    }

    public static String executeShellCommand(String command) throws IOException {
        try (
            InputStream inputStream = new java.io.FileInputStream(
                InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .executeShellCommand(command)
                    .getFileDescriptor()
            )
        ) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void waitUntil(Condition condition, String failureMessage) {
        long deadline = SystemClock.uptimeMillis() + DEFAULT_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition.check()) {
                return;
            }
            SystemClock.sleep(POLL_MS);
        }
        fail(failureMessage + " (package=" + targetPackage() + ")");
    }

    private interface Condition {
        boolean check();
    }
}
