package fr.husi.test

expect object UnitTestPlatform {
    fun stopBackgroundLoops()
}

/** True when desktop tests run with `husi.unitTest` or `husi.scenarioTest`. */
expect fun isNetworkScenarioInjectionEnabled(): Boolean
