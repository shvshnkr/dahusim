package fr.husi.simplemode

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
