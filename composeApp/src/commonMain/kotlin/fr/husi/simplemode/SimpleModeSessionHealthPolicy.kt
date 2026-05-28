package fr.husi.simplemode

/** Scheduling policy for periodic simple-mode session health checks (unit-testable). */
internal object SimpleModeSessionHealthPolicy {
    const val CHECK_INTERVAL_MS = 30_000L
    const val FIRST_FAIL_FOLLOW_UP_MS = 6_000L
    const val CONSECUTIVE_FAIL_LIMIT_OPEN = 2
    const val ON_DEMAND_MIN_GAP_MS = 15_000L
    const val ON_DEMAND_UI_MIN_GAP_MS = 8_000L
    const val RECENT_FAIL_WINDOW_MS = 120_000L

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
