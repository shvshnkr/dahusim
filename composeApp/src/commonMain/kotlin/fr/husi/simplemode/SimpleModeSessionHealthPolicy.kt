package fr.husi.simplemode

/** Scheduling policy for periodic simple-mode session health checks (unit-testable). */
internal object SimpleModeSessionHealthPolicy {
    const val STALL_PROBE_ERROR = "session_health_probe_stall"
    const val CHECK_INTERVAL_MS = 30_000L
    const val FIRST_FAIL_FOLLOW_UP_MS = 6_000L
    const val CONSECUTIVE_FAIL_LIMIT_OPEN = 2
    const val ON_DEMAND_MIN_GAP_MS = 15_000L
    const val ON_DEMAND_UI_MIN_GAP_MS = 8_000L
    const val RECENT_FAIL_WINDOW_MS = 120_000L
    /** Poll interval for the parallel stall watchdog (independent of a stuck urlTest). */
    const val STALL_TICK_MS = 10_000L
    /**
     * If no health check completes within this window while connected, force recovery.
     * Must exceed [CHECK_INTERVAL_MS] plus probe budget so a slow-but-live check is not cut off.
     */
    const val STALL_RECOVERY_MS = 45_000L
    /**
     * When no health check completes within this window, re-bind the watchdog (job may have exited).
     * Shorter than [STALL_RECOVERY_MS] so ui_attach can restart monitoring before stall tears down VPN.
     */
    const val MONITORING_STALE_MS = CHECK_INTERVAL_MS + 10_000L
    /** First periodic check after connect — after post-connect warmup, before post_connect verify returns. */
    const val CONNECT_FIRST_CHECK_DELAY_MS = 12_000L

    fun isMonitoringStale(lastCheckCompletedAt: Long, nowMs: Long): Boolean {
        if (lastCheckCompletedAt <= 0L) return true
        return nowMs - lastCheckCompletedAt >= MONITORING_STALE_MS
    }

    fun nextCheckDelayMs(consecutiveFails: Int, whitelistRestricted: Boolean): Long {
        if (whitelistRestricted || consecutiveFails != 1) return CHECK_INTERVAL_MS
        return FIRST_FAIL_FOLLOW_UP_MS
    }

    /** Re-bind watchdog after returning to simple UI while VPN stayed up (H34). */
    fun shouldRescheduleMonitoringWhenSessionMissing(reason: String): Boolean =
        reason == "ui_attach" || reason == "ui_resume"

    fun onDemandMinGapMs(reason: String): Long =
        if (reason == "ui_resume" || reason == "ui_attach") {
            ON_DEMAND_UI_MIN_GAP_MS
        } else {
            ON_DEMAND_MIN_GAP_MS
        }
}
