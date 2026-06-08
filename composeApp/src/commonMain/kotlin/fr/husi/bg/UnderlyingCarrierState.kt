package fr.husi.bg

/**
 * Read-only carrier-loss state for simple-mode health/coordinator while Android polls for restore.
 * Updated from [DefaultNetworkMonitor] on the default-network callback path and restore watchdog.
 */
internal object UnderlyingCarrierState {

    @Volatile
    var awaitingRestore: Boolean = false
        private set

    @Volatile
    private var lostAtMs: Long = 0L

    /** True after VPN-up carrier loss until session reconnect or [clear]. */
    @Volatile
    var outageDuringVpnSession: Boolean = false
        private set

    fun onCarrierLost(vpnConnected: Boolean) {
        if (!vpnConnected) {
            awaitingRestore = false
            lostAtMs = 0L
            return
        }
        awaitingRestore = true
        outageDuringVpnSession = true
        lostAtMs = System.currentTimeMillis()
    }

    fun onCarrierRestored() {
        awaitingRestore = false
        lostAtMs = 0L
    }

    fun elapsedSinceLossMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (lostAtMs <= 0L) return -1L
        return (nowMs - lostAtMs).coerceAtLeast(0L)
    }

    fun clear() {
        awaitingRestore = false
        lostAtMs = 0L
        outageDuringVpnSession = false
    }

    internal fun markAwaitingRestoreForTest() {
        awaitingRestore = true
        outageDuringVpnSession = true
        lostAtMs = System.currentTimeMillis()
    }
}
