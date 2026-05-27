package fr.husi.scenario.network

import fr.husi.simplemode.SimpleModeNetworkProbeHooks
import fr.husi.simplemode.SimpleModeNetworkState

object NetworkScenarioHarness {
    fun install(state: SimpleModeNetworkState?) {
        SimpleModeNetworkProbeHooks.scenarioOverride = state
    }

    fun clear() {
        SimpleModeNetworkProbeHooks.scenarioOverride = null
    }
}

data class NetworkScenarioRow(
    val id: String,
    val state: SimpleModeNetworkState,
)
