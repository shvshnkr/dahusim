package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProbePoolEligibilityTest {

    @Test
    fun cemeteryNotSelectableEvenWhenDue() {
        val due = cemeteryState(nextProbeAt = 0L)
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
    fun builtinUsesSameCemeteryRules() {
        val builtinBuried = cemeteryState(
            profileId = 42L,
            sourcePriority = ProbeSourcePriority.BUILTIN,
        )
        assertFalse(ProbePoolEligibility.isSelectableForConnect(builtinBuried))
    }

    @Test
    fun filterSelectableRemovesCemeteryOnly() {
        val proxies = listOf(proxy(1L), proxy(2L), proxy(3L))
        val states = mapOf(
            1L to cemeteryState(profileId = 1L),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.DEAD),
            3L to ProxyProbeState(profileId = 3L, state = ProbeState.ALIVE),
        )
        val filtered = ProbePoolEligibility.filterSelectable(proxies, states)
        assertEquals(listOf(2L, 3L), filtered.map { it.id })
    }

    private fun cemeteryState(
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
