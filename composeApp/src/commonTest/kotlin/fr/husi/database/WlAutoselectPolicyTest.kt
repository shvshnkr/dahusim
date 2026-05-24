package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals

class WlAutoselectPolicyTest {

    @Test
    fun wlNodeRankPrefersSubscriptionWlMarked() {
        assertEquals(0, WlAutoselectPolicy.wlNodeRank(2L, setOf(2L)))
        assertEquals(1, WlAutoselectPolicy.wlNodeRank(3L, setOf(2L)))
    }
}
