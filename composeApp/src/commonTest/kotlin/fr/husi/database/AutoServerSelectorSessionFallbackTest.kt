package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoServerSelectorSessionFallbackTest {

    @Test
    fun indexPointsAtConnectedProfileInQueue() {
        val queue = listOf(10L, 20L, 30L)
        assertEquals(1, AutoServerSelectorSessionFallback.fallbackIndexForConnected(queue, 20L))
    }

    @Test
    fun unknownProfileDefaultsToHead() {
        val queue = listOf(10L, 20L)
        assertEquals(0, AutoServerSelectorSessionFallback.fallbackIndexForConnected(queue, 99L))
    }

    @Test
    fun emptyQueueIndexZero() {
        assertEquals(0, AutoServerSelectorSessionFallback.fallbackIndexForConnected(emptyList(), 1L))
    }
}
