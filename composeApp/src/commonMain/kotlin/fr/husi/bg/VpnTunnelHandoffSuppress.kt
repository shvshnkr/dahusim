package fr.husi.bg

/** Suppress false uplink→tun handoff right after VPN connect (tun0 is not a carrier change). */
internal object VpnTunnelHandoffSuppress {

    private const val GRACE_MS = 15_000L

    @Volatile
    private var sessionAnchorMs = 0L

    fun markVpnSessionAnchor() {
        sessionAnchorMs = System.currentTimeMillis()
    }

    fun clear() {
        sessionAnchorMs = 0L
    }

    fun isVpnTunnelInterface(name: String): Boolean =
        name.startsWith("tun", ignoreCase = true)

    fun shouldSuppressHandoffToTunnel(interfaceName: String): Boolean {
        if (!isVpnTunnelInterface(interfaceName)) return false
        val anchor = sessionAnchorMs
        if (anchor <= 0L) return false
        return System.currentTimeMillis() - anchor < GRACE_MS
    }
}
