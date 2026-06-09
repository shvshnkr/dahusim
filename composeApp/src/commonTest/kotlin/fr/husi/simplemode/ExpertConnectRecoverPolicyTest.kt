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

    @Test
    fun fullModeHealthRecoverBlockedWhenRecoverDisabled() {
        assertFalse(ExpertConnectRecoverPolicy.allowsFullModeHealthRecover(false, false))
    }

    @Test
    fun fullModeHealthRecoverAllowedWhenRecoverEnabled() {
        assertTrue(ExpertConnectRecoverPolicy.allowsFullModeHealthRecover(false, true))
    }

    @Test
    fun simpleModeAlwaysAllowsHealthRecover() {
        assertTrue(ExpertConnectRecoverPolicy.allowsFullModeHealthRecover(true, false))
    }
}
