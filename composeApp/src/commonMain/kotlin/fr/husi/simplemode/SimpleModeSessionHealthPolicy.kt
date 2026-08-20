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
    const val MAX_STALL_DEFER_PER_WINDOW = 2
    const val STALL_DEFER_WINDOW_MS = 120_000L
    const val STALL_DEFER_PRIMARY_OK_MS = 60_000L
    const val WARM_STALL_DEFER_MS = 45_000L
    /** Absolute stall ceiling: never defer once the tunnel has been frozen this long. */
    const val STALL_RECOVERY_HARD_CAP_MS = 90_000L
    /** Successful stall recoveries in one session after which the server is switched anyway. */
    const val MAX_STALL_RECOVERY_OK_PER_SESSION = 3
    /** Post-connect verify must finish within this window or trigger fallback. */
    const val POST_CONNECT_WATCHDOG_MS = 28_000L
    /**
     * Consecutive synthetic-only (inconclusive) WL health cycles after which the session is
     * treated as unhealthy and recovery/fallback runs. Without the bound, a profile whose
     * server is unreachable from the BS uplink (e.g. hosted on a non-whitelisted IP) stays
     * "Connected" on a dead tunnel forever: every check produces the same dial error, which
     * the bootstrap classification marks inconclusive and resets the fail streak.
     */
    const val WL_SYNTHETIC_PASS_LIMIT = 3

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

    fun shouldDeferStallRecovery(
        tracker: StallDeferTracker,
        nowMs: Long,
        stalledMs: Long,
        consecutiveFails: Int,
        lastHealthOkAt: Long,
        warmReserveVerifiedRecently: Boolean,
        profileSessionLive: Boolean,
        whitelistOnly: Boolean,
    ): Boolean {
        if (stalledMs >= STALL_RECOVERY_HARD_CAP_MS) return false
        if (consecutiveFails > 0) return false
        if (!warmReserveVerifiedRecently && !profileSessionLive) return false
        if (!whitelistOnly) {
            if (lastHealthOkAt <= 0L || nowMs - lastHealthOkAt >= STALL_DEFER_PRIMARY_OK_MS) {
                return false
            }
            if (!warmReserveVerifiedRecently) return false
        }
        return tracker.tryDefer(nowMs, warmReserveVerifiedRecently, profileSessionLive)
    }
}

class StallDeferTracker(
    private val maxDefers: Int = SimpleModeSessionHealthPolicy.MAX_STALL_DEFER_PER_WINDOW,
    private val windowMs: Long = SimpleModeSessionHealthPolicy.STALL_DEFER_WINDOW_MS,
) {
    private var windowStartMs = 0L
    private var deferCount = 0

    fun reset() {
        windowStartMs = 0L
        deferCount = 0
    }

    fun tryDefer(nowMs: Long, warmReserveVerifiedRecently: Boolean, profileSessionLive: Boolean): Boolean {
        if (!warmReserveVerifiedRecently && !profileSessionLive) return false
        if (windowStartMs == 0L || nowMs - windowStartMs > windowMs) {
            windowStartMs = nowMs
            deferCount = 0
        }
        if (deferCount >= maxDefers) return false
        deferCount++
        return true
    }
}
