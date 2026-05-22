package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeHealthRouteTest {

    @Test
    fun rmnetDialTimeoutIsConclusiveOnPostConnect() {
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "dial rmnet_data1 (17): dial tcp 1.2.3.4:443: i/o timeout",
                whitelistOnly = true,
                phase = "post_connect",
            ),
        )
    }

    @Test
    fun contextDeadlineIsConclusiveOnPostConnect() {
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "context deadline exceeded",
                whitelistOnly = true,
                phase = "post_connect",
            ),
        )
    }

    @Test
    fun contextDeadlineIsConclusiveOnSessionPeriodic() {
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "context deadline exceeded",
                whitelistOnly = true,
                phase = "session_periodic",
            ),
        )
    }

    @Test
    fun bareRmnetDialWithoutTimeoutInconclusiveOnSessionPeriodic() {
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "dial rmnet_data1 (17): operation was canceled",
                whitelistOnly = true,
                phase = "session_periodic",
            ),
        )
    }

    @Test
    fun openNetDoesNotTreatTimeoutAsInconclusive() {
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "timeout: no recent network activity",
                whitelistOnly = false,
                phase = "post_connect",
            ),
        )
    }
}
