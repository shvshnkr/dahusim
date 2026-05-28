package fr.husi.bg

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhitelistNetworkHandoffPolicyTest {

    @Test
    fun staleHandoffRequiresCarrierRestoreAndLongLoss() {
        assertTrue(
            WhitelistNetworkHandoffPolicy.isStaleHandoffTunnelReload(
                UnderlyingNetworkHandoffPolicy.REASON_CARRIER_RESTORE,
                elapsedFromLossMs = 5_000L,
            ),
        )
        assertFalse(
            WhitelistNetworkHandoffPolicy.isStaleHandoffTunnelReload(
                UnderlyingNetworkHandoffPolicy.REASON_CARRIER_RESTORE,
                elapsedFromLossMs = 4_999L,
            ),
        )
        assertFalse(
            WhitelistNetworkHandoffPolicy.isStaleHandoffTunnelReload(
                UnderlyingNetworkHandoffPolicy.REASON_CROSS_INTERFACE,
                elapsedFromLossMs = 10_000L,
            ),
        )
    }

    @Test
    fun reachabilityFlipReloadSkippedOnStaleHandoff() {
        assertFalse(
            WhitelistNetworkHandoffPolicy.shouldRequestReloadOnReachabilityFlip(staleHandoff = true),
        )
        assertTrue(
            WhitelistNetworkHandoffPolicy.shouldRequestReloadOnReachabilityFlip(staleHandoff = false),
        )
    }

    @Test
    fun suppressExitRuReloadInsideHealthyWindow() {
        val now = 1_000L
        assertTrue(
            WhitelistNetworkHandoffPolicy.shouldSuppressExitRuRoutingReload(
                reason = "exit_country_ru_routing",
                nowMs = now,
                suppressUntilMs = 2_000L,
            ),
        )
        assertFalse(
            WhitelistNetworkHandoffPolicy.shouldSuppressExitRuRoutingReload(
                reason = "exit_country_ru_routing",
                nowMs = 3_000L,
                suppressUntilMs = 2_000L,
            ),
        )
        assertFalse(
            WhitelistNetworkHandoffPolicy.shouldSuppressExitRuRoutingReload(
                reason = "reachability_flip",
                nowMs = now,
                suppressUntilMs = 2_000L,
            ),
        )
    }
}
