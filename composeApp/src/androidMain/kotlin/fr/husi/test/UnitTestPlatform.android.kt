package fr.husi.test

actual object UnitTestPlatform {
    actual fun stopBackgroundLoops() = Unit
}

actual fun isNetworkScenarioInjectionEnabled(): Boolean = false
