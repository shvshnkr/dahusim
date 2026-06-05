package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun uiAttachReschedulesWhenSessionMissing() {
        assertTrue(SimpleModeSessionHealthPolicy.shouldRescheduleMonitoringWhenSessionMissing("ui_attach"))
        assertTrue(SimpleModeSessionHealthPolicy.shouldRescheduleMonitoringWhenSessionMissing("ui_resume"))
        assertFalse(SimpleModeSessionHealthPolicy.shouldRescheduleMonitoringWhenSessionMissing("lte_handoff"))
    }

    @Test
    fun onDemandUiUsesShorterDebounce() {
        assertEquals(
            SimpleModeSessionHealthPolicy.ON_DEMAND_UI_MIN_GAP_MS,
            SimpleModeSessionHealthPolicy.onDemandMinGapMs("ui_attach"),
        )
        assertEquals(
            SimpleModeSessionHealthPolicy.ON_DEMAND_MIN_GAP_MS,
            SimpleModeSessionHealthPolicy.onDemandMinGapMs("network_handoff"),
        )
    }

    @Test
    fun stallRecoveryExceedsCheckInterval() {
        assertTrue(
            SimpleModeSessionHealthPolicy.STALL_RECOVERY_MS >
                SimpleModeSessionHealthPolicy.CHECK_INTERVAL_MS,
        )
    }

    @Test
    fun monitoringStaleWhenNoRecentCheck() {
        val now = 100_000L
        assertTrue(
            SimpleModeSessionHealthPolicy.isMonitoringStale(
                lastCheckCompletedAt = 0L,
                nowMs = now,
            ),
        )
        assertTrue(
            SimpleModeSessionHealthPolicy.isMonitoringStale(
                lastCheckCompletedAt = now - SimpleModeSessionHealthPolicy.MONITORING_STALE_MS,
                nowMs = now,
            ),
        )
        assertFalse(
            SimpleModeSessionHealthPolicy.isMonitoringStale(
                lastCheckCompletedAt = now - SimpleModeSessionHealthPolicy.MONITORING_STALE_MS + 1L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun monitoringStaleBeforeStallRecovery() {
        assertTrue(
            SimpleModeSessionHealthPolicy.MONITORING_STALE_MS <
                SimpleModeSessionHealthPolicy.STALL_RECOVERY_MS,
        )
    }
}
