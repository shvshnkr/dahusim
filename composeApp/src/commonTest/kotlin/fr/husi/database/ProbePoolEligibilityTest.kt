package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProbePoolEligibilityTest {

    @Test
    fun jailedNotSelectableEvenWhenDue() {
        val due = jailedState(nextProbeAt = 0L)
        assertFalse(ProbePoolEligibility.isSelectableForConnect(due))
    }

    @Test
    fun revivedCandidateIsSelectable() {
        assertTrue(
            ProbePoolEligibility.isSelectableForConnect(
                ProxyProbeState(profileId = 1L, state = ProbeState.CANDIDATE),
            ),
        )
    }

    @Test
    fun builtinUsesSameJailRules() {
        val builtinJailed = jailedState(
            profileId = 42L,
            sourcePriority = ProbeSourcePriority.BUILTIN,
        )
        assertFalse(ProbePoolEligibility.isSelectableForConnect(builtinJailed))
    }

    @Test
    fun filterSelectableRemovesJailOnly() {
        val proxies = listOf(proxy(1L), proxy(2L), proxy(3L))
        val states = mapOf(
            1L to jailedState(profileId = 1L),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.DEAD),
            3L to ProxyProbeState(profileId = 3L, state = ProbeState.ALIVE),
        )
        val filtered = ProbePoolEligibility.filterSelectable(proxies, states)
        assertEquals(listOf(2L, 3L), filtered.map { it.id })
    }

    @Test
    fun deadNotViableForFallbackWalk() {
        val dead = ProxyProbeState(profileId = 2L, state = ProbeState.DEAD)
        assertFalse(ProbePoolEligibility.isViableFallbackTarget(dead, inRecentFailureCooldown = false))
        assertFalse(ProbePoolEligibility.isViableFallbackTarget(dead, inRecentFailureCooldown = true))
    }

    @Test
    fun suspectRemainsViableForFallback() {
        val suspect = ProxyProbeState(profileId = 3L, state = ProbeState.SUSPECT)
        assertTrue(ProbePoolEligibility.isViableFallbackTarget(suspect, inRecentFailureCooldown = false))
    }

    @Test
    fun orderFallbackQueueMovesDeadToTail() {
        val ranked = listOf(10L, 20L, 30L, 40L)
        val states = mapOf(
            20L to ProxyProbeState(profileId = 20L, state = ProbeState.DEAD),
            40L to ProxyProbeState(profileId = 40L, state = ProbeState.DEAD),
        )
        assertEquals(listOf(10L, 30L, 20L, 40L), ProbePoolEligibility.orderFallbackQueue(ranked, states))
    }

    @Test
    fun firstViableSkipsDeadAndCooldown() {
        val ranked = listOf(1L, 2L, 3L)
        val states = mapOf(
            1L to ProxyProbeState(profileId = 1L, state = ProbeState.DEAD),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE),
        )
        assertEquals(
            2L,
            ProbePoolEligibility.firstViableInQueue(ranked, states) { it == 3L },
        )
    }

    private fun jailedState(
        profileId: Long = 1L,
        sourcePriority: Int = ProbeSourcePriority.SUBSCRIPTION,
        nextProbeAt: Long = 9_000_000_000L,
    ) = ProxyProbeState(
        profileId = profileId,
        state = ProbeState.CEMETERY,
        sourcePriority = sourcePriority,
        nextProbeAt = nextProbeAt,
    )

    private fun proxy(id: Long) = ProxyEntity().apply {
        this.id = id
        groupId = 1L
    }
}
