package fr.husi.database

import fr.husi.simplemode.WarmReserveSessionCache

/**
 * Selects and tracks session-live fallback reserve ids from [DataStore.autoSelectFallbackQueue]
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

    fun countSessionLive(
        ids: Collection<Long>,
        cache: WarmReserveSessionCache = WarmReserveSessionCache,
    ): Int = ids.count { cache.isSessionLive(it) }

    /** Reserve ids confirmed live in the current VPN session (queue order, up to [target]). */
    fun liveReserveIds(
        queue: List<Long>,
        connectedId: Long,
        cache: WarmReserveSessionCache = WarmReserveSessionCache,
        probeStates: Map<Long, ProxyProbeState>,
        target: Int = targetCount(),
    ): List<Long> {
        if (queue.isEmpty()) return emptyList()
        val reserveTarget = target.coerceIn(1, 4)
        val startIdx = queue.indexOf(connectedId).let { if (it >= 0) it + 1 else 0 }
        return queue.drop(startIdx)
            .filter { id ->
                id != connectedId &&
                    cache.isSessionLive(id) &&
                    ProbePoolEligibility.isSelectableForConnect(probeStates[id])
            }
            .sortedWith(queueRankComparator(queue, probeStates))
            .take(reserveTarget)
    }

    /** Candidates for warm URL verify (session-live first, then queue scan skipping warm-failed). */
    fun selectReserveIds(
        queue: List<Long>,
        connectedId: Long,
        probeStates: Map<Long, ProxyProbeState>,
        target: Int = targetCount(),
        cache: WarmReserveSessionCache = WarmReserveSessionCache,
    ): List<Long> {
        if (queue.isEmpty()) return emptyList()
        val reserveTarget = target.coerceIn(1, 4)
        val startIdx = queue.indexOf(connectedId).let { if (it >= 0) it + 1 else 0 }
        val tail = queue.drop(startIdx)

        val liveFirst = tail
            .filter { id ->
                id != connectedId &&
                    cache.isSessionLive(id) &&
                    ProbePoolEligibility.isSelectableForConnect(probeStates[id])
            }
            .sortedWith(queueRankComparator(queue, probeStates))
            .take(reserveTarget)
        if (liveFirst.size >= reserveTarget) return liveFirst

        val have = liveFirst.toMutableSet()
        val fill = tail
            .filter { id ->
                id != connectedId &&
                    id !in have &&
                    !cache.isWarmFailed(id) &&
                    ProbePoolEligibility.isSelectableForConnect(probeStates[id])
            }
            .sortedWith(queueRankComparator(queue, probeStates))
            .take(reserveTarget - liveFirst.size)
        return liveFirst + fill
    }

    fun deficit(
        reserveIds: List<Long>,
        cache: WarmReserveSessionCache = WarmReserveSessionCache,
        target: Int = targetCount(),
    ): Int {
        val live = countSessionLive(reserveIds, cache)
        return (target.coerceIn(1, 4) - live).coerceAtLeast(0)
    }

    fun replenishScanCandidates(
        queue: List<Long>,
        connectedId: Long,
        reserveIds: List<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        cache: WarmReserveSessionCache = WarmReserveSessionCache,
        scanLimit: Int = Probe2kDefaults.WARM_REPLENISH_SCAN_LIMIT,
    ): List<Long> {
        if (scanLimit <= 0 || queue.size <= 1) return emptyList()
        val reserveSet = reserveIds.toSet()
        val startIdx = queue.indexOf(connectedId).let { if (it >= 0) it + 1 else 0 }
        return queue.drop(startIdx)
            .filter { id ->
                id != connectedId &&
                    id !in reserveSet &&
                    !cache.isSessionLive(id) &&
                    !cache.isWarmFailed(id) &&
                    ProbePoolEligibility.isSelectableForConnect(probeStates[id])
            }
            .take(scanLimit)
    }

    fun replenishCandidates(
        queue: List<Long>,
        connectedId: Long,
        reserveIds: List<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        limit: Int,
    ): List<Long> = replenishScanCandidates(
        queue = queue,
        connectedId = connectedId,
        reserveIds = reserveIds,
        probeStates = probeStates,
        scanLimit = limit,
    )

    fun updateStatusSnapshot(
        reserveIds: List<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        cache: WarmReserveSessionCache = WarmReserveSessionCache,
    ) {
        val target = targetCount()
        val live = countSessionLive(reserveIds, cache)
        DataStore.probe2kWarmReserveStatus = "$live/$target live"
    }

    fun countSessionLiveInQueue(
        queue: List<Long>,
        cache: WarmReserveSessionCache = WarmReserveSessionCache,
    ): Int = queue.count { cache.isSessionLive(it) }

    private fun queueRankComparator(
        queue: List<Long>,
        probeStates: Map<Long, ProxyProbeState>,
    ): Comparator<Long> = compareBy<Long> { id ->
        if (ProxyProbeStateStore.isFreshUrlVerified(probeStates[id])) 0 else 1
    }
        .thenBy { ProxyProbeStateStore.probeStateRank(probeStates[it]) }
        .thenBy { queue.indexOf(it) }
}
