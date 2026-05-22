package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeHealthRouteTest {

    @Test
    fun rmnetDialIsInconclusiveOnWhitelist() {
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "dial rmnet_data1 (17): dial tcp 1.2.3.4:443: i/o timeout",
                whitelistOnly = true,
                phase = "post_connect",
            ),
        )
    }

    @Test
    fun transportTimeoutAloneInconclusiveOnWhitelistPostConnect() {
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "timeout: no recent network activity",
                whitelistOnly = true,
                phase = "post_connect",
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
