package fr.husi.bg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UnderlyingNetworkHandoffPolicyTest {

    @Test
    fun crossInterfaceHandoff() {
        val reason = UnderlyingNetworkHandoffPolicy.evaluate(
            snapshot(
                previousInterfaceForHandoff = "wlan0",
                interfaceName = "rmnet_data2",
            ),
        )
        assertEquals(UnderlyingNetworkHandoffPolicy.REASON_CROSS_INTERFACE, reason)
    }

    @Test
    fun crossInterfaceHandoffWhileVpnConnecting() {
        val reason = UnderlyingNetworkHandoffPolicy.evaluate(
            snapshot(
                vpnSessionActive = true,
                previousInterfaceForHandoff = "wlan0",
                interfaceName = "rmnet_data1",
            ),
        )
        assertEquals(UnderlyingNetworkHandoffPolicy.REASON_CROSS_INTERFACE, reason)
    }

    @Test
    fun carrierRestoreAfterWifiReconnect() {
        val reason = UnderlyingNetworkHandoffPolicy.evaluate(
            snapshot(
                previousInterfaceForHandoff = "wlan0",
                interfaceName = "wlan0",
                lastInterfaceName = null,
                lastInterfaceIndex = -1,
                underlyingCarrierLostWhileConnected = true,
            ),
        )
        assertEquals(UnderlyingNetworkHandoffPolicy.REASON_CARRIER_RESTORE, reason)
    }

    @Test
    fun linkReboundSameNameDifferentIndex() {
        val reason = UnderlyingNetworkHandoffPolicy.evaluate(
            snapshot(
                interfaceName = "wlan0",
                interfaceIndex = 27,
                lastInterfaceName = "wlan0",
                lastInterfaceIndex = 26,
            ),
        )
        assertEquals(UnderlyingNetworkHandoffPolicy.REASON_LINK_REBOUND, reason)
    }

    @Test
    fun noHandoffWhenVpnDown() {
        assertNull(
            UnderlyingNetworkHandoffPolicy.evaluate(
                snapshot(vpnSessionActive = false, underlyingCarrierLostWhileConnected = true),
            ),
        )
    }

    @Test
    fun noHandoffOnStableInterface() {
        assertNull(
            UnderlyingNetworkHandoffPolicy.evaluate(
                snapshot(
                    interfaceName = "wlan0",
                    interfaceIndex = 26,
                    lastInterfaceName = "wlan0",
                    lastInterfaceIndex = 26,
                    previousInterfaceForHandoff = "wlan0",
                ),
            ),
        )
    }

    @Test
    fun carrierRestoreTakesPrecedenceOverLinkReboundAfterLoss() {
        val reason = UnderlyingNetworkHandoffPolicy.evaluate(
            snapshot(
                previousInterfaceForHandoff = "wlan0",
                interfaceName = "wlan0",
                interfaceIndex = 27,
                lastInterfaceName = null,
                lastInterfaceIndex = -1,
                underlyingCarrierLostWhileConnected = true,
            ),
        )
        assertEquals(UnderlyingNetworkHandoffPolicy.REASON_CARRIER_RESTORE, reason)
    }

    private fun snapshot(
        vpnSessionActive: Boolean = true,
        interfaceName: String? = "wlan0",
        interfaceIndex: Int = 26,
        lastInterfaceName: String? = "wlan0",
        lastInterfaceIndex: Int = 26,
        previousInterfaceForHandoff: String? = "wlan0",
        underlyingCarrierLostWhileConnected: Boolean = false,
    ) = UnderlyingNetworkHandoffPolicy.Snapshot(
        vpnSessionActive = vpnSessionActive,
        interfaceName = interfaceName,
        interfaceIndex = interfaceIndex,
        lastInterfaceName = lastInterfaceName,
        lastInterfaceIndex = lastInterfaceIndex,
        previousInterfaceForHandoff = previousInterfaceForHandoff,
        underlyingCarrierLostWhileConnected = underlyingCarrierLostWhileConnected,
    )
}
