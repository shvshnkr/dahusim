package fr.husi.simplemode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeHealthRouteTest {

    @Test
    fun wlTunnelHealthUsesBsTargetNotYaOrGstatic() {
        val urls = SimpleModeHealthRoute.healthCheckUrls(whitelistOnly = true)
        assertEquals(listOf(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM), urls)
        assertFalse(urls.any { it.contains("ya.ru") })
        assertFalse(urls.any { it.contains("gstatic") })
    }

    @Test
    fun prepareWhitelistUsesTelegramBsProbe() {
        val urls = SimpleModeHealthRoute.prepareProbeUrls(whitelistOnly = true)
        assertEquals(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM, urls.single())
    }

    @Test
    fun postConnectWhitelistUsesBsProbe() {
        val urls = SimpleModeHealthRoute.postConnectProbeUrls(whitelistOnly = true)
        assertEquals(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM, urls.single())
    }

    @Test
    fun tunnelHealthOnWlByDefault() {
        assertFalse(SimpleModeHealthRoute.skipTunnelHealthCheck(whitelistOnly = true, wlSkipTunnelHealthCheck = false))
    }

    @Test
    fun skipTunnelHealthOptInOnly() {
        assertTrue(SimpleModeHealthRoute.skipTunnelHealthCheck(whitelistOnly = true, wlSkipTunnelHealthCheck = true))
        assertFalse(SimpleModeHealthRoute.skipTunnelHealthCheck(whitelistOnly = false, wlSkipTunnelHealthCheck = true))
    }

    @Test
    fun rmnetDialTimeoutInconclusiveOnWlTunnelHealth() {
        val err = "dial rmnet_data1 (17): dial tcp 1.2.3.4:443: i/o timeout"
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                err,
                whitelistOnly = true,
                phase = "post_connect",
            ),
        )
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                err,
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
