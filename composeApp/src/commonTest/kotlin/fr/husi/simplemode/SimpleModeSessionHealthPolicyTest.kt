package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimpleModeSessionHealthPolicyTest {

    @Test
    fun openFirstFailUsesAcceleratedFollowUp() {
        val delay = SimpleModeSessionHealthPolicy.nextCheckDelayMs(
            consecutiveFails = 1,
            whitelistRestricted = false,
        )
        assertTrue(delay < SimpleModeSessionHealthPolicy.CHECK_INTERVAL_MS)
        assertEquals(SimpleModeSessionHealthPolicy.FIRST_FAIL_FOLLOW_UP_MS, delay)
    }

    @Test
    fun openHealthyOrSecondFailUsesFullInterval() {
        assertEquals(
            SimpleModeSessionHealthPolicy.CHECK_INTERVAL_MS,
            SimpleModeSessionHealthPolicy.nextCheckDelayMs(0, false),
        )
        assertEquals(
            SimpleModeSessionHealthPolicy.CHECK_INTERVAL_MS,
            SimpleModeSessionHealthPolicy.nextCheckDelayMs(2, false),
        )
    }

    @Test
    fun wlFirstFailUsesFullInterval() {
        assertEquals(
            SimpleModeSessionHealthPolicy.CHECK_INTERVAL_MS,
            SimpleModeSessionHealthPolicy.nextCheckDelayMs(1, true),
        )
    }
}
