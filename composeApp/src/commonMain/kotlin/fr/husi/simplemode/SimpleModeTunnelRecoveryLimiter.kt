package fr.husi.simplemode

/**
 * Caps rapid tunnel reloads from post-connect / session-recover fallback chains on flaky
 * uplink (WL handoff, cellular iface) so the client prefers one ADAPT reselect instead.
 */
internal object SimpleModeTunnelRecoveryLimiter {

    const val WINDOW_MS = 90_000L
    const val MAX_RELOADS_PER_WINDOW = 3

    private val lock = Any()

    private var windowStartMs = 0L

    private var reloadCount = 0

    fun resetOnHealthyConnect() {
        synchronized(lock) {
            windowStartMs = 0L
            reloadCount = 0
        }
    }

    /**
     * @return true when a counted reload may proceed; false → caller should run ADAPT once.
     */
    fun tryConsumeReload(reason: String): Boolean {
        if (!isCountedReason(reason)) return true
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (windowStartMs == 0L || now - windowStartMs > WINDOW_MS) {
                windowStartMs = now
                reloadCount = 0
            }
            if (reloadCount >= MAX_RELOADS_PER_WINDOW) {
                return false
            }
            reloadCount++
            return true
        }
    }

    private fun isCountedReason(reason: String): Boolean =
        reason == "session_recover_fallback" ||
            reason == "post_connect_unhealthy_switch" ||
            reason.startsWith("post_connect_unhealthy") ||
            reason.startsWith("session_recover")
}
