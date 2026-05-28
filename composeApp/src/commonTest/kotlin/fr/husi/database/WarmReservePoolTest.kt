package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals

class WarmReservePoolTest {

    @Test
    fun selectReserveSkipsConnectedAndTakesNextInQueue() {
        val queue = listOf(1L, 2L, 3L, 4L, 5L)
        val states = mapOf(
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE, lastUrlMs = 100, lastOkAt = 1L),
            3L to ProxyProbeState(profileId = 3L, state = ProbeState.CANDIDATE),
        )
        val reserve = WarmReservePool.selectReserveIds(
            queue,
            connectedId = 1L,
            probeStates = states,
            target = 2,
        )
        assertEquals(listOf(2L, 3L), reserve)
    }

    @Test
    fun deficitCountsOnlyFreshUrlVerified() {
        val now = 10_000L
        val reserve = listOf(10L, 20L)
        val states = mapOf(
            10L to ProxyProbeState(
                profileId = 10L,
                state = ProbeState.ALIVE,
                lastUrlMs = 50,
                lastOkAt = now,
            ),
            20L to ProxyProbeState(profileId = 20L, state = ProbeState.CANDIDATE, lastTcpMs = 40, lastCheckedAt = now),
        )
        assertEquals(1, WarmReservePool.countFreshUrlAlive(reserve, states, now))
    }

    @Test
    fun replenishSkipsReserveSetAndConnected() {
        val queue = listOf(1L, 2L, 3L, 4L)
        val states = emptyMap<Long, ProxyProbeState>()
        val candidates = WarmReservePool.replenishCandidates(
            queue = queue,
            connectedId = 1L,
            reserveIds = listOf(2L, 3L),
            probeStates = states,
            limit = 2,
        )
        assertEquals(listOf(4L), candidates)
    }

    @Test
    fun replenishEmptyWhenQueueHasSingleId() {
        val candidates = WarmReservePool.replenishCandidates(
            queue = listOf(1L),
            connectedId = 1L,
            reserveIds = emptyList(),
            probeStates = emptyMap(),
            limit = 2,
        )
        assertEquals(emptyList(), candidates)
    }
}
