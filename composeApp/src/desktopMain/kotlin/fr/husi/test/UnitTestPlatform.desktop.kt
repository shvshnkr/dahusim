package fr.husi.test

import fr.husi.bg.DesktopBackgroundCoordinator

internal val isHusiUnitTest: Boolean
    get() = System.getProperty("husi.unitTest") == "true"

actual object UnitTestPlatform {
    actual fun stopBackgroundLoops() {
        if (!isHusiUnitTest) return
        runCatching { DesktopBackgroundCoordinator.stop() }
    }
}

actual fun isNetworkScenarioInjectionEnabled(): Boolean =
    System.getProperty("husi.scenarioTest") == "true" ||
        System.getProperty("husi.unitTest") == "true"
