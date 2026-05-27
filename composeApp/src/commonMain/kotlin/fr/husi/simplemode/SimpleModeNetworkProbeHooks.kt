package fr.husi.simplemode

/**
 * Test-only injection for [probeSimpleModeNetwork]. Honored on desktop when
 * `husi.scenarioTest` or `husi.unitTest` is set; cleared between scenario rows.
 */
object SimpleModeNetworkProbeHooks {
    @Volatile
    var scenarioOverride: SimpleModeNetworkState? = null
}

internal fun scenarioNetworkOverride(): SimpleModeNetworkState? =
    SimpleModeNetworkProbeHooks.scenarioOverride
