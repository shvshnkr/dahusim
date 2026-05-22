package fr.husi.database

/**
 * Shared probe-pool rules for subscriptions and built-in helpers.
 *
 * [ProbeState.CEMETERY] (jail) profiles stay out of connect-time selection until a probe
 * (usually background TCP) promotes them to CANDIDATE/ALIVE — automatic unjail.
 */
internal object ProbePoolEligibility {

    /** Connect / fallback queue: jail stays closed until revived in [ProxyProbeStateStore]. */
    fun isSelectableForConnect(state: ProxyProbeState?): Boolean =
        state == null || state.state != ProbeState.CEMETERY

    fun filterSelectable(
        proxies: List<ProxyEntity>,
        probeStates: Map<Long, ProxyProbeState>,
    ): List<ProxyEntity> = proxies.filter { isSelectableForConnect(probeStates[it.id]) }

    fun countJailed(probeStates: Map<Long, ProxyProbeState>): Int =
        probeStates.values.count { it.state == ProbeState.CEMETERY }
}
