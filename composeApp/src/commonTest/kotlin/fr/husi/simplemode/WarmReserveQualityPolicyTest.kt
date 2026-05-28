package fr.husi.simplemode

import fr.husi.database.AutoServerSelectorProbePolicy
import fr.husi.database.DataStore
import fr.husi.database.ProbeState
import fr.husi.database.ProxyProbeState
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarmReserveQualityPolicyTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
    }

    @Test
    fun qualityScorePrefersLowerLiveUrl() {
        val now = System.currentTimeMillis()
        val fast = WarmQualitySample(1L, liveUrlMs = 100, probeState = aliveState(1L, now))
        val slow = WarmQualitySample(2L, liveUrlMs = 400, probeState = aliveState(2L, now))
        assertTrue(WarmReserveQualityPolicy.qualityScore(fast) < WarmReserveQualityPolicy.qualityScore(slow))
    }

    @Test
    fun compareSwitchesWhenReserveIsFaster() {
        val now = System.currentTimeMillis()
        val connected = 1L
        val reserve = 2L
        val states = mapOf(
            connected to aliveState(connected, now, urlMs = 300),
            reserve to aliveState(reserve, now, urlMs = 120),
        )
        val decision = WarmReserveQualityPolicy.compareForManualSwitch(
            connectedId = connected,
            reserveIds = listOf(reserve),
            liveUrlMs = mapOf(connected to 300, reserve to 120),
            probeStates = states,
            nowMs = now,
        )
        assertEquals(WarmSwitchDecision.SwitchTo(reserve), decision)
    }

    @Test
    fun compareAlreadyOnBestWhenReservesAreSlower() {
        val now = System.currentTimeMillis()
        val connected = 1L
        val reserve = 2L
        val states = mapOf(
            connected to aliveState(connected, now, urlMs = 100),
            reserve to aliveState(reserve, now, urlMs = 250),
        )
        val decision = WarmReserveQualityPolicy.compareForManualSwitch(
            connectedId = connected,
            reserveIds = listOf(reserve),
            liveUrlMs = mapOf(connected to 100, reserve to 250),
            probeStates = states,
            nowMs = now,
        )
        assertEquals(WarmSwitchDecision.AlreadyOnBest, decision)
    }

    @Test
    fun compareAlreadyOnBestWithinTieEpsilon() {
        val now = System.currentTimeMillis()
        val connected = 1L
        val reserve = 2L
        val states = mapOf(
            connected to aliveState(connected, now, urlMs = 200),
            reserve to aliveState(reserve, now, urlMs = 220),
        )
        val decision = WarmReserveQualityPolicy.compareForManualSwitch(
            connectedId = connected,
            reserveIds = listOf(reserve),
            liveUrlMs = mapOf(connected to 200, reserve to 220),
            probeStates = states,
            nowMs = now,
        )
        assertEquals(WarmSwitchDecision.AlreadyOnBest, decision)
    }

    @Test
    fun compareNoReservesWhenPoolEmpty() {
        val decision = WarmReserveQualityPolicy.compareForManualSwitch(
            connectedId = 1L,
            reserveIds = emptyList(),
            liveUrlMs = emptyMap(),
            probeStates = emptyMap(),
        )
        assertEquals(WarmSwitchDecision.NoReserves, decision)
    }

    @Test
    fun degradedConnectedLosesToFasterReserve() {
        val now = System.currentTimeMillis()
        AutoServerSelectorProbePolicy.recordDegradedProfile(1L, nowMs = now)
        val connected = 1L
        val reserve = 2L
        val states = mapOf(
            connected to aliveState(connected, now, urlMs = 100),
            reserve to aliveState(reserve, now, urlMs = 800),
        )
        val decision = WarmReserveQualityPolicy.compareForManualSwitch(
            connectedId = connected,
            reserveIds = listOf(reserve),
            liveUrlMs = mapOf(connected to 100, reserve to 800),
            probeStates = states,
            nowMs = now,
        )
        assertEquals(WarmSwitchDecision.SwitchTo(reserve), decision)
        assertTrue(AutoServerSelectorProbePolicy.isRecentlyDegraded(connected, nowMs = now))
        AutoServerSelectorProbePolicy.clearDegradedProfile(connected)
    }

    private fun aliveState(
        id: Long,
        nowMs: Long,
        urlMs: Int = 100,
    ): ProxyProbeState = ProxyProbeState(
        profileId = id,
        state = ProbeState.ALIVE,
        lastUrlMs = urlMs,
        lastOkAt = nowMs,
    )
}
