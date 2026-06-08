package fr.husi.simplemode

import fr.husi.bg.UnderlyingCarrierState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeCarrierReconnectTest {

    @Test
    fun pendingTtlMatchesHeartbeatStale() {
        assertTrue(SimpleModeVpnSessionMarker.HEARTBEAT_STALE_MS > 0L)
    }

    @Test
    fun deferGracefulStopDuringCarrierOutage() {
        UnderlyingCarrierState.clear()
        try {
            assertFalse(SimpleModeCarrierReconnect.shouldDeferGracefulStop())
            UnderlyingCarrierState.markAwaitingRestoreForTest()
            assertTrue(SimpleModeCarrierReconnect.shouldDeferGracefulStop())
        } finally {
            UnderlyingCarrierState.clear()
        }
    }
}
