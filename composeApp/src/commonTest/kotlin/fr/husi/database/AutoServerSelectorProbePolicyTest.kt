package fr.husi.database

import fr.husi.fmt.trojan.TrojanBean
import fr.husi.ktx.applyDefaultValues
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoServerSelectorProbePolicyTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.autoSelectLastFullProbeAt = 0L
        DataStore.autoSelectProxyIdSetHash = 0L
        DataStore.autoSelectLastProbeWhitelistOnly = false
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
    fun openPrepareRejectWhenMessengerProbeOnAndNoUrlDelays() {
        assertTrue(
            AutoServerSelectorProbePolicy.openPrepareRejectWithoutUrl(
                wlUrlProbes = false,
                shouldQuickProbe = true,
                urlTestDelays = emptyMap(),
                openMessengerProbe = true,
            ),
        )
    }

    @Test
    fun openPrepareAcceptsWhenTelegramUrlProbeOk() {
        assertFalse(
            AutoServerSelectorProbePolicy.openPrepareRejectWithoutUrl(
                wlUrlProbes = false,
                shouldQuickProbe = true,
                urlTestDelays = mapOf(1L to 200),
                openMessengerProbe = true,
            ),
        )
    }

    @Test
    fun openPrepareNoRejectWhenMessengerProbeOff() {
        assertFalse(
            AutoServerSelectorProbePolicy.openPrepareRejectWithoutUrl(
                wlUrlProbes = false,
                shouldQuickProbe = true,
                urlTestDelays = emptyMap(),
                openMessengerProbe = false,
            ),
        )
    }

    @Test
    fun openPrepareNoRejectOnWlPool() {
        assertFalse(
            AutoServerSelectorProbePolicy.openPrepareRejectWithoutUrl(
                wlUrlProbes = true,
                shouldQuickProbe = true,
                urlTestDelays = emptyMap(),
                openMessengerProbe = true,
            ),
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
