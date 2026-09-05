package fr.husi.simplemode

/**
 * Policy for which profile to reload after an ADAPT prepare timeout / all-dead reselect.
 * Session-health-driven rescans must not reload the profile that just failed (zombie
 * recycle); network-handoff/reachability-flip may keep reloading the previous/queue-head.
 */
internal object SimpleModeAdaptTimeoutPolicy {

    /** Sentinel: no reloadable profile — escalate to the all-dead recovery path. */
    const val NO_RELOAD = -1L

    /** A forced full-sweep quick probe takes ~2 min in 0-url-ok conditions; 30s kills it mid-sweep. */
    const val FULL_SWEEP_ADAPT_TIMEOUT_MS = 180_000L

    fun adaptPrepareTimeoutMs(reason: String, networkHandoff: Boolean, fullSweepInProgress: Boolean): Long =
        when {
            networkHandoff -> 45_000L
            fullSweepInProgress -> FULL_SWEEP_ADAPT_TIMEOUT_MS
            else -> 30_000L
        }

    fun isZombieLoopReason(reason: String): Boolean =
        reason == "session_unhealthy" || reason == "session_health_exhausted"

    fun resolvePrepareTimeoutReloadId(
        reason: String,
        previousProfileId: Long,
        selectedProxy: Long,
        fallbackQueueHead: Long?,
        networkHandoff: Boolean,
    ): Long {
        if (selectedProxy > 0L && selectedProxy != previousProfileId) return selectedProxy
        if (networkHandoff) {
            val queueHead = fallbackQueueHead
            if (queueHead != null && queueHead > 0L && queueHead != previousProfileId) {
                return queueHead
            }
        }
        if (isZombieLoopReason(reason)) return NO_RELOAD
        return previousProfileId
    }
}
