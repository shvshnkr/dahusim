package fr.husi.simplemode

/**
 * Per-VPN-session warm reserve state: which ids passed or failed warm URL verify in this connection.
 * Cleared when [WarmReserveMaintainer] schedules or cancels.
 */
object WarmReserveSessionCache {

    private val liveVerifiedIds = mutableSetOf<Long>()
    private val warmFailedIds = mutableSetOf<Long>()

    fun clear() {
        liveVerifiedIds.clear()
        warmFailedIds.clear()
    }

    fun markLive(id: Long) {
        if (id <= 0L) return
        liveVerifiedIds.add(id)
        warmFailedIds.remove(id)
    }

    fun markWarmFailed(id: Long) {
        if (id <= 0L) return
        warmFailedIds.add(id)
        liveVerifiedIds.remove(id)
    }

    fun isSessionLive(id: Long): Boolean = id in liveVerifiedIds

    fun isWarmFailed(id: Long): Boolean = id in warmFailedIds

    fun liveCount(): Int = liveVerifiedIds.size

    fun warmFailedIdsSnapshot(): Set<Long> = warmFailedIds.toSet()
}
