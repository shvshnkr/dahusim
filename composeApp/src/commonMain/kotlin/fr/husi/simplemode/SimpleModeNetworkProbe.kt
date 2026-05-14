package fr.husi.simplemode

data class SimpleModeNetworkState(
    val hasAnyInternet: Boolean,
    val googleOk: Boolean,
    val whitelistOnly: Boolean,
)

expect suspend fun probeSimpleModeNetwork(): SimpleModeNetworkState
