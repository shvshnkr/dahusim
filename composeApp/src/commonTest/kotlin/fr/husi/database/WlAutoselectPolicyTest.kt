package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals

class WlAutoselectPolicyTest {

    @Test
    fun wlNodeRankPrefersBuiltinThenSubscription() {
        assertEquals(0, WlAutoselectPolicy.wlNodeRank(1L, setOf(1L), setOf(2L)))
        assertEquals(1, WlAutoselectPolicy.wlNodeRank(2L, setOf(1L), setOf(2L)))
        assertEquals(2, WlAutoselectPolicy.wlNodeRank(3L, setOf(1L), setOf(2L)))
    }

    @Test
    fun maxSessionFallbackStepsWlCap() {
        assertEquals(4, WlAutoselectPolicy.maxSessionFallbackSteps(true))
        assertEquals(32, WlAutoselectPolicy.maxSessionFallbackSteps(false))
    }
}
