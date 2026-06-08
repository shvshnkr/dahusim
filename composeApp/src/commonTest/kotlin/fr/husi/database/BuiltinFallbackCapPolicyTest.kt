package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals

class BuiltinFallbackCapPolicyTest {

    @Test
    fun capRankedIdsDemotesExcessBuiltinToTail() {
        val ranked = listOf(1L, 2L, 1L, 3L, 1L)
        val capped = BuiltinFallbackCapPolicy.capRankedIds(
            rankedIds = ranked,
            isBuiltin = { it == 1L },
            maxFraction = 0.28,
        )
        assertEquals(listOf(1L, 2L, 3L, 1L, 1L), capped)
    }

    @Test
    fun appliesForUserPoolModeExclusiveIsFalse() {
        assertEquals(false, BuiltinFallbackCapPolicy.appliesForUserPoolMode(UserPoolMode.EXCLUSIVE))
    }
}
