package fr.husi.simplemode

import fr.husi.database.DataStore
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeTunnelSoftRecoveryPolicyTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        SimpleModeTunnelSoftRecoveryPolicy.resetDebounce()
    }

    @Test
    fun openStaleTransportEligible() {
        assertTrue(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                "dial tcp 1.2.3.4:443: i/o timeout",
                whitelistOnly = false,
            ),
        )
        assertTrue(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                SimpleModeSessionHealthPolicy.STALL_PROBE_ERROR,
                whitelistOnly = false,
            ),
        )
    }

    @Test
    fun openMessengerLookupNotEligible() {
        assertFalse(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                "lookup gb.nodes.example: connection refused",
                whitelistOnly = false,
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
    }

    @Test
    fun wlBootstrapEligibleMessengerLookupNot() {
        val bootstrap = "dial rmnet_data1 (17): dial tcp 1.2.3.4:443: i/o timeout"
        assertTrue(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                bootstrap,
                whitelistOnly = true,
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                "lookup web.telegram.org: no such host",
                whitelistOnly = true,
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
    }

    @Test
    fun wl405AndRateLimitNotEligible() {
        assertFalse(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                "method not allowed",
                whitelistOnly = true,
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                "unexpected HTTP response status: 429",
                whitelistOnly = true,
            ),
        )
    }

    @Test
    fun softRecoveryDebounceGap() {
        val now = 1_000_000L
        val err = SimpleModeSessionHealthPolicy.STALL_PROBE_ERROR
        assertTrue(
            SimpleModeTunnelSoftRecoveryPolicy.shouldAttemptSoftRecovery(
                error = err,
                whitelistOnly = false,
                probeUrl = null,
                nowMs = now,
                simpleMode = true,
                connected = true,
            ),
        )
        SimpleModeTunnelSoftRecoveryPolicy.markAttempt(now)
        assertFalse(
            SimpleModeTunnelSoftRecoveryPolicy.shouldAttemptSoftRecovery(
                error = err,
                whitelistOnly = false,
                probeUrl = null,
                nowMs = now + 10_000L,
                simpleMode = true,
                connected = true,
            ),
        )
        assertTrue(
            SimpleModeTunnelSoftRecoveryPolicy.shouldAttemptSoftRecovery(
                error = err,
                whitelistOnly = false,
                probeUrl = null,
                nowMs = now + SimpleModeTunnelSoftRecoveryPolicy.SOFT_RESET_MIN_GAP_MS,
                simpleMode = true,
                connected = true,
            ),
        )
    }

    @Test
    fun reprobeBudgetsPerMode() {
        assertEquals(400L, SimpleModeTunnelSoftRecoveryPolicy.reprobeWarmupMs(whitelistOnly = false))
        assertEquals(2_500L, SimpleModeTunnelSoftRecoveryPolicy.reprobeWarmupMs(whitelistOnly = true))
        assertEquals(
            listOf(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM),
            SimpleModeTunnelSoftRecoveryPolicy.reprobeUrls(whitelistOnly = true),
        )
        DataStore.simpleModeTelegramProbe = false
        assertTrue(
            SimpleModeTunnelSoftRecoveryPolicy.reprobeUrls(whitelistOnly = false)
                .any { it.contains("gstatic") },
        )
    }

    @Test
    fun softReprobeDoesNotEscalateToConfirmOnWl() {
        assertFalse(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = SimpleModeTunnelSoftRecoveryPolicy.SOFT_REPROBE_PHASE,
                    whitelistOnly = true,
                    primaryProbeFailed = true,
                    lastProbeError = "dial rmnet_data1: i/o timeout",
                ),
            ),
        )
    }
}
