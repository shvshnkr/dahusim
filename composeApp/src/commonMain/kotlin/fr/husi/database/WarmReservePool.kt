package fr.husi.database

/**
 * Selects and tracks URL-verified fallback reserve ids from [DataStore.autoSelectFallbackQueue]
 * while simple-mode VPN stays connected.
 */
internal object WarmReservePool {

    fun isFeatureEnabled(): Boolean =
        DataStore.simpleMode && DataStore.probe2kPersistenceEnabled

    fun targetCount(): Int = DataStore.probe2kWarmReserveCount.coerceIn(1, 4)

    fun countFreshUrlAlive(
        ids: Collection<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        nowMs: Long = System.currentTimeMillis(),
    ): Int = ids.count { ProxyProbeStateStore.isFreshUrlVerified(probeStates[it], nowMs) }

    fun selectReserveIds(
        queue: List<Long>,
        connectedId: Long,
        probeStates: Map<Long, ProxyProbeState>,
        target: Int = targetCount(),
    ): List<Long> {
        if (queue.isEmpty()) return emptyList()
        val reserveTarget = target.coerceIn(1, 4)
        val startIdx = queue.indexOf(connectedId).let { if (it >= 0) it + 1 else 0 }
        val candidates = queue.drop(startIdx).filter { id ->
            id != connectedId && ProbePoolEligibility.isSelectableForConnect(probeStates[id])
        }
        return candidates
            .sortedWith(
                compareBy<Long> { id ->
                    if (ProxyProbeStateStore.isFreshUrlVerified(probeStates[id])) 0 else 1
                }
                    .thenBy { ProxyProbeStateStore.probeStateRank(probeStates[it]) }
                    .thenBy { queue.indexOf(it) },
            )
            .take(reserveTarget)
    }

    fun deficit(
        reserveIds: List<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        target: Int = targetCount(),
    ): Int {
        val fresh = countFreshUrlAlive(reserveIds, probeStates)
        return (target.coerceIn(1, 4) - fresh).coerceAtLeast(0)
    }

    fun replenishCandidates(
        queue: List<Long>,
        connectedId: Long,
        reserveIds: List<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        limit: Int,
    ): List<Long> {
        if (limit <= 0 || queue.size <= 1) return emptyList()
        val reserveSet = reserveIds.toSet()
        val startIdx = queue.indexOf(connectedId).let { if (it >= 0) it + 1 else 0 }
        return queue.drop(startIdx)
            .filter { it !in reserveSet && it != connectedId }
            .filter { ProbePoolEligibility.isSelectableForConnect(probeStates[it]) }
            .take(limit)
    }

    fun updateStatusSnapshot(reserveIds: List<Long>, probeStates: Map<Long, ProxyProbeState>) {
        val target = targetCount()
        val fresh = countFreshUrlAlive(reserveIds, probeStates)
        DataStore.probe2kWarmReserveStatus = "$fresh/$target fresh"
    }
}
