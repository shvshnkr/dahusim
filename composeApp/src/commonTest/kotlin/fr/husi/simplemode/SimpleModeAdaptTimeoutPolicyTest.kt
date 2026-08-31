package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimpleModeAdaptTimeoutPolicyTest {

    @Test
    fun zombieReasonsDoNotReloadPreviousProfile() {
        assertEquals(
            SimpleModeAdaptTimeoutPolicy.NO_RELOAD,
            SimpleModeAdaptTimeoutPolicy.resolvePrepareTimeoutReloadId(
                reason = "session_unhealthy",
                previousProfileId = 99912L,
                selectedProxy = 99912L,
                fallbackQueueHead = null,
                networkHandoff = false,
            ),
            "session_unhealthy prepare timeout must not reload the same dead profile",
        )
        assertEquals(
            SimpleModeAdaptTimeoutPolicy.NO_RELOAD,
            SimpleModeAdaptTimeoutPolicy.resolvePrepareTimeoutReloadId(
                reason = "session_health_exhausted",
                previousProfileId = 99912L,
                selectedProxy = 99912L,
                fallbackQueueHead = null,
                networkHandoff = false,
            ),
            "session_health_exhausted prepare timeout must not reload the same dead profile",
        )
    }

    @Test
    fun reselectWinnerIsReloadedEvenOnZombieReason() {
        assertEquals(
            100140L,
            SimpleModeAdaptTimeoutPolicy.resolvePrepareTimeoutReloadId(
                reason = "session_unhealthy",
                previousProfileId = 99912L,
                selectedProxy = 100140L,
                fallbackQueueHead = null,
                networkHandoff = false,
            ),
            "prepare found a new profile — reload it even for zombie reasons",
        )
    }

    @Test
    fun handoffPrefersQueueHead() {
        assertEquals(
            100140L,
            SimpleModeAdaptTimeoutPolicy.resolvePrepareTimeoutReloadId(
                reason = "network_handoff",
                previousProfileId = 99912L,
                selectedProxy = 99912L,
                fallbackQueueHead = 100140L,
                networkHandoff = true,
            ),
        )
    }

    @Test
    fun handoffAndFlipStillAllowPreviousReload() {
        assertEquals(
            99912L,
            SimpleModeAdaptTimeoutPolicy.resolvePrepareTimeoutReloadId(
                reason = "network_handoff",
                previousProfileId = 99912L,
                selectedProxy = 99912L,
                fallbackQueueHead = null,
                networkHandoff = true,
            ),
            "handoff keeps reloading previous/queue-head — working recovery on iface change",
        )
        assertEquals(
            99912L,
            SimpleModeAdaptTimeoutPolicy.resolvePrepareTimeoutReloadId(
                reason = "reachability_flip",
                previousProfileId = 99912L,
                selectedProxy = 99912L,
                fallbackQueueHead = null,
                networkHandoff = false,
            ),
            "reachability_flip keeps reloading previous",
        )
    }

    @Test
    fun zombieReasonFlags() {
        assertTrue(SimpleModeAdaptTimeoutPolicy.isZombieLoopReason("session_unhealthy"))
        assertTrue(SimpleModeAdaptTimeoutPolicy.isZombieLoopReason("session_health_exhausted"))
        assertTrue(!SimpleModeAdaptTimeoutPolicy.isZombieLoopReason("network_handoff"))
        assertTrue(!SimpleModeAdaptTimeoutPolicy.isZombieLoopReason("reachability_flip"))
        assertTrue(!SimpleModeAdaptTimeoutPolicy.isZombieLoopReason("session_recover_fallback"))
    }
}
