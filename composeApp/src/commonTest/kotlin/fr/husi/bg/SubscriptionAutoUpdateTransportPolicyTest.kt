package fr.husi.bg

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionAutoUpdateTransportPolicyTest {

    @Test
    fun bypassRetryRequiresStaleFailureVpnAndUplink() {
        assertTrue(
            SubscriptionAutoUpdateTransportPolicy.shouldRetryWithBypass(
                staleTransportFailure = true,
                vpnConnected = true,
                hasAnyInternet = true,
            ),
        )
        assertFalse(
            SubscriptionAutoUpdateTransportPolicy.shouldRetryWithBypass(
                staleTransportFailure = false,
                vpnConnected = true,
                hasAnyInternet = true,
            ),
        )
        assertFalse(
            SubscriptionAutoUpdateTransportPolicy.shouldRetryWithBypass(
                staleTransportFailure = true,
                vpnConnected = false,
                hasAnyInternet = true,
            ),
        )
        assertFalse(
            SubscriptionAutoUpdateTransportPolicy.shouldRetryWithBypass(
                staleTransportFailure = true,
                vpnConnected = true,
                hasAnyInternet = false,
            ),
        )
    }

    @Test
    fun staleTransportMessageHeuristics() {
        assertTrue(
            SubscriptionAutoUpdateTransportPolicy.messageLooksLikeStaleTransport(
                "read tcp: connection reset by peer",
            ),
        )
        assertTrue(
            SubscriptionAutoUpdateTransportPolicy.messageLooksLikeStaleTransport(
                "unexpected eof",
            ),
        )
        assertFalse(
            SubscriptionAutoUpdateTransportPolicy.messageLooksLikeStaleTransport("HTTP 404 Not Found"),
        )
        assertFalse(SubscriptionAutoUpdateTransportPolicy.messageLooksLikeStaleTransport(""))
    }
}
