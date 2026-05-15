package fr.husi.simplemode

import fr.husi.bg.NetworkReachabilityProbe

actual suspend fun probeSimpleModeNetwork(): SimpleModeNetworkState {
    val r = NetworkReachabilityProbe.probe(fast = true)
    return SimpleModeNetworkState(
        hasAnyInternet = r.hasInternet,
        googleOk = r.googleReachable,
        whitelistOnly = r.whitelistOnly,
    )
}
