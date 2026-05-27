package fr.husi.simplemode

import fr.husi.test.isNetworkScenarioInjectionEnabled

actual suspend fun probeSimpleModeNetwork(): SimpleModeNetworkState {
    if (isNetworkScenarioInjectionEnabled()) {
        scenarioNetworkOverride()?.let { return@probeSimpleModeNetwork it }
    }
    return SimpleModeNetworkState(
        hasAnyInternet = true,
        googleOk = true,
        whitelistOnly = false,
    )
}
