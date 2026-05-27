package fr.husi.simplemode

/** Scheduling policy for periodic simple-mode session health checks (unit-testable). */
internal object SimpleModeSessionHealthPolicy {
    const val CHECK_INTERVAL_MS = 30_000L
    const val FIRST_FAIL_FOLLOW_UP_MS = 6_000L
    const val CONSECUTIVE_FAIL_LIMIT_OPEN = 2

    fun nextCheckDelayMs(consecutiveFails: Int, whitelistRestricted: Boolean): Long {
        if (whitelistRestricted || consecutiveFails != 1) return CHECK_INTERVAL_MS
        return FIRST_FAIL_FOLLOW_UP_MS
    }
}
