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
    fun openPostConnectEscalatesToConfirmAfterPrimaryFailure() {
        assertTrue(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = "post_connect",
                    whitelistOnly = false,
                    primaryProbeFailed = true,
                    lastProbeError = "Head \"https://web.telegram.org\": EOF",
                ),
            ),
        )
    }

    @Test
    fun openSessionPeriodicStillDoesNotEscalateToConfirm() {
        assertFalse(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = "session_periodic",
                    whitelistOnly = false,
                    primaryProbeFailed = true,
                    lastProbeError = "Head \"https://web.telegram.org\": EOF",
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
    fun ccmniDialTimeoutInconclusiveOnWlPostConnectEvenForTelegramProbe() {
        val err = "dial ccmni1 (15): dial tcp 94.125.102.179:443: i/o timeout"
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                err,
                whitelistOnly = true,
                phase = "post_connect",
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
    }

    @Test
    fun networkChangedInconclusiveOnWlPostConnect() {
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "network changed",
                whitelistOnly = true,
                phase = "post_connect",
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
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

    @Test
    fun messengerProbeFailuresAreConclusive() {
        // When simple-mode requires the messenger probe (web.telegram.org), timeouts must
        // trigger server degradation/re-selection. Otherwise client can look "connected"
        // while Telegram traffic never comes.
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "timeout: no recent network activity",
                whitelistOnly = true,
                phase = "post_connect",
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "context deadline exceeded",
                whitelistOnly = true,
                phase = "post_connect",
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "context deadline exceeded",
                whitelistOnly = true,
                phase = "post_connect",
                probeUrl = SimpleModeMessengerProbe.DC_REQUIRED_URL,
            ),
        )
    }

    @Test
    fun telegramLookupAndDialAbortRemainConclusive() {
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "lookup gb.nodes.rocketnetwork.ru: connection refused",
                whitelistOnly = true,
                phase = "post_connect",
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "dial tun0 (41): connect: software caused connection abort",
                whitelistOnly = true,
                phase = "session_periodic",
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
    }

    @Test
    fun http429SyntheticOkAndInconclusiveOnOpenSessionPeriodic() {
        val err = "unexpected HTTP response status: 429"
        assertEquals(
            SimpleModeHealthRoute.WL_URL_PROBE_SYNTHETIC_MS,
            SimpleModeHealthRoute.wlUrlProbeTreatAsOk(err, whitelistOnly = false),
        )
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                err,
                whitelistOnly = false,
                phase = "session_periodic",
            ),
        )
    }

    @Test
    fun http429InconclusiveOnOpenPostConnect() {
        val err = "unexpected HTTP response status: 429"
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                err,
                whitelistOnly = false,
                phase = "post_connect",
            ),
        )
    }

    @Test
    fun http503SyntheticOkOnOpen() {
        assertEquals(
            SimpleModeHealthRoute.WL_URL_PROBE_SYNTHETIC_MS,
            SimpleModeHealthRoute.wlUrlProbeTreatAsOk(
                "unexpected HTTP response status: 503",
                whitelistOnly = false,
            ),
        )
    }

    @Test
    fun classifyTunnelProbeMapsThreeStates() {
        val real = SimpleModeHealthRoute.classifyTunnelProbe(95, wasSyntheticSuccess = false)
        assertTrue(real is SimpleModeHealthRoute.TunnelHealthOutcome.RealSuccess)
        assertTrue(real.recordUrlVerified)

        val synthetic = SimpleModeHealthRoute.classifyTunnelProbe(
            SimpleModeHealthRoute.WL_URL_PROBE_SYNTHETIC_MS,
            wasSyntheticSuccess = true,
            lastError = "HTTP 405",
        )
        assertTrue(synthetic is SimpleModeHealthRoute.TunnelHealthOutcome.InconclusiveSynthetic)
        assertFalse(synthetic.recordUrlVerified)

        val hard = SimpleModeHealthRoute.classifyTunnelProbe(0, wasSyntheticSuccess = false, lastError = "refused")
        assertTrue(hard is SimpleModeHealthRoute.TunnelHealthOutcome.HardFail)
        assertFalse(hard.isProbeOk)
    }

    @Test
    fun postConnectRecordUrlVerifiedFalseOnSyntheticSuccess() {
        assertFalse(
            SimpleModeHealthRoute.postConnectRecordUrlVerified(
                tunnelLatencyMs = SimpleModeHealthRoute.WL_URL_PROBE_SYNTHETIC_MS,
                wasSyntheticSuccess = true,
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.postConnectRecordUrlVerified(
                tunnelLatencyMs = 1,
                wasSyntheticSuccess = true,
            ),
        )
    }

    @Test
    fun postConnectRecordUrlVerifiedTrueOnRealUrlTestSuccess() {
        assertTrue(
            SimpleModeHealthRoute.postConnectRecordUrlVerified(
                tunnelLatencyMs = 120,
                wasSyntheticSuccess = false,
            ),
        )
    }

    @Test
    fun postConnectRecordUrlVerifiedFalseWhenProbeFailed() {
        assertFalse(
            SimpleModeHealthRoute.postConnectRecordUrlVerified(
                tunnelLatencyMs = 0,
                wasSyntheticSuccess = false,
            ),
        )
    }

    @Test
    fun proxyAuthFailed502IsNotSynthetic() {
        val err = "connection: open connection to 1.2.3.4:443: authentication failed, status code: 502"
        assertEquals(
            null,
            SimpleModeHealthRoute.wlUrlProbeTreatAsOk(
                error = err,
                whitelistOnly = false,
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.isHttpRateLimitOrTransientResponse(err),
        )
    }

    @Test
    fun telegramProbeNeverSyntheticEvenOn503() {
        assertEquals(
            null,
            SimpleModeHealthRoute.wlUrlProbeTreatAsOk(
                error = "unexpected HTTP response status: 503",
                whitelistOnly = false,
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
    }

    @Test
    fun stallProbeErrorInconclusiveOnlyOnPostConnectPhase() {
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                SimpleModeSessionHealthPolicy.STALL_PROBE_ERROR,
                whitelistOnly = false,
                phase = "post_connect",
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                SimpleModeSessionHealthPolicy.STALL_PROBE_ERROR,
                whitelistOnly = false,
                phase = "session_periodic",
            ),
        )
    }

    @Test
    fun openRealFailuresNotSyntheticOrInconclusive() {
        assertEquals(
            null,
            SimpleModeHealthRoute.wlUrlProbeTreatAsOk("connection refused", whitelistOnly = false),
        )
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "context deadline exceeded",
                whitelistOnly = false,
                phase = "session_periodic",
            ),
        )
    }

    @Test
    fun openPostConnectTelegramBootstrapFailureIsInconclusive() {
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "timeout: no recent network activity",
                whitelistOnly = false,
                phase = "post_connect",
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "connection refused",
                whitelistOnly = false,
                phase = "post_connect",
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
    }

    @Test
    fun openPostConnectHasTwoAttempts() {
        assertEquals(2, SimpleModeHealthRoute.postConnectMaxAttempts(whitelistOnly = false))
    }

    @Test
    fun postConnectHardFailUsesSingleAttempt() {
        assertEquals(
            1,
            SimpleModeHealthRoute.postConnectMaxAttempts(
                whitelistOnly = false,
                lastError = "connection refused",
            ),
        )
        assertTrue(SimpleModeHealthRoute.isPostConnectHardFail("x509: certificate signed by unknown authority"))
    }

    @Test
    fun softRecoveryEligibleMatrix() {
        assertTrue(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                "probe_watchdog_timeout",
                whitelistOnly = false,
            ),
        )
        assertTrue(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                "network changed",
                whitelistOnly = true,
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                "connection refused",
                whitelistOnly = false,
            ),
        )
        assertFalse(
            SimpleModeHealthRoute.isSoftRecoveryEligible(
                "connection: authentication failed",
                whitelistOnly = true,
            ),
        )
    }

    @Test
    fun wl405RegressionUnchanged() {
        assertEquals(
            SimpleModeHealthRoute.WL_URL_PROBE_SYNTHETIC_MS,
            SimpleModeHealthRoute.wlUrlProbeTreatAsOk("HTTP 405 method not allowed", whitelistOnly = true),
        )
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(
                "method not allowed",
                whitelistOnly = true,
                phase = "post_connect",
            ),
        )
    }

    @Test
    fun messengerDnsErrorConclusiveWithTelegramProbeUrl() {
        assertEquals(
            null,
            SimpleModeHealthRoute.probeFailureSkipReason(
                error = "lookup gb.nodes.rocketnetwork.ru: connection refused",
                whitelistOnly = true,
                probeUrl = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM,
            ),
        )
    }

    @Test
    fun wlTunnelBootstrapStillInconclusiveWithoutProbeUrl() {
        assertEquals(
            "wl_tunnel_bootstrap",
            SimpleModeHealthRoute.probeFailureSkipReason(
                error = "dial ccmni1 (15): dial tcp 94.125.102.179:443: i/o timeout",
                whitelistOnly = true,
            ),
        )
    }

    @Test
    fun underlyingProxyDialSyntheticGateIsPostConnectOnly() {
        assertTrue(SimpleModeHealthRoute.allowsUnderlyingProxyDialSynthetic(true, "post_connect"))
        assertFalse(SimpleModeHealthRoute.allowsUnderlyingProxyDialSynthetic(true, "session_periodic"))
        assertFalse(
            SimpleModeHealthRoute.allowsUnderlyingProxyDialSynthetic(
                true,
                SimpleModeTunnelSoftRecoveryPolicy.SOFT_REPROBE_PHASE,
            ),
        )
        assertFalse(SimpleModeHealthRoute.allowsUnderlyingProxyDialSynthetic(true, ""))
        assertFalse(SimpleModeHealthRoute.allowsUnderlyingProxyDialSynthetic(false, "post_connect"))
    }

    @Test
    fun wlSessionPeriodicInconclusiveDoesNotEscalateToConfirm() {
        val err = "dial ccmni2 (16): dial tcp 45.154.96.35:443: i/o timeout"
        assertFalse(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = "session_periodic",
                    whitelistOnly = true,
                    primaryProbeFailed = true,
                    lastProbeError = err,
                ),
            ),
            "WL periodic dial-timeout is honest — skip confirm-tier probes for quick recovery",
        )
        assertFalse(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = "session_soft_reprobe",
                    whitelistOnly = true,
                    primaryProbeFailed = true,
                    lastProbeError = err,
                ),
            ),
        )
    }

    @Test
    fun wlPostConnectInconclusiveStillEscalatesToConfirm() {
        val err = "dial ccmni1 (15): dial tcp 94.125.102.179:443: i/o timeout"
        assertTrue(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = "post_connect",
                    whitelistOnly = true,
                    primaryProbeFailed = true,
                    lastProbeError = err,
                ),
            ),
        )
    }
}
