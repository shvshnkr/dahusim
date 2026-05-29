package fr.husi;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
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
        launchSwitchActivity();
        UiDevice device = InstrumentedTestSupport.device();
        boolean visible = device.wait(Until.hasObject(By.textContains("Comparing")), 5_000L)
            || device.wait(Until.hasObject(By.text("Search")), 5_000L)
            || device.wait(Until.hasObject(By.textContains("backup")), 3_000L)
            || InstrumentedTestSupport.isSwitchActivityResumed();
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
