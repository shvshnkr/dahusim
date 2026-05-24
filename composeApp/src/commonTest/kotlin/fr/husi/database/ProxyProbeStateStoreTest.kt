package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertTrue

class ProxyProbeStateStoreTest {

    @Test
    fun `fresh alive accepts recent url or tcp`() {
        val now = 1_000_000L
        val state = ProxyProbeState(
            profileId = 1L,
            state = ProbeState.ALIVE,
            lastCheckedAt = now,
            lastOkAt = now,
            lastTcpMs = 120,
            lastUrlMs = 200,
        )
        assertTrue(ProxyProbeStateStore.isFreshAlive(state, now + 1_000L))
    }

    @Test
    fun `probe state rank orders alive before dead`() {
        val alive = ProxyProbeState(profileId = 1L, state = ProbeState.ALIVE)
        val dead = ProxyProbeState(profileId = 2L, state = ProbeState.DEAD)
        assertTrue(ProxyProbeStateStore.probeStateRank(alive) < ProxyProbeStateStore.probeStateRank(dead))
    }
}
