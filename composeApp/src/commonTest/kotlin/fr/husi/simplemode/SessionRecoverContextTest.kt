package fr.husi.simplemode

import fr.husi.bg.UnderlyingCarrierState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionRecoverContextTest {

    @Test
    fun exhaustedContextNeverAllowsSoftRecover() {
        assertFalse(
            SimpleModeHealthRoute.allowsInconclusiveSoftRecover(
                context = SessionRecoverContext.PostConnectExhausted,
                error = "context deadline exceeded",
                whitelistOnly = false,
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
    }

    @Test
    fun bootstrapMayAllowOpenTelegramBootstrapTimeout() {
        assertTrue(
            SimpleModeHealthRoute.allowsInconclusiveSoftRecover(
                context = SessionRecoverContext.PostConnectBootstrap,
                error = "timeout: no recent network activity",
                whitelistOnly = false,
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
    }

    @Test
    fun carrierOutageErrorsAllowSoftRecoverDuringAwaitingRestore() {
        UnderlyingCarrierState.clear()
        try {
            UnderlyingCarrierState.markAwaitingRestoreForTest()
            assertTrue(
                SimpleModeHealthRoute.isCarrierOutageProbeFailure("no available network interface"),
            )
            assertTrue(
                SimpleModeHealthRoute.allowsInconclusiveSoftRecover(
                    context = SessionRecoverContext.SessionHealth,
                    error = "resource temporarily unavailable",
                    whitelistOnly = false,
                    probeUrl = null,
                ),
            )
            assertTrue(
                SimpleModeHealthRoute.allowsInconclusiveSoftRecover(
                    context = SessionRecoverContext.StallWatchdog,
                    error = "network changed",
                    whitelistOnly = false,
                    probeUrl = null,
                ),
            )
        } finally {
            UnderlyingCarrierState.clear()
        }
    }

    @Test
    fun carrierOutageSoftRecoverRequiresAwaitingRestore() {
        UnderlyingCarrierState.clear()
        assertFalse(
            SimpleModeHealthRoute.allowsInconclusiveSoftRecover(
                context = SessionRecoverContext.SessionHealth,
                error = "no available network interface",
                whitelistOnly = false,
                probeUrl = null,
            ),
        )
    }

    @Test
    fun stallErrorInconclusiveOnlyOnPostConnectPhase() {
        val err = SimpleModeSessionHealthPolicy.STALL_PROBE_ERROR
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(err, whitelistOnly = false, phase = "post_connect"),
        )
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(err, whitelistOnly = false, phase = "session_periodic"),
        )
    }
}
