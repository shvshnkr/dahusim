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
        AutoServerSelectorProbePolicy.TelegramTargetCircuit.resetForTest()
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
    fun wlNoUrlOkDeadEndsOnBsOpenFallback() {
        assertTrue(
            AutoServerSelectorProbePolicy.wlNoUrlOkDeadEndsPrepare(
                wlUrlProbes = false,
                activeWhitelistRestrictedNetwork = true,
                urlOk = 0,
                urlConfirmed = false,
            ),
        )
    }

    @Test
    fun wlNoUrlOkProceedsWhenCandidateUrlConfirmed() {
        assertFalse(
            AutoServerSelectorProbePolicy.wlNoUrlOkDeadEndsPrepare(
                wlUrlProbes = false,
                activeWhitelistRestrictedNetwork = true,
                urlOk = 0,
                urlConfirmed = true,
            ),
        )
    }

    @Test
    fun wlNoUrlOkProceedsWhenUrlOkPresent() {
        assertFalse(
            AutoServerSelectorProbePolicy.wlNoUrlOkDeadEndsPrepare(
                wlUrlProbes = false,
                activeWhitelistRestrictedNetwork = true,
                urlOk = 1,
                urlConfirmed = false,
            ),
        )
    }

    @Test
    fun wlNoUrlOkKeepsDegradedContinueOnOpenNetwork() {
        assertFalse(
            AutoServerSelectorProbePolicy.wlNoUrlOkDeadEndsPrepare(
                wlUrlProbes = false,
                activeWhitelistRestrictedNetwork = false,
                urlOk = 0,
                urlConfirmed = false,
            ),
        )
    }

    @Test
    fun wlNoUrlOkDeadEndsOnWlPoolRegardlessOfNetworkFlag() {
        assertTrue(
            AutoServerSelectorProbePolicy.wlNoUrlOkDeadEndsPrepare(
                wlUrlProbes = true,
                activeWhitelistRestrictedNetwork = false,
                urlOk = 0,
                urlConfirmed = false,
            ),
        )
    }

    @Test
    fun wlNoUrlOkDeadEndsEvenWhenQuickProbeSkipped() {
        // 2026-08-21 field (code 758): quick probe skipped (full probe recorded seconds
        // earlier), sequential 36-url sweep found 0 ok, yet prepare returned Success and
        // connected to a warm-state head (72460) that was dead on the BS uplink — fake
        // "Connected" until manual disconnect. The 0-url-ok sweep is fresh negative
        // evidence regardless of shouldQuickProbe, so the gate must dead-end.
        assertTrue(
            AutoServerSelectorProbePolicy.wlNoUrlOkDeadEndsPrepare(
                wlUrlProbes = true,
                activeWhitelistRestrictedNetwork = true,
                urlOk = 0,
                urlConfirmed = false,
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
        AutoServerSelectorProbePolicy.TelegramTargetCircuit.seedOpenForTest(open = true)
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
    fun openPrepareHardDeadWhenTcpAliveButNoTelegramUrlAndCircuitClosed() {
        AutoServerSelectorProbePolicy.TelegramTargetCircuit.seedOpenForTest(open = false)
        assertEquals(
            AutoServerSelectorProbePolicy.OpenPrepareDecision.HARD_DEAD,
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
    fun wlSubscriptionEarlyExitAfterFirstUrlOk() {
        assertEquals(
            1,
            AutoServerSelector.urlTestEarlyExitTarget(
                poolMode = ConnectPoolPolicy.PoolBuildMode.WL_SUBSCRIPTION,
                whitelistBuiltinOnly = true,
            ),
        )
        assertEquals(
            2,
            AutoServerSelector.urlTestEarlyExitTarget(
                poolMode = ConnectPoolPolicy.PoolBuildMode.OPEN,
                whitelistBuiltinOnly = false,
            ),
        )
        assertEquals(
            8,
            AutoServerSelector.urlTestEarlyExitTarget(
                poolMode = ConnectPoolPolicy.PoolBuildMode.MERGED,
                whitelistBuiltinOnly = true,
            ),
        )
        assertEquals(
            1,
            AutoServerSelector.urlTestEarlyExitTarget(
                poolMode = ConnectPoolPolicy.PoolBuildMode.MERGED,
                whitelistBuiltinOnly = false,
            ),
        )
    }

    @Test
    fun wlUrlWavePoolLeadsWithThisRunTcpAliveByPingBeforePriority() {
        val pool = listOf(
            plainProxy(1L),
            plainProxy(2L).also { it.ping = 90 },
            plainProxy(3L).also { it.ping = 40 },
            plainProxy(4L),
        )
        val wave = AutoServerSelector.buildWlUrlWavePool(
            connectPool = pool,
            quickProbePings = mapOf(2L to 90, 3L to 40),
            priorityFirstIds = setOf(4L),
            urlTestCap = 4,
            extraTcp = 0,
        )
        assertEquals(listOf(3L, 2L, 4L, 1L), wave.map { it.id })
    }

    @Test
    fun wlUrlWavePoolRespectsCapAndKeepsAliveInside() {
        val pool = (1L..10L).map { plainProxy(it) }
        val wave = AutoServerSelector.buildWlUrlWavePool(
            connectPool = pool,
            quickProbePings = mapOf(9L to 10, 3L to 55),
            priorityFirstIds = emptySet(),
            urlTestCap = 3,
            extraTcp = 2,
        )
        assertEquals(5, wave.size)
        assertEquals(listOf(9L, 3L), wave.take(2).map { it.id })
    }

    @Test
    fun wlUrlWavePoolEmptyWhenCapZero() {
        val pool = listOf(plainProxy(1L))
        val wave = AutoServerSelector.buildWlUrlWavePool(
            connectPool = pool,
            quickProbePings = mapOf(1L to 5),
            priorityFirstIds = emptySet(),
            urlTestCap = 0,
            extraTcp = 0,
        )
        assertTrue(wave.isEmpty())
    }

    @Test
    fun stratifiedHeuristicOrderAppliesWarmRankWithExplicitFlag() {
        // audit C.2: heuristicPreTcpOrder used to read DataStore.probe2kWarmRankingEnabled
        // inside every comparison; the explicit flag must drive the same rank behavior.
        val pool = (1L..8L).map { plainProxy(it) }
        val states = mapOf(
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE),
            5L to ProxyProbeState(profileId = 5L, state = ProbeState.DEAD),
        )
        val comparator = AutoServerSelector.heuristicPreTcpOrder(
            priorityFirstIds = emptySet(),
            probeStates = states,
            poolMode = ConnectPoolPolicy.PoolBuildMode.OPEN,
            warmRankingEnabled = true,
        )
        val order = pool.sortedWith(comparator).map { it.id }
        // ALIVE (2) → UNKNOWN (rest) → DEAD (5); ties fall back to id.
        assertEquals(listOf(2L, 1L, 3L, 4L, 6L, 7L, 8L, 5L), order)
        // Deterministic: same comparator, same input → same order.
        assertEquals(order, pool.sortedWith(comparator).map { it.id })
    }

    @Test
    fun stratifiedHeuristicOrderSkipsWarmRankWhenFlagFalse() {
        val pool = (1L..8L).map { plainProxy(it) }
        val states = mapOf(
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE),
            5L to ProxyProbeState(profileId = 5L, state = ProbeState.DEAD),
        )
        val order = pool.sortedWith(
            AutoServerSelector.heuristicPreTcpOrder(
                priorityFirstIds = emptySet(),
                probeStates = states,
                poolMode = ConnectPoolPolicy.PoolBuildMode.OPEN,
                warmRankingEnabled = false,
            ),
        ).map { it.id }
        // warmProbeStateRank returns 0 for every proxy when disabled → pure id tie-break.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), order)
    }

    @Test
    fun stratifiedHeuristicOrderMatchesLegacyDataStoreValue() {
        // Behavior-neutral refactor regression: sorting with the explicit flag equal to the
        // stored probe2kWarmRankingEnabled must reproduce the legacy (per-comparison read)
        // order — priority first, then warm rank, then id.
        DataStore.probe2kWarmRankingEnabled = true
        val legacyFlag = DataStore.probe2kWarmRankingEnabled
        val pool = (1L..6L).map { plainProxy(it) }
        val states = mapOf(3L to ProxyProbeState(profileId = 3L, state = ProbeState.ALIVE))
        val order = pool.sortedWith(
            AutoServerSelector.heuristicPreTcpOrder(
                priorityFirstIds = setOf(5L),
                probeStates = states,
                poolMode = ConnectPoolPolicy.PoolBuildMode.MERGED,
                warmRankingEnabled = legacyFlag,
            ),
        ).map { it.id }
        assertEquals(listOf(5L, 3L, 1L, 2L, 4L, 6L), order)
    }

    private fun plainProxy(id: Long) = ProxyEntity().apply {
        this.id = id
        status = ProxyEntity.STATUS_INITIAL
        groupId = 1L
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
