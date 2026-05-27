package fr.husi;

import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DeviceLifecycleInstrumentedTest {
    @Before
    public void setUp() {
        InstrumentedTestSupport.launchMainActivity();
        InstrumentedTestSupport.waitForProcessRunning();
        InstrumentedTestSupport.waitForMainActivityResumed();
    }

    @Test
    public void survivesBackgroundForegroundCycle() throws InterruptedException {
        InstrumentedTestSupport.pressHome();
        InstrumentedTestSupport.waitMillis(1_500L);

        InstrumentedTestSupport.launchMainActivity();
        InstrumentedTestSupport.waitForProcessRunning();
        InstrumentedTestSupport.waitForMainActivityResumed();

        assertTrue(InstrumentedTestSupport.isProcessRunning());
        assertTrue(InstrumentedTestSupport.isMainActivityResumed());
    }

    @Test
    public void survivesLockUnlockCycle() throws InterruptedException {
        InstrumentedTestSupport.lockScreen();
        InstrumentedTestSupport.waitMillis(1_500L);
        InstrumentedTestSupport.unlockScreen();
        InstrumentedTestSupport.waitMillis(1_000L);

        InstrumentedTestSupport.launchMainActivity();
        InstrumentedTestSupport.waitForMainActivityResumed();

        assertTrue(InstrumentedTestSupport.isProcessRunning());
        assertTrue(InstrumentedTestSupport.isMainActivityResumed());
    }

    @Test
    public void survivesExternalAppSwitchAndReturn() throws InterruptedException {
        InstrumentedTestSupport.launchExternalAppForSwitch();
        InstrumentedTestSupport.waitMillis(1_500L);

        InstrumentedTestSupport.launchMainActivity();
        InstrumentedTestSupport.waitForProcessRunning();
        InstrumentedTestSupport.waitForMainActivityResumed();

        assertTrue(InstrumentedTestSupport.isProcessRunning());
        assertTrue(InstrumentedTestSupport.isMainActivityResumed());
    }
}
