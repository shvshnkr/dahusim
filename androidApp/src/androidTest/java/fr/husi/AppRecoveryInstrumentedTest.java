package fr.husi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AppRecoveryInstrumentedTest {
    @Test
    public void recoversAfterForceStop() throws IOException, InterruptedException {
        InstrumentedTestSupport.launchMainActivity();
        InstrumentedTestSupport.waitForProcessRunning();
        InstrumentedTestSupport.waitForMainActivityResumed();

        InstrumentedTestSupport.killTargetApp();
        InstrumentedTestSupport.waitMillis(2_000L);
        assertFalse(InstrumentedTestSupport.isProcessRunning());

        InstrumentedTestSupport.launchMainActivity();
        InstrumentedTestSupport.waitForProcessRunning();
        InstrumentedTestSupport.waitForMainActivityResumed();

        assertTrue(InstrumentedTestSupport.isProcessRunning());
        assertTrue(InstrumentedTestSupport.isMainActivityResumed());
    }

    @Test
    public void recoversAfterForceStopDuringBackground() throws IOException, InterruptedException {
        InstrumentedTestSupport.launchMainActivity();
        InstrumentedTestSupport.waitForProcessRunning();
        InstrumentedTestSupport.waitForMainActivityResumed();
        InstrumentedTestSupport.waitMillis(3_000L);

        InstrumentedTestSupport.pressHome();
        InstrumentedTestSupport.waitMillis(2_000L);
        InstrumentedTestSupport.killTargetApp();
        InstrumentedTestSupport.waitMillis(2_000L);

        InstrumentedTestSupport.launchMainActivity();
        InstrumentedTestSupport.waitForProcessRunning();
        InstrumentedTestSupport.waitForMainActivityResumed();

        assertTrue(InstrumentedTestSupport.isProcessRunning());
        assertTrue(InstrumentedTestSupport.isMainActivityResumed());
    }
}
