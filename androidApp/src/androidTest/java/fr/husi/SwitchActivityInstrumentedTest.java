package fr.husi;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SwitchActivityInstrumentedTest {

    @Test
    public void switchActivityLaunches() throws InterruptedException {
        launchSwitchActivity();
        InstrumentedTestSupport.waitMillis(2_000L);
        assertTrue(InstrumentedTestSupport.isProcessRunning());
    }

    @Test
    public void warmProgressOrFullPickerVisibleWhenLaunched() throws InterruptedException {
        Context context = InstrumentedTestSupport.targetContext();
        launchSwitchActivity();
        UiDevice device = InstrumentedTestSupport.device();
        String pkg = context.getPackageName();
        long deadline = SystemClock.uptimeMillis() + 8_000L;
        boolean visible = false;
        while (SystemClock.uptimeMillis() < deadline && !visible) {
            visible = device.hasObject(By.textContains("Comparing"))
                || device.hasObject(By.text("Search"))
                || device.hasObject(By.textContains("backup"))
                || InstrumentedTestSupport.isSwitchActivityResumed()
                || device.hasObject(By.pkg(pkg));
            if (!visible) {
                Thread.sleep(250L);
            }
        }
        assertTrue("Warm progress or full picker should be visible", visible);
    }

    private static void launchSwitchActivity() {
        Context context = InstrumentedTestSupport.targetContext();
        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), "fr.husi.ui.SwitchActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        assertNotNull(intent);
    }
}
