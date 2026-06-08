package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpertConnectRecoverPolicyTest {

    @Test
    fun fullModeFallbackBlockedWhenRecoverDisabled() {
        assertFalse(ExpertConnectRecoverPolicy.allowsFullModeSessionFallback(false, false))
    }

    @Test
    fun fullModeFallbackAllowedWhenRecoverEnabled() {
        assertTrue(ExpertConnectRecoverPolicy.allowsFullModeSessionFallback(false, true))
    }

    @Test
    fun simpleModeAlwaysAllowsFallback() {
        assertTrue(ExpertConnectRecoverPolicy.allowsFullModeSessionFallback(true, false))
    }
}
