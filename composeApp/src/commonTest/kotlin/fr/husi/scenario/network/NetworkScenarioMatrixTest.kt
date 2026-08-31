package fr.husi.scenario.network

import fr.husi.bg.UnderlyingNetworkHandoffPolicy
import fr.husi.database.AutoServerSelectorProbePolicy
import fr.husi.database.ConnectPoolPolicy
import fr.husi.database.DataStore
import fr.husi.database.ProfileManager
import fr.husi.database.RuleEntity
import fr.husi.fmt.buildConfig
import fr.husi.fmt.trojan.TrojanBean
import fr.husi.group.SubscriptionHttpFetch
import fr.husi.group.WhitelistSubscriptionFetch
import fr.husi.ktx.applyDefaultValues
import fr.husi.database.ProxyEntity
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.repository.resolveRepository
import fr.husi.simplemode.SimpleModeHealthRoute
import fr.husi.simplemode.SimpleModeNetworkState
import fr.husi.simplemode.SimpleModeTunnelSoftRecoveryPolicy
import fr.husi.simplemode.probeSimpleModeNetwork
import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetworkScenarioMatrixTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.autoSelectLastFullProbeAt = System.currentTimeMillis() - 30_000L
        DataStore.autoSelectLastProbeWhitelistOnly = false
        DataStore.autoSelectProxyIdSetHash = 1L
    }

    @AfterTest
    fun tearDownScenario() {
        NetworkScenarioHarness.clear()
    }

    @Test
    fun uplinkModeMatrix() = runBlocking {
        val rows = listOf(
            NetworkScenarioRow(
                id = "open_full",
                state = SimpleModeNetworkState(
                    hasAnyInternet = true,
                    googleOk = true,
                    whitelistOnly = false,
                ),
            ),
            NetworkScenarioRow(
                id = "wl_bs",
                state = SimpleModeNetworkState(
                    hasAnyInternet = true,
                    googleOk = false,
                    whitelistOnly = true,
                ),
            ),
            NetworkScenarioRow(
                id = "no_inet",
                state = SimpleModeNetworkState(
                    hasAnyInternet = false,
                    googleOk = false,
                    whitelistOnly = false,
                ),
            ),
            NetworkScenarioRow(
                id = "wl_partial",
                state = SimpleModeNetworkState(
                    hasAnyInternet = true,
                    googleOk = false,
                    whitelistOnly = true,
                ),
            ),
        )
        for (row in rows) {
            NetworkScenarioHarness.install(row.state)
            val probed = probeSimpleModeNetwork()
            assertEquals(row.state, probed, "scenario=${row.id}")
            when (row.id) {
                "open_full" -> assertOpenHealthRoute()
                "wl_bs" -> assertWlHealthRoute()
                "no_inet" -> assertFalse(probed.hasAnyInternet, "scenario=${row.id}")
                "wl_partial" -> {
                    assertWlHealthRoute()
                    assertFalse(probed.googleOk, "scenario=${row.id}")
                }
            }
            NetworkScenarioHarness.clear()
        }
    }

    @Test
    fun handoffAndFlapMatrix() {
        assertEquals(
            UnderlyingNetworkHandoffPolicy.REASON_CROSS_INTERFACE,
            UnderlyingNetworkHandoffPolicy.evaluate(
                handoffSnapshot(
                    previousInterfaceForHandoff = "wlan0",
                    interfaceName = "rmnet_data2",
                ),
            ),
            "handoff_wifi_lte",
        )
        assertEquals(
            UnderlyingNetworkHandoffPolicy.REASON_CROSS_INTERFACE,
            UnderlyingNetworkHandoffPolicy.evaluate(
                handoffSnapshot(
                    previousInterfaceForHandoff = "wlan0",
                    interfaceName = "rmnet_data1",
                    underlyingCarrierLostWhileConnected = true,
                ),
            ),
            "handoff_wifi_lte_watchdog_snapshot",
        )
        assertEquals(
            UnderlyingNetworkHandoffPolicy.REASON_CARRIER_RESTORE,
            UnderlyingNetworkHandoffPolicy.evaluate(
                handoffSnapshot(
                    previousInterfaceForHandoff = "wlan0",
                    interfaceName = "wlan0",
                    lastInterfaceName = null,
                    lastInterfaceIndex = -1,
                    underlyingCarrierLostWhileConnected = true,
                ),
            ),
            "handoff_carrier_restore",
        )
        assertEquals(
            UnderlyingNetworkHandoffPolicy.REASON_LINK_REBOUND,
            UnderlyingNetworkHandoffPolicy.evaluate(
                handoffSnapshot(
                    interfaceName = "wlan0",
                    interfaceIndex = 27,
                    lastInterfaceName = "wlan0",
                    lastInterfaceIndex = 26,
                ),
            ),
            "handoff_link_rebound",
        )
        assertNull(
            UnderlyingNetworkHandoffPolicy.evaluate(
                handoffSnapshot(
                    previousInterfaceForHandoff = "wlan0",
                    interfaceName = "tun0",
                    underlyingCarrierLostWhileConnected = true,
                ),
            ),
            "handoff_wifi_tun_during_loss",
        )

        val flapEvents = listOf("wifi_to_lte", "lte_to_wifi", "wifi_to_lte", "carrier_restore")
        val proxies = listOf(proxy(1L), proxy(3L))
        for (event in flapEvents) {
            val compact = AutoServerSelectorProbePolicy.useCompactReprobeForProxySetChange(
                proxies = proxies,
                whitelistBuiltinOnly = true,
                networkHandoff = true,
            )
            assertFalse(compact, "flap_in_scan event=$event")
        }
    }

    @Test
    fun wlFlipAndReconnectMatrix() {
        DataStore.autoSelectLastProbeWhitelistOnly = true
        DataStore.autoSelectLastFullProbeAt = System.currentTimeMillis()
        val proxies = listOf(proxy(1L), proxy(2L))
        val reasonOpen = AutoServerSelectorProbePolicy.forceFullProbeReason(
            proxies = proxies,
            whitelistBuiltinOnly = false,
            networkHandoff = false,
        )
        assertNotNull(reasonOpen?.contains("wl_to_open"), "wl_flip_open_to_wl")

        val compactHandoff = AutoServerSelectorProbePolicy.useCompactReprobeForProxySetChange(
            proxies = proxies,
            whitelistBuiltinOnly = false,
            networkHandoff = true,
        )
        assertFalse(compactHandoff, "reconnect_handoff")

        DataStore.activeWhitelistRestrictedNetwork = true
        DataStore.simpleModeAutoselectPoolMerged = true
        assertEquals(
            ConnectPoolPolicy.MAX_SESSION_FALLBACK_STEPS_OPEN,
            ConnectPoolPolicy.maxSessionFallbackSteps(whitelistRestricted = true),
            "wl_partial merged pool",
        )
    }

    @Test
    fun subscriptionWlFetchMatrix() {
        val githubLink =
            "https://raw.githubusercontent.com/shvshnkr/dahusim/main/docs/subscription-catalog.txt"
        val wlDirect = WhitelistSubscriptionFetch.resolveFetchLink(
            link = githubLink,
            whitelistRestricted = true,
            vpnConnected = false,
        )
        assertTrue(wlDirect.contains("translate.yandex"), "sub_fetch_wl_direct")
        val wlVpn = WhitelistSubscriptionFetch.shouldUseYandexMirror(
            link = githubLink,
            whitelistRestricted = true,
            vpnConnected = true,
        )
        assertFalse(wlVpn, "sub_fetch_wl_vpn")
        val request = SubscriptionHttpFetch.Request(
            canonicalLink = githubLink,
            userAgent = "husi/scenario",
            whitelistRestricted = true,
            vpnConnected = false,
            purpose = SubscriptionHttpFetch.FetchPurpose.Catalog,
        )
        assertEquals(SubscriptionHttpFetch.FetchPurpose.Catalog, request.purpose)
        assertEquals(true, request.whitelistRestricted)
    }

    @Test
    fun noInetSkipsSubscriptionRefreshBudget() {
        NetworkScenarioHarness.install(
            SimpleModeNetworkState(
                hasAnyInternet = false,
                googleOk = false,
                whitelistOnly = false,
            ),
        )
        val net = runBlocking { probeSimpleModeNetwork() }
        assertFalse(net.hasAnyInternet)
        val degraded = AutoServerSelectorProbePolicy.decideOpenPrepare(
            wlUrlProbes = false,
            shouldQuickProbe = true,
            tcpAlive = 0,
            urlOk = 0,
            openMessengerProbe = true,
        )
        assertEquals(
            AutoServerSelectorProbePolicy.OpenPrepareDecision.HARD_DEAD,
            degraded.decision,
        )
    }

    @Test
    fun rulesetPartialLocalFallbackMatrix() = runBlocking {
        val group = ProxyGroup(name = "group").applyDefaultValues()
        group.id = SagerDatabase.groupDao.createGroup(group)
        val proxy = ProxyEntity(groupId = group.id, userOrder = 1).apply {
            type = ProxyEntity.TYPE_TROJAN
            trojanBean = TrojanBean().apply {
                name = "matrix-main"
                serverAddress = "example.com"
                serverPort = 443
            }.applyDefaultValues()
        }
        proxy.id = SagerDatabase.proxyDao.addProxy(proxy)
        ProfileManager.createRule(
            RuleEntity(
                enabled = true,
                name = "RU blocked split",
                domains = "set+dns:geosite-ru-blocked",
                ip = "set+dns:geoip-ru-blocked",
                outbound = RuleEntity.OUTBOUND_DIRECT,
            ),
        )

        val geoDir = resolveRepository().externalAssetsDir.resolve("geo")
        geoDir.mkdirs()
        val localGeosite = geoDir.resolve("geosite-ru-blocked.srs")
        val missingGeoip = geoDir.resolve("geoip-ru-blocked.srs")
        localGeosite.writeText("test")
        missingGeoip.delete()

        try {
            val routeRuleSets = Json.parseToJsonElement(buildConfig(proxy).config)
                .jsonObject["route"]!!
                .jsonObject["rule_set"]!!
                .jsonArray
                .map { it.jsonObject }

            val geositeRuleSet = routeRuleSets.firstOrNull {
                it["tag"]?.jsonPrimitive?.content == "geosite-ru-blocked"
            }
            assertNotNull(geositeRuleSet, "ruleset_partial_local_fallback geosite")
            assertTrue(
                geositeRuleSet["path"]?.jsonPrimitive?.content?.endsWith("/geo/geosite-ru-blocked.srs") == true,
                "ruleset_partial_local_fallback geosite local path",
            )
            assertNull(geositeRuleSet["url"], "ruleset_partial_local_fallback geosite no remote url")

            val geoipRuleSet = routeRuleSets.firstOrNull {
                it["tag"]?.jsonPrimitive?.content == "geoip-ru-blocked"
            }
            assertNotNull(geoipRuleSet, "ruleset_partial_local_fallback geoip")
            assertNull(geoipRuleSet["path"], "ruleset_partial_local_fallback geoip no local path")
            assertEquals(
                "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/sing-box/rule-set-geoip/geoip-ru-blocked.srs",
                geoipRuleSet["url"]?.jsonPrimitive?.content,
                "ruleset_partial_local_fallback geoip remote fallback",
            )
        } finally {
            localGeosite.delete()
        }
    }

    @Test
    fun wlZombieDialTimeoutPhaseMatrix() {
        // Log 29.08: WL LTE, server unreachable from the uplink (field zombie session).
        val err = "dial ccmni2 (16): dial tcp 45.154.96.35:443: i/o timeout"
        // post_connect: bootstrap grace — classification inconclusive AND synthetic pass allowed.
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(err, whitelistOnly = true, phase = "post_connect"),
            "post_connect dial-timeout stays inconclusive (H10 post_connect_inconclusive_connected)",
        )
        assertTrue(
            SimpleModeHealthRoute.allowsUnderlyingProxyDialSynthetic(true, "post_connect"),
            "post_connect may convert the wave to a synthetic pass",
        )
        // session_periodic: classification stays inconclusive (soft-recovery eligibility), but the
        // wave must NOT go synthetic — honest HardFail → fail-streak → recovery.
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive(err, whitelistOnly = true, phase = "session_periodic"),
            "classification unchanged on session_periodic",
        )
        assertFalse(
            SimpleModeHealthRoute.allowsUnderlyingProxyDialSynthetic(true, "session_periodic"),
            "session_periodic dial-timeout must be a HardFail, not synthetic-ok",
        )
        assertFalse(
            SimpleModeHealthRoute.allowsUnderlyingProxyDialSynthetic(true, SimpleModeTunnelSoftRecoveryPolicy.SOFT_REPROBE_PHASE),
            "soft reprobe keeps no synthetic grace",
        )
        // open network untouched: never synthetic, never inconclusive for this error.
        assertFalse(SimpleModeHealthRoute.allowsUnderlyingProxyDialSynthetic(false, "post_connect"))
        assertFalse(
            SimpleModeHealthRoute.isProbeFailureInconclusive(err, whitelistOnly = false, phase = "session_periodic"),
        )
        // network changed stays inconclusive on session_periodic (transitional classifier).
        assertTrue(
            SimpleModeHealthRoute.isProbeFailureInconclusive("network changed", whitelistOnly = true, phase = "session_periodic"),
            "network changed remains inconclusive on session_periodic",
        )
        // No confirm-tier escalation on WL session_periodic: the uplink dial failure is honest and
        // quick — the first probe already proves the tunnel is dead from this uplink.
        assertFalse(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = "session_periodic",
                    whitelistOnly = true,
                    primaryProbeFailed = true,
                    lastProbeError = err,
                ),
            ),
            "WL session_periodic inconclusive must not escalate to confirm",
        )
        assertTrue(
            SimpleModeHealthRoute.shouldEscalateToConfirm(
                SimpleModeHealthRoute.ProbeEscalationContext(
                    phase = "post_connect",
                    whitelistOnly = true,
                    primaryProbeFailed = true,
                    lastProbeError = err,
                ),
            ),
            "WL post_connect bootstrap keeps confirm-tier escalation",
        )
    }

    private fun assertOpenHealthRoute() {
        val urls = SimpleModeHealthRoute.prepareProbeUrls(whitelistOnly = false)
        assertTrue(urls.isNotEmpty())
        assertFalse(urls.any { it.contains("ya.ru") })
    }

    private fun assertWlHealthRoute() {
        val urls = SimpleModeHealthRoute.prepareProbeUrls(whitelistOnly = true)
        assertEquals(listOf(SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM), urls)
        assertFalse(urls.any { it.contains("gstatic") })
        assertFalse(urls.any { it.contains("ya.ru") })
    }

    private fun handoffSnapshot(
        vpnSessionActive: Boolean = true,
        previousInterfaceForHandoff: String? = null,
        interfaceName: String? = null,
        interfaceIndex: Int = 0,
        lastInterfaceName: String? = null,
        lastInterfaceIndex: Int = -1,
        underlyingCarrierLostWhileConnected: Boolean = false,
    ) = UnderlyingNetworkHandoffPolicy.Snapshot(
        vpnSessionActive = vpnSessionActive,
        previousInterfaceForHandoff = previousInterfaceForHandoff,
        interfaceName = interfaceName,
        interfaceIndex = interfaceIndex,
        lastInterfaceName = lastInterfaceName,
        lastInterfaceIndex = lastInterfaceIndex,
        underlyingCarrierLostWhileConnected = underlyingCarrierLostWhileConnected,
    )

    private fun proxy(id: Long) = ProxyEntity().apply {
        this.id = id
        type = ProxyEntity.TYPE_TROJAN
        trojanBean = TrojanBean().apply {
            name = "p$id"
            serverAddress = "example.com"
            serverPort = 443
        }.applyDefaultValues()
    }
}
