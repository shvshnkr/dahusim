package fr.husi;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InstrumentedSmokeTest {
    @Test
    public void appContextIsAvailable() {
        Context appContext = ApplicationProvider.getApplicationContext();
        assertNotNull(appContext);
    }

    @Test
    public void launcherIntentResolves() {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String targetPackage = targetContext.getPackageName();
        Intent launchIntent = targetContext.getPackageManager().getLaunchIntentForPackage(targetPackage);
        assertNotNull("Launcher intent should resolve for target package", launchIntent);
    }

    @Test
    public void mainActivityLaunchesAndResumes() {
        InstrumentedTestSupport.launchMainActivity();
        InstrumentedTestSupport.waitForProcessRunning();
        InstrumentedTestSupport.waitForMainActivityResumed();
        assertTrue(InstrumentedTestSupport.isProcessRunning());
        assertTrue(InstrumentedTestSupport.isMainActivityResumed());
    }
}
