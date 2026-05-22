package fr.husi.database

/**
 * Keeps [DataStore.autoSelectFallbackQueue] across [AutoServerSelector.markConnected] so
 * [AutoServerSelector.tryMoveToFallback] walks the prepare-time ranking while the VPN session
 * stays up. Viable targets skip jail, persisted dead, and recent health-failure cooldown.
 */
internal object AutoServerSelectorSessionFallback {

    data class FallbackWalkResult(
        val nextId: Long,
        val nextIndex: Int,
        val skippedJail: Int,
        val skippedDead: Int,
        val skippedCooldown: Int,
    )

    fun parseQueue(raw: String): List<Long> =
        raw.split(",").mapNotNull { it.trim().toLongOrNull() }

    fun fallbackIndexForConnected(queueIds: List<Long>, connectedProfileId: Long): Int {
        if (queueIds.isEmpty()) return 0
        val idx = queueIds.indexOf(connectedProfileId)
        return if (idx >= 0) idx else 0
    }

    fun syncIndexForConnected(connectedProfileId: Long) {
        val queue = parseQueue(DataStore.autoSelectFallbackQueue)
        if (queue.isEmpty()) return
        DataStore.autoSelectFallbackIndex =
            fallbackIndexForConnected(queue, connectedProfileId)
    }

    /**
     * @param startIndex index after [currentId] in the queue (or persisted fallback index + 1)
     */
    fun findNextFallbackCandidate(
        queue: List<Long>,
        startIndex: Int,
        probeStates: Map<Long, ProxyProbeState>,
        inRecentFailureCooldown: (Long) -> Boolean,
    ): FallbackWalkResult? {
        if (queue.isEmpty() || startIndex >= queue.size) return null
        var skippedJail = 0
        var skippedDead = 0
        var skippedCooldown = 0
        var nextIndex = startIndex
        while (nextIndex < queue.size) {
            val candidate = queue[nextIndex]
            val state = probeStates[candidate]
            when {
                state?.state == ProbeState.CEMETERY -> {
                    skippedJail++
                    nextIndex++
                }
                state?.state == ProbeState.DEAD -> {
                    skippedDead++
                    nextIndex++
                }
                inRecentFailureCooldown(candidate) -> {
                    skippedCooldown++
                    nextIndex++
                }
                else -> {
                    return FallbackWalkResult(
                        nextId = candidate,
                        nextIndex = nextIndex,
                        skippedJail = skippedJail,
                        skippedDead = skippedDead,
                        skippedCooldown = skippedCooldown,
                    )
                }
            }
        }
        return null
    }
}
