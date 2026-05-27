package fr.husi.database

import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryMatrixTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
    }

    @Test
    fun fallbackStepLimitsMatrix() {
        DataStore.simpleModeAutoselectPoolMerged = false
        assertEquals(ConnectPoolPolicy.MAX_SESSION_FALLBACK_STEPS_WL, WlAutoselectPolicy.maxSessionFallbackSteps(true))

        DataStore.simpleModeAutoselectPoolMerged = true
        assertEquals(ConnectPoolPolicy.MAX_SESSION_FALLBACK_STEPS_OPEN, WlAutoselectPolicy.maxSessionFallbackSteps(true))

        DataStore.simpleModeAutoselectPoolMerged = false
        assertEquals(ConnectPoolPolicy.MAX_SESSION_FALLBACK_STEPS_OPEN, WlAutoselectPolicy.maxSessionFallbackSteps(false))
    }

    @Test
    fun recoveryMatrixFindsNextCandidateOrExhausts() {
        val queue = listOf(10L, 20L, 30L, 40L, 50L)
        val deadAndJail = mapOf(
            20L to ProxyProbeState(profileId = 20L, state = ProbeState.DEAD),
            30L to ProxyProbeState(profileId = 30L, state = ProbeState.CEMETERY),
        )

        val recoveredInThree = AutoServerSelectorSessionFallback.findNextFallbackCandidate(
            queue = queue,
            startIndex = 1,
            probeStates = deadAndJail,
            inRecentFailureCooldown = { it == 40L },
        )
        requireNotNull(recoveredInThree)
        assertEquals(50L, recoveredInThree.nextId)
        assertEquals(4, recoveredInThree.nextIndex)

        val exhausted = AutoServerSelectorSessionFallback.findNextFallbackCandidate(
            queue = queue,
            startIndex = 1,
            probeStates = deadAndJail + (50L to ProxyProbeState(profileId = 50L, state = ProbeState.DEAD)),
            inRecentFailureCooldown = { it == 40L },
        )
        assertNull(exhausted)
    }

    @Test
    fun degradedProfilePenaltyWindowMatrix() {
        val now = System.currentTimeMillis()
        AutoServerSelectorProbePolicy.recordDegradedProfile(3325L, nowMs = now)
        assertTrue(AutoServerSelectorProbePolicy.isRecentlyDegraded(3325L, nowMs = now + 5_000L))
        assertTrue(AutoServerSelectorProbePolicy.isRecentlyDegraded(3325L, nowMs = now + 120_000L))
        assertFalse(AutoServerSelectorProbePolicy.isRecentlyDegraded(3097L, nowMs = now + 20_000L))
        assertFalse(AutoServerSelectorProbePolicy.isRecentlyDegraded(3325L, nowMs = now + (46L * 60 * 1000)))

        AutoServerSelectorProbePolicy.clearDegradedProfile(3325L)
        assertFalse(AutoServerSelectorProbePolicy.isRecentlyDegraded(3325L, nowMs = now + 10_000L))
    }
}
