package fr.husi.database

import fr.husi.simplemode.WarmReserveSessionCache
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WarmReservePoolTest {

    @BeforeTest
    fun resetCache() {
        WarmReserveSessionCache.clear()
    }

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
    fun deficitCountsSessionLiveNotPrepareFresh() {
        val now = 10_000L
        val reserve = listOf(10L, 20L)
        val states = mapOf(
            10L to ProxyProbeState(
                profileId = 10L,
                state = ProbeState.ALIVE,
                lastUrlMs = 50,
                lastOkAt = now,
            ),
            20L to ProxyProbeState(
                profileId = 20L,
                state = ProbeState.ALIVE,
                lastUrlMs = 60,
                lastOkAt = now,
            ),
        )
        assertEquals(2, WarmReservePool.countFreshUrlAlive(reserve, states, now))
        assertEquals(0, WarmReservePool.countSessionLive(reserve))
        assertEquals(2, WarmReservePool.deficit(reserve, target = 2))
        WarmReserveSessionCache.markLive(10L)
        assertEquals(1, WarmReservePool.deficit(reserve, target = 2))
    }

    @Test
    fun selectReserveIdsSkipsWarmFailed() {
        val queue = listOf(1L, 2L, 3L, 4L)
        val states = mapOf(
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.CANDIDATE),
            3L to ProxyProbeState(profileId = 3L, state = ProbeState.CANDIDATE),
            4L to ProxyProbeState(profileId = 4L, state = ProbeState.CANDIDATE),
        )
        WarmReserveSessionCache.markWarmFailed(2L)
        WarmReserveSessionCache.markWarmFailed(3L)
        val reserve = WarmReservePool.selectReserveIds(
            queue = queue,
            connectedId = 1L,
            probeStates = states,
            target = 2,
        )
        assertEquals(listOf(4L), reserve)
    }

    @Test
    fun liveReserveIdsOnlySessionVerified() {
        val queue = listOf(1L, 2L, 3L, 4L)
        val states = mapOf(
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE, lastUrlMs = 10, lastOkAt = 1L),
            3L to ProxyProbeState(profileId = 3L, state = ProbeState.ALIVE, lastUrlMs = 20, lastOkAt = 1L),
        )
        WarmReserveSessionCache.markLive(2L)
        val live = WarmReservePool.liveReserveIds(queue, connectedId = 1L, probeStates = states, target = 2)
        assertEquals(listOf(2L), live)
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

    @Test
    fun verifyFailScenarioDeficitEqualsTarget() {
        val now = System.currentTimeMillis()
        val queue = listOf(1L, 10L, 20L)
        val states = mapOf(
            10L to ProxyProbeState(
                profileId = 10L,
                state = ProbeState.ALIVE,
                lastUrlMs = 50,
                lastOkAt = now,
            ),
            20L to ProxyProbeState(
                profileId = 20L,
                state = ProbeState.ALIVE,
                lastUrlMs = 60,
                lastOkAt = now,
            ),
        )
        val candidates = WarmReservePool.selectReserveIds(queue, 1L, states, target = 2)
        assertEquals(listOf(10L, 20L), candidates)
        WarmReserveSessionCache.markWarmFailed(10L)
        WarmReserveSessionCache.markWarmFailed(20L)
        val live = WarmReservePool.liveReserveIds(queue, 1L, probeStates = states, target = 2)
        assertEquals(emptyList(), live)
        assertEquals(2, WarmReservePool.deficit(live, target = 2))
    }
}
