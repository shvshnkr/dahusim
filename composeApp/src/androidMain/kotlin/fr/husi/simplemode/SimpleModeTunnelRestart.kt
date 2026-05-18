package fr.husi.simplemode

import fr.husi.bg.NetworkReachability

/**
 * Reuse recent reachability while the VPN tunnel is restarting (fallback / session health).
 * Avoids probing through a dying tunnel and false "no internet" pauses.
 */
internal object SimpleModeTunnelRestart {

    private const val GRACE_MS = 45_000L

    @Volatile
    private var cached: NetworkReachability? = null

    @Volatile
    private var validUntilMs: Long = 0L

    fun markReconnect(reachability: NetworkReachability) {
        cached = reachability
        validUntilMs = System.currentTimeMillis() + GRACE_MS
    }

    fun markOpenInternetReconnect() {
        markReconnect(
            NetworkReachability(
                googleReachable = true,
                dzenReachable = false,
                yaReachable = false,
                whitelistSourceReachable = false,
            ),
        )
    }

    fun markWhitelistRestrictedReconnect() {
        markReconnect(
            NetworkReachability(
                googleReachable = false,
                dzenReachable = false,
                yaReachable = false,
                whitelistSourceReachable = true,
            ),
        )
    }

    fun markModeReconnect(whitelistOnly: Boolean) {
        if (whitelistOnly) {
            markWhitelistRestrictedReconnect()
        } else {
            markOpenInternetReconnect()
        }
    }

    fun takeCachedReachability(): NetworkReachability? {
        val snapshot = cached ?: return null
        if (System.currentTimeMillis() > validUntilMs) {
            clear()
            return null
        }
        clear()
        return snapshot
    }

    private fun clear() {
        cached = null
        validUntilMs = 0L
    }
}
