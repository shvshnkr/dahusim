package fr.husi.simplemode

/** When to flush stale sing-box upstream TCP vs go straight to hard fallback/reload. */
internal object SimpleModeTunnelSoftRecoveryPolicy {

    const val SOFT_RESET_MIN_GAP_MS = 30_000L
    const val SOFT_REPROBE_PHASE = "session_soft_reprobe"

    private var lastSoftResetAtMs = 0L

    fun resetDebounce() {
        lastSoftResetAtMs = 0L
    }

    fun shouldAttemptSoftRecovery(
        error: String?,
        whitelistOnly: Boolean,
        probeUrl: String?,
        nowMs: Long,
        simpleMode: Boolean,
        connected: Boolean,
    ): Boolean {
        if (!simpleMode || !connected) return false
        if (!SimpleModeHealthRoute.isSoftRecoveryEligible(error, whitelistOnly, probeUrl)) return false
        if (lastSoftResetAtMs > 0L && nowMs - lastSoftResetAtMs < SOFT_RESET_MIN_GAP_MS) return false
        return true
    }

    fun markAttempt(nowMs: Long) {
        lastSoftResetAtMs = nowMs
    }

    fun reprobeWarmupMs(whitelistOnly: Boolean): Long =
        SimpleModeHealthRoute.postConnectWarmupMs(whitelistOnly)

    fun reprobeTimeoutMs(whitelistOnly: Boolean, baseTimeoutMs: Int): Int =
        SimpleModeHealthRoute.postConnectTimeoutMs(whitelistOnly, baseTimeoutMs)

    fun reprobeUrls(whitelistOnly: Boolean): List<String> =
        if (whitelistOnly) {
            SimpleModeHealthRoute.primaryBsProbeUrls()
        } else {
            SimpleModeHealthRoute.healthCheckUrls(whitelistOnly = false)
        }
}
