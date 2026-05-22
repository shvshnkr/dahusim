package fr.husi.database

/**
 * Shared probe-pool rules for subscriptions and built-in helpers.
 *
 * [ProbeState.CEMETERY] (jail) profiles stay out of connect-time selection until a probe
 * (usually background TCP) promotes them to CANDIDATE/ALIVE — automatic unjail.
 *
 * [ProbeState.DEAD] stays in the ranked fallback queue (for background revival) but is not
 * walked during session fallback — same idea as jail, without removing the id from the list.
 */
internal object ProbePoolEligibility {

    /** Prepare / connect pool: jail stays closed until revived in [ProxyProbeStateStore]. */
    fun isSelectableForConnect(state: ProxyProbeState?): Boolean =
        state == null || state.state != ProbeState.CEMETERY

    /**
     * Session fallback walk: skip jail, persisted dead, and recent connect-health cooldown.
     * UNKNOWN/SUSPECT/CANDIDATE/ALIVE remain reachable.
     */
    fun isViableFallbackTarget(
        state: ProxyProbeState?,
        inRecentFailureCooldown: Boolean,
    ): Boolean {
        if (inRecentFailureCooldown) return false
        if (!isSelectableForConnect(state)) return false
        return state == null || state.state != ProbeState.DEAD
    }

    fun filterSelectable(
        proxies: List<ProxyEntity>,
        probeStates: Map<Long, ProxyProbeState>,
    ): List<ProxyEntity> = proxies.filter { isSelectableForConnect(probeStates[it.id]) }

    fun countJailed(probeStates: Map<Long, ProxyProbeState>): Int =
        probeStates.values.count { it.state == ProbeState.CEMETERY }

    fun countDead(probeStates: Map<Long, ProxyProbeState>): Int =
        probeStates.values.count { it.state == ProbeState.DEAD }

    /**
     * Keeps prepare ranking order but moves persisted [ProbeState.DEAD] ids to the tail so
     * [DataStore.autoSelectFallbackIndex] advances through viable servers first.
     */
    fun orderFallbackQueue(
        rankedIds: List<Long>,
        probeStates: Map<Long, ProxyProbeState>,
    ): List<Long> {
        if (rankedIds.isEmpty()) return rankedIds
        val head = ArrayList<Long>(rankedIds.size)
        val deadTail = ArrayList<Long>()
        for (id in rankedIds) {
            if (probeStates[id]?.state == ProbeState.DEAD) {
                deadTail += id
            } else {
                head += id
            }
        }
        return head + deadTail
    }

    fun firstViableInQueue(
        rankedIds: List<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        inRecentFailureCooldown: (Long) -> Boolean = { false },
    ): Long? = rankedIds.firstOrNull { id ->
        isViableFallbackTarget(probeStates[id], inRecentFailureCooldown(id))
    }
}
