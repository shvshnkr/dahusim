package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `builtin fallback cap limits builtin share`() {
        val ranked = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
        val builtin = setOf(1L, 2L, 3L, 4L, 5L, 6L)
        val capped = BuiltinFallbackQuota.apply(
            rankedIds = ranked,
            builtinProfileIds = builtin,
            maxFraction = 0.28,
            enabled = true,
        )
        val builtinInHead = capped.take(5).count { it in builtin }
        assertTrue(builtinInHead <= 2)
        assertEquals(ranked.size, capped.size)
    }
}
