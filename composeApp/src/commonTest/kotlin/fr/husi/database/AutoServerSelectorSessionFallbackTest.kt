package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoServerSelectorSessionFallbackTest {

    @Test
    fun indexPointsAtConnectedProfileInQueue() {
        val queue = listOf(10L, 20L, 30L)
        assertEquals(1, AutoServerSelectorSessionFallback.fallbackIndexForConnected(queue, 20L))
    }

    @Test
    fun unknownProfileDefaultsToHead() {
        val queue = listOf(10L, 20L)
        assertEquals(0, AutoServerSelectorSessionFallback.fallbackIndexForConnected(queue, 99L))
    }

    @Test
    fun emptyQueueIndexZero() {
        assertEquals(0, AutoServerSelectorSessionFallback.fallbackIndexForConnected(emptyList(), 1L))
    }

    @Test
    fun findNextSkipsDeadJailAndCooldown() {
        val queue = listOf(1L, 2L, 3L, 4L, 5L)
        val states = mapOf(
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.DEAD),
            3L to ProxyProbeState(profileId = 3L, state = ProbeState.CEMETERY),
        )
        val walk = AutoServerSelectorSessionFallback.findNextFallbackCandidate(
            queue = queue,
            startIndex = 1,
            probeStates = states,
            inRecentFailureCooldown = { it == 4L },
        )
        requireNotNull(walk)
        assertEquals(5L, walk.nextId)
        assertEquals(4, walk.nextIndex)
        assertEquals(1, walk.skippedDead)
        assertEquals(1, walk.skippedJail)
        assertEquals(1, walk.skippedCooldown)
    }

    @Test
    fun findNextSkipsNotFreshUrlWhenRequired() {
        val queue = listOf(1L, 2L, 3L)
        val now = 50_000L
        val states = mapOf(
            2L to ProxyProbeState(
                profileId = 2L,
                state = ProbeState.CANDIDATE,
                lastTcpMs = 100,
                lastCheckedAt = now,
            ),
            3L to ProxyProbeState(
                profileId = 3L,
                state = ProbeState.ALIVE,
                lastUrlMs = 80,
                lastOkAt = now,
            ),
        )
        val walk = AutoServerSelectorSessionFallback.findNextFallbackCandidate(
            queue = queue,
            startIndex = 1,
            probeStates = states,
            inRecentFailureCooldown = { false },
            requireFreshUrlVerified = true,
            nowMs = now,
        )
        requireNotNull(walk)
        assertEquals(3L, walk.nextId)
        assertEquals(1, walk.skippedNotFresh)
    }

    @Test
    fun findNextReturnsNullWhenOnlyDeadRemain() {
        val queue = listOf(1L, 2L)
        val states = mapOf(
            1L to ProxyProbeState(profileId = 1L, state = ProbeState.DEAD),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.DEAD),
        )
        assertNull(
            AutoServerSelectorSessionFallback.findNextFallbackCandidate(
                queue = queue,
                startIndex = 0,
                probeStates = states,
                inRecentFailureCooldown = { false },
            ),
        )
    }
}
