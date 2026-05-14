package fr.husi.simplemode

actual suspend fun probeSimpleModeNetwork(): SimpleModeNetworkState {
    return SimpleModeNetworkState(
        hasAnyInternet = true,
        googleOk = true,
        whitelistOnly = false,
    )
}
