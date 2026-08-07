package fr.husi.database

import fr.husi.fmt.trojan.TrojanBean
import fr.husi.ktx.applyDefaultValues
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoServerSelectorProbePolicyTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.autoSelectLastFullProbeAt = 0L
        DataStore.autoSelectProxyIdSetHash = 0L
        DataStore.autoSelectLastProbeWhitelistOnly = false
        DataStore.autoSelectLastHandoffPreserveOkAt = 0L
    }

    @Test
    fun wlPrepareUrlConfirmationFromLiveUrlProbe() {
        assertTrue(
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                profileId = 42L,
                urlTestDelays = mapOf(42L to 180),
                probeStates = emptyMap(),
                lkgUrlFresh = { false },
            ),
        )
    }

    @Test
    fun wlPrepareUrlConfirmationFromFreshProbeState() {
        val now = System.currentTimeMillis()
        val state = ProxyProbeState(
            profileId = 7L,
            lastUrlMs = 220,
            lastOkAt = now - 1_000L,
        )
        assertTrue(
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                profileId = 7L,
                urlTestDelays = emptyMap(),
                probeStates = mapOf(7L to state),
                lkgUrlFresh = { false },
            ),
        )
    }

    @Test
    fun wlPrepareRejectsTcpOnlyWithoutUrl() {
        val state = ProxyProbeState(profileId = 9L, lastTcpMs = 50, lastUrlMs = -1)
        assertFalse(
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                profileId = 9L,
                urlTestDelays = emptyMap(),
                probeStates = mapOf(9L to state),
                lkgUrlFresh = { false },
            ),
        )
    }

    @Test
    fun wlPrepareRejectsStaleUrlProbeState() {
        val stale = ProxyProbeState(
            profileId = 11L,
            lastUrlMs = 300,
            lastOkAt = System.currentTimeMillis() - Probe2kDefaults.ALIVE_URL_FRESH_MS - 60_000L,
        )
        assertFalse(
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                profileId = 11L,
                urlTestDelays = emptyMap(),
                probeStates = mapOf(11L to stale),
                lkgUrlFresh = { false },
            ),
        )
    }

    @Test
    fun wlPrepareAcceptsLkgWhenProbeStateEmpty() {
        assertTrue(
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                profileId = 3L,
                urlTestDelays = emptyMap(),
                probeStates = emptyMap(),
                lkgUrlFresh = { it == 3L },
            ),
        )
    }

    @Test
    fun openPrepareHardDeadWhenNoTcpSurvivors() {
        assertEquals(
            AutoServerSelectorProbePolicy.OpenPrepareDecision.HARD_DEAD,
            AutoServerSelectorProbePolicy.decideOpenPrepare(
                wlUrlProbes = false,
                shouldQuickProbe = true,
                tcpAlive = 0,
                urlOk = 0,
                openMessengerProbe = true,
            ).decision,
        )
    }

    @Test
    fun openPrepareDegradedWhenTcpAliveButNoTelegramUrl() {
        assertEquals(
            AutoServerSelectorProbePolicy.OpenPrepareDecision.DEGRADED,
            AutoServerSelectorProbePolicy.decideOpenPrepare(
                wlUrlProbes = false,
                shouldQuickProbe = true,
                tcpAlive = 3,
                urlOk = 0,
                openMessengerProbe = true,
            ).decision,
        )
    }

    @Test
    fun openPrepareOkWhenTelegramUrlProbeOk() {
        assertEquals(
            AutoServerSelectorProbePolicy.OpenPrepareDecision.OK,
            AutoServerSelectorProbePolicy.decideOpenPrepare(
                wlUrlProbes = false,
                shouldQuickProbe = true,
                tcpAlive = 2,
                urlOk = 1,
                openMessengerProbe = true,
            ).decision,
        )
    }

    @Test
    fun openPrepareOkWhenMessengerProbeOff() {
        assertEquals(
            AutoServerSelectorProbePolicy.OpenPrepareDecision.OK,
            AutoServerSelectorProbePolicy.decideOpenPrepare(
                wlUrlProbes = false,
                shouldQuickProbe = true,
                tcpAlive = 5,
                urlOk = 0,
                openMessengerProbe = false,
            ).decision,
        )
    }

    @Test
    fun openPrepareOkOnWlPool() {
        assertEquals(
            AutoServerSelectorProbePolicy.OpenPrepareDecision.OK,
            AutoServerSelectorProbePolicy.decideOpenPrepare(
                wlUrlProbes = true,
                shouldQuickProbe = true,
                tcpAlive = 4,
                urlOk = 0,
                openMessengerProbe = true,
            ).decision,
        )
    }

    @Test
    fun useCompactReprobeWhenHashChangedWithinGraceAndWlModeStable() {
        val proxies = listOf(trojanProxy(1L), trojanProxy(2L))
        val hash = AutoServerSelectorProbePolicy.computeProxyIdSetHash(proxies)
        DataStore.autoSelectProxyIdSetHash = hash - 1L
        DataStore.autoSelectLastFullProbeAt = System.currentTimeMillis() - 60_000L
        DataStore.autoSelectLastProbeWhitelistOnly = true
        assertTrue(
            AutoServerSelectorProbePolicy.useCompactReprobeForProxySetChange(
                proxies = proxies,
                whitelistBuiltinOnly = true,
                networkHandoff = false,
            ),
        )
    }

    @Test
    fun useCompactReprobeDisabledOnNetworkHandoff() {
        val proxies = listOf(trojanProxy(1L))
        DataStore.autoSelectProxyIdSetHash = 1L
        DataStore.autoSelectLastFullProbeAt = System.currentTimeMillis() - 60_000L
        DataStore.autoSelectLastProbeWhitelistOnly = false
        assertFalse(
            AutoServerSelectorProbePolicy.useCompactReprobeForProxySetChange(
                proxies = proxies,
                whitelistBuiltinOnly = false,
                networkHandoff = true,
            ),
        )
    }

    @Test
    fun handoffPreserveFreshWithinWindow() {
        val now = System.currentTimeMillis()
        DataStore.autoSelectLastHandoffPreserveOkAt = now - 5_000L
        assertTrue(AutoServerSelectorProbePolicy.isHandoffPreserveFresh(now))
    }

    @Test
    fun handoffPreserveExpiresOutsideWindow() {
        val now = System.currentTimeMillis()
        DataStore.autoSelectLastHandoffPreserveOkAt = now - 25_000L
        assertFalse(AutoServerSelectorProbePolicy.isHandoffPreserveFresh(now))
    }

    @Test
    fun wlReprobeBypassTrueOnlyForWhitelist() {
        assertTrue(AutoServerSelector.shouldWlReprobeBypass(wlOnly = true))
        assertFalse(AutoServerSelector.shouldWlReprobeBypass(wlOnly = false))
    }

    private fun trojanProxy(id: Long) = ProxyEntity().apply {
        this.id = id
        type = ProxyEntity.TYPE_TROJAN
        trojanBean = TrojanBean().apply {
            name = "n$id"
            serverAddress = "example.com"
            serverPort = 443
        }.applyDefaultValues()
    }
}
