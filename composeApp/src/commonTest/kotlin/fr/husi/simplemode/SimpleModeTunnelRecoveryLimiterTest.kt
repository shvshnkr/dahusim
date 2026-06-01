package fr.husi.simplemode

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeTunnelRecoveryLimiterTest {

    @BeforeTest
    fun resetLimiter() {
        SimpleModeTunnelRecoveryLimiter.resetOnHealthyConnect()
    }

    @Test
    fun allowsCountedReloadsUpToCapThenBlocks() {
        assertTrue(SimpleModeTunnelRecoveryLimiter.tryConsumeReload("session_recover_fallback"))
        assertTrue(SimpleModeTunnelRecoveryLimiter.tryConsumeReload("session_recover_fallback"))
        assertTrue(SimpleModeTunnelRecoveryLimiter.tryConsumeReload("post_connect_unhealthy_switch"))
        assertFalse(SimpleModeTunnelRecoveryLimiter.tryConsumeReload("session_recover_fallback"))
    }

    @Test
    fun resetOnHealthyConnectReopensWindow() {
        repeat(SimpleModeTunnelRecoveryLimiter.MAX_RELOADS_PER_WINDOW) {
            assertTrue(SimpleModeTunnelRecoveryLimiter.tryConsumeReload("session_recover_fallback"))
        }
        assertFalse(SimpleModeTunnelRecoveryLimiter.tryConsumeReload("session_recover_fallback"))
        SimpleModeTunnelRecoveryLimiter.resetOnHealthyConnect()
        assertTrue(SimpleModeTunnelRecoveryLimiter.tryConsumeReload("session_recover_fallback"))
    }

    @Test
    fun uncountedReasonAlwaysAllowed() {
        repeat(10) {
            assertTrue(SimpleModeTunnelRecoveryLimiter.tryConsumeReload("session_unhealthy"))
        }
    }
}
