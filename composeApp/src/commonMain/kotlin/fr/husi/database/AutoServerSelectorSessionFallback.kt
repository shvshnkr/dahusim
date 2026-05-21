package fr.husi.database

/**
 * Keeps [DataStore.autoSelectFallbackQueue] across [AutoServerSelector.markConnected] so
 * [AutoServerSelector.tryMoveToFallback] works while the VPN session stays up.
 */
internal object AutoServerSelectorSessionFallback {

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
}
