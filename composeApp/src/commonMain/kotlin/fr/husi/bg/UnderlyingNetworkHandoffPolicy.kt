package fr.husi.bg

/**
 * Decides when the underlying carrier changed enough to rebuild the VPN tunnel.
 *
 * Interface name alone is insufficient: Wi‑Fi reconnect often keeps `wlan0` while IP,
 * routes, and the default-network handle change. Handoff must also run on same-name
 * restore after loss and on link-index rebound when the OS skips a loss callback.
 */
internal object UnderlyingNetworkHandoffPolicy {

    const val REASON_CROSS_INTERFACE = "cross_interface"
    const val REASON_CARRIER_RESTORE = "carrier_restore"
    const val REASON_LINK_REBOUND = "link_rebound"

    data class Snapshot(
        /** True when VPN is up or still finishing the connect job (underlying iface may flip mid-connect). */
        val vpnSessionActive: Boolean,
        val interfaceName: String?,
        val interfaceIndex: Int,
        val lastInterfaceName: String?,
        val lastInterfaceIndex: Int,
        val previousInterfaceForHandoff: String?,
        val underlyingCarrierLostWhileConnected: Boolean,
    )

    /** Non-null reason when the tunnel should be rebuilt; null when unchanged. */
    fun evaluate(snapshot: Snapshot): String? {
        if (!snapshot.vpnSessionActive) return null
        val name = snapshot.interfaceName?.takeIf { it.isNotBlank() } ?: return null

        val previous = snapshot.previousInterfaceForHandoff
        if (previous != null && previous != name) {
            return REASON_CROSS_INTERFACE
        }

        if (snapshot.underlyingCarrierLostWhileConnected && (previous == null || previous == name)) {
            return REASON_CARRIER_RESTORE
        }

        if (snapshot.lastInterfaceName == name &&
            snapshot.lastInterfaceIndex >= 0 &&
            snapshot.interfaceIndex != snapshot.lastInterfaceIndex
        ) {
            return REASON_LINK_REBOUND
        }

        return null
    }
}
