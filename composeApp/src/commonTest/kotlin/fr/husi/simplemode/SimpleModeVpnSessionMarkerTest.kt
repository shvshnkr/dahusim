package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertEquals

class SimpleModeVpnSessionMarkerTest {

    @Test
    fun noMarkerWhenSessionNotExpected() {
        assertEquals(
            SimpleModeVpnSessionMarker.UncleanStopEvaluation.None,
            SimpleModeVpnSessionMarker.evaluateUncleanStopState(
                sessionExpected = false,
                lastHeartbeatMs = 5_000L,
                nowMs = 10_000L,
            ),
        )
    }

    @Test
    fun showNoticeWhenExpectedWithRecentHeartbeat() {
        val now = 1_000_000L
        assertEquals(
            SimpleModeVpnSessionMarker.UncleanStopEvaluation.ShowNotice,
            SimpleModeVpnSessionMarker.evaluateUncleanStopState(
                sessionExpected = true,
                lastHeartbeatMs = now - 60_000L,
                nowMs = now,
            ),
        )
    }

    @Test
    fun showNoticeWhenExpectedWithoutHeartbeat() {
        assertEquals(
            SimpleModeVpnSessionMarker.UncleanStopEvaluation.ShowNotice,
            SimpleModeVpnSessionMarker.evaluateUncleanStopState(
                sessionExpected = true,
                lastHeartbeatMs = 0L,
                nowMs = 10_000L,
            ),
        )
    }

    @Test
    fun clearStaleWhenHeartbeatTooOld() {
        val now = 100_000_000L
        val stale = now - SimpleModeVpnSessionMarker.HEARTBEAT_STALE_MS - 1L
        assertEquals(
            SimpleModeVpnSessionMarker.UncleanStopEvaluation.ClearStale,
            SimpleModeVpnSessionMarker.evaluateUncleanStopState(
                sessionExpected = true,
                lastHeartbeatMs = stale,
                nowMs = now,
            ),
        )
    }
}
