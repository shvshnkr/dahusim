package fr.husi.simplemode

import fr.husi.database.Probe2kDefaults
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarmReserveMaintainerPolicyTest {

    @Test
    fun canScheduleRequiresFeatureAndProfile() {
        assertTrue(WarmReserveMaintainerPolicy.canSchedule(featureEnabled = true, connectedProfileId = 1L))
        assertFalse(WarmReserveMaintainerPolicy.canSchedule(featureEnabled = false, connectedProfileId = 1L))
        assertFalse(WarmReserveMaintainerPolicy.canSchedule(featureEnabled = true, connectedProfileId = 0L))
    }

    @Test
    fun replenishDebounceSkippedForPreFallback() {
        val now = 100_000L
        assertFalse(
            WarmReserveMaintainerPolicy.shouldSkipReplenish(
                reason = "pre_fallback",
                nowMs = now,
                lastReplenishAtMs = now - 1_000L,
            ),
        )
    }

    @Test
    fun replenishDebounceWithinWindow() {
        val now = 100_000L
        assertTrue(
            WarmReserveMaintainerPolicy.shouldSkipReplenish(
                reason = "periodic",
                nowMs = now,
                lastReplenishAtMs = now - Probe2kDefaults.WARM_RESERVE_REPLENISH_DEBOUNCE_MS + 1_000L,
            ),
        )
        assertFalse(
            WarmReserveMaintainerPolicy.shouldSkipReplenish(
                reason = "periodic",
                nowMs = now,
                lastReplenishAtMs = now - Probe2kDefaults.WARM_RESERVE_REPLENISH_DEBOUNCE_MS - 1_000L,
            ),
        )
    }
}
