package fr.husi.simplemode

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarmReserveSessionCacheTest {

    @BeforeTest
    fun reset() {
        WarmReserveSessionCache.clear()
    }

    @Test
    fun markLiveAndWarmFailedAreMutuallyExclusive() {
        WarmReserveSessionCache.markLive(10L)
        assertTrue(WarmReserveSessionCache.isSessionLive(10L))
        WarmReserveSessionCache.markWarmFailed(10L)
        assertFalse(WarmReserveSessionCache.isSessionLive(10L))
        assertTrue(WarmReserveSessionCache.isWarmFailed(10L))
        WarmReserveSessionCache.markLive(10L)
        assertFalse(WarmReserveSessionCache.isWarmFailed(10L))
    }

    @Test
    fun clearRemovesAllSessionState() {
        WarmReserveSessionCache.markLive(1L)
        WarmReserveSessionCache.markWarmFailed(2L)
        WarmReserveSessionCache.clear()
        assertEquals(0, WarmReserveSessionCache.liveCount())
        assertFalse(WarmReserveSessionCache.isSessionLive(1L))
        assertFalse(WarmReserveSessionCache.isWarmFailed(2L))
    }
}
