package fr.husi.simplemode

import fr.husi.database.DataStore
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeHealthRouteTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
    }

    @Test
    fun wlPrimaryProbeIsTelegramOnly() {
        val urls = SimpleModeHealthRoute.probeUrlPlan(
            phase = "prepare",
            whitelistOnly = true,
            tier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
        )
        assertEquals(listOf(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM), urls)
        assertFalse(urls.any { it.contains("ya.ru") })
        assertFalse(urls.any { it.contains("gstatic") })
    }

    @Test
    fun wlConfirmProbeUsesThreeBsHosts() {
        val urls = SimpleModeHealthRoute.probeUrlPlan(
            phase = "prepare",
            whitelistOnly = true,
            tier = SimpleModeHealthRoute.ProbeTier.CONFIRM,
        )
        assertEquals(3, urls.size)
        assertEquals(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM, urls.first())
        assertTrue(urls.any { it.contains("instagram.com") })
        assertTrue(urls.any { it.contains("facebook.com") })
        assertFalse(urls.any { it.contains("gstatic") })
        assertFalse(urls.any { it.contains("ya.ru") })
    }

    @Test
    fun prepareWhitelistDelegatesToPrimaryTier() {
        val urls = SimpleModeHealthRoute.prepareProbeUrls(whitelistOnly = true)
        assertEquals(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM, urls.single())
    }

    @Test
    fun postConnectWhitelistDelegatesToPrimaryTier() {
        val urls = SimpleModeHealthRoute.postConnectProbeUrls(whitelistOnly = true)
        assertEquals(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM, urls.single())
    }

    @Test
    fun openPrimaryTelegramOnlyWhenMessengerProbeOn() {
        DataStore.simpleModeTelegramProbe = true
        val urls = SimpleModeHealthRoute.probeUrlPlan(
            phase = "prepare",
            whitelistOnly = false,
            tier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
        )
        assertEquals(listOf(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM), urls)
        assertFalse(urls.any { it.contains("gstatic") })
    }

    @Test
    fun openPrimaryGstaticWhenMessengerProbeOff() {
        DataStore.simpleModeTelegramProbe = false
        val urls = SimpleModeHealthRoute.probeUrlPlan(
            phase = "prepare",
            whitelistOnly = false,
            tier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
        )
        assertTrue(urls.any { it.contains("gstatic") })
        assertFalse(urls.any { it.contains("telegram.org") })
    }

    @Test
    fun openConfirmTelegramFirstWhenMessengerProbeOn() {
        DataStore.simpleModeTelegramProbe = true
        val urls = SimpleModeHealthRoute.probeUrlPlan(
            phase = "prepare",
            whitelistOnly = false,
            tier = SimpleModeHealthRoute.ProbeTier.CONFIRM,
        )
        assertEquals(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM, urls.first())
        assertTrue(urls.any { it.contains("gstatic") })
        assertTrue(urls.any { it.contains("cloudflare.com") })
        assertFalse(urls.any { it.contains("instagram.com") })
    }

    @Test
    fun openConfirmAddsCloudflareNotInstagramWhenMessengerProbeOff() {
        DataStore.simpleModeTelegramProbe = false
        val urls = SimpleModeHealthRoute.probeUrlPlan(
            phase = "prepare",
            whitelistOnly = false,
            tier = SimpleModeHealthRoute.ProbeTier.CONFIRM,
        )
        assertTrue(urls.any { it.contains("gstatic") })
        assertTrue(urls.any { it.contains("cloudflare.com") })
        assertFalse(urls.any { it.contains("instagram.com") })
    }

    @Test
    fun messengerProbeRequiredAlwaysOnWl() {
        DataStore.simpleModeTelegramProbe = false
        assertTrue(SimpleModeHealthRoute.messengerProbeRequired(whitelistOnly = true))
    }

    @Test
    fun postConnectOpenUsesTelegramWhenMessengerProbeOn() {
        DataStore.simpleModeTelegramProbe = true
        val urls = SimpleModeHealthRoute.postConnectProbeUrls(whitelistOnly = false)
        assertEquals(listOf(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM), urls)
    }

    @Test
    fun sessionHealthOpenUsesTelegramWhenMessengerProbeOn() {
        DataStore.simpleModeTelegramProbe = true
        val urls = SimpleModeHealthRoute.healthCheckUrls(whitelistOnly = false)
        assertEquals(listOf(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM), urls)
    }

    @Test
    fun prepareEscalationWhenUrlDeadButTcpAlive() {
        assertTrue(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = "prepare",
                    urlOk = 0,
                    tcpAlive = 3,
                    whitelistOnly = true,
                ),
            ),
        )
    }

    @Test
    fun prepareEscalationOnWlTieBreak() {
        assertTrue(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = "prepare",
                    urlOk = 2,
                    tcpAlive = 5,
                    topDelays = listOf(1L to 100, 2L to 150),
                    whitelistOnly = true,
                ),
            ),
        )
    }

    @Test
    fun prepareNoEscalationWhenScoresFarApart() {
        assertFalse(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = "prepare",
                    urlOk = 2,
                    tcpAlive = 5,
                    topDelays = listOf(1L to 100, 2L to 300),
                    whitelistOnly = true,
                ),
            ),
        )
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
    fun methodNotAllowedInconclusiveOnWlPostConnect() {
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "method not allowed",
                whitelistOnly = true,
                phase = "post_connect",
            ),
        )
    }

    @Test
    fun wlUrlProbeTreats405AsSyntheticOk() {
        assertEquals(
            SimpleModeHealthRoute.WL_URL_PROBE_SYNTHETIC_MS,
            SimpleModeHealthRoute.wlUrlProbeTreatAsOk("HTTP 405 method not allowed", whitelistOnly = true),
        )
        assertEquals(null, SimpleModeHealthRoute.wlUrlProbeTreatAsOk("connection refused", whitelistOnly = true))
        assertEquals(null, SimpleModeHealthRoute.wlUrlProbeTreatAsOk("method not allowed", whitelistOnly = false))
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
