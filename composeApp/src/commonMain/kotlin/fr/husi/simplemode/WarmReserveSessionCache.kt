package fr.husi.simplemode

/**
 * Per-VPN-session warm reserve state: which ids passed or failed warm URL verify in this connection.
 * Cleared when [WarmReserveMaintainer] schedules or cancels.
 */
object WarmReserveSessionCache {

    private val liveVerifiedIds = mutableSetOf<Long>()
    private val warmFailedIds = mutableSetOf<Long>()
    private var lastVerifySuccessAtMs: Long = 0L

    fun clear() {
        liveVerifiedIds.clear()
        warmFailedIds.clear()
        lastVerifySuccessAtMs = 0L
    }

    fun noteWarmVerifySuccess(nowMs: Long = System.currentTimeMillis()) {
        lastVerifySuccessAtMs = nowMs
    }

    fun hasRecentVerifySuccess(withinMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        lastVerifySuccessAtMs > 0L && nowMs - lastVerifySuccessAtMs <= withinMs

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

    internal fun liveVerifiedIdsSnapshot(): Set<Long> = liveVerifiedIds.toSet()
}
