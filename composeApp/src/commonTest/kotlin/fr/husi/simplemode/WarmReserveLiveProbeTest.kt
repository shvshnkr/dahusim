package fr.husi.simplemode

import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WarmReserveLiveProbeTest {

  @Test
  fun concurrentHashMapRejectsNullValue() {
    val map = ConcurrentHashMap<Long, Int>()
    assertFailsWith<NullPointerException> {
      map[1L] = null as Int
    }
  }

  @Test
  fun associateWithReturnsNullForMissingProbeResult() {
    val results = ConcurrentHashMap<Long, Int>()
    results[42L] = 120
    val out = listOf(42L, 99L).associateWith { results[it] }
    assertEquals(120, out[42L])
    assertNull(out[99L])
  }
}
