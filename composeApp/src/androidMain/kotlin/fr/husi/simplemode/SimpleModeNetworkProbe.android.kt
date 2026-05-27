package fr.husi.simplemode

import fr.husi.bg.NetworkReachabilityProbe
import fr.husi.test.isNetworkScenarioInjectionEnabled

actual suspend fun probeSimpleModeNetwork(): SimpleModeNetworkState {
    if (isNetworkScenarioInjectionEnabled()) {
        scenarioNetworkOverride()?.let { return@probeSimpleModeNetwork it }
    }
    val r = NetworkReachabilityProbe.probe(fast = true)
    return SimpleModeNetworkState(
        hasAnyInternet = r.hasInternet,
        googleOk = r.googleReachable,
        whitelistOnly = r.whitelistOnly,
    )
}
