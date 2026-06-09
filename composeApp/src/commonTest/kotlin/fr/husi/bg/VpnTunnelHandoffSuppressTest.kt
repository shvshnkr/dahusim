package fr.husi.bg

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VpnTunnelHandoffSuppressTest {

    @Test
    fun tunInterfaceIsNotPollableUplink() {
        assertFalse(VpnTunnelHandoffSuppress.isPollableUplink("tun0"))
        assertFalse(VpnTunnelHandoffSuppress.isPollableUplink("TUN1"))
    }

    @Test
    fun carrierUplinkIsPollable() {
        assertTrue(VpnTunnelHandoffSuppress.isPollableUplink("wlan0"))
        assertTrue(VpnTunnelHandoffSuppress.isPollableUplink("rmnet_data0"))
    }

    @Test
    fun suppressTunnelHandoffDuringCarrierLoss() {
        VpnTunnelHandoffSuppress.clear()
        assertTrue(
            VpnTunnelHandoffSuppress.shouldSuppressHandoffToTunnel(
                interfaceName = "tun0",
                carrierLostWhileConnected = true,
            ),
        )
    }

    @Test
    fun postConnectGraceSuppressesTunnelHandoff() {
        VpnTunnelHandoffSuppress.markVpnSessionAnchor()
        try {
            assertTrue(VpnTunnelHandoffSuppress.shouldSuppressHandoffToTunnel("tun0"))
        } finally {
            VpnTunnelHandoffSuppress.clear()
        }
    }
}
