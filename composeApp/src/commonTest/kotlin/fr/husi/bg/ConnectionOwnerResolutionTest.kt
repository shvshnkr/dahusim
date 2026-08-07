package fr.husi.bg

import kotlin.test.Test
import kotlin.test.assertNotNull

class ConnectionOwnerResolutionTest {

    @Test
    fun `returns unknown owner for INVALID_UID without throwing`() {
        val result = buildConnectionOwner(UNKNOWN_OWNER_UID, null)
        assertNotNull(result)
    }
}
