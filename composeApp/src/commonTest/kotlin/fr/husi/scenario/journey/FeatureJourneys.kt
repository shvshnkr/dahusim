package fr.husi.scenario.journey

data class FeatureJourney(
    val id: String,
    val userPromise: String,
    val entryPoints: List<String>,
    val testClass: String,
)

object FeatureJourneys {
    val all: List<FeatureJourney> = listOf(
        FeatureJourney(
            id = "sub_add_import",
            userPromise = "Import URL → USER subscription group with proxies in DB",
            entryPoints = listOf("MainViewModel", "ImportLinkInteractor"),
            testClass = "fr.husi.scenario.journey.SubscriptionAddByImportJourneyTest",
        ),
        FeatureJourney(
            id = "sub_add_settings",
            userPromise = "Manual subscription creation → same USER ownership contract",
            entryPoints = listOf("GroupSettingsViewModel", "LibraryAddSheet"),
            testClass = "fr.husi.scenario.journey.SubscriptionAddBySettingsJourneyTest",
        ),
        FeatureJourney(
            id = "sub_survives_bootstrap",
            userPromise = "After restart/bootstrap USER ownership and link unchanged",
            entryPoints = listOf("DefaultUserBootstrap"),
            testClass = "fr.husi.scenario.journey.SubscriptionSurvivesBootstrapJourneyTest",
        ),
        FeatureJourney(
            id = "profile_import_standalone",
            userPromise = "vless/share → user BASIC group, not builtin",
            entryPoints = listOf("ImportLinkInteractor.importStandaloneProfiles"),
            testClass = "fr.husi.scenario.journey.StandaloneProfileImportJourneyTest",
        ),
        FeatureJourney(
            id = "connect_user_pool_priority",
            userPromise = "USER proxies rank above managed in PRIORITY pool mode",
            entryPoints = listOf("UserPoolMode.PRIORITY", "AutoServerSelector"),
            testClass = "fr.husi.scenario.journey.UserPoolConnectJourneyTest",
        ),
        FeatureJourney(
            id = "library_manual_flat_list",
            userPromise = "Library Manual tab lists user-owned BASIC profiles with connect, test, and status actions",
            entryPoints = listOf(
                "ManualServersViewModel",
                "ManualServersPolicy",
                "ConfigurationScreenViewModel.toggleManualServerConnection",
            ),
            testClass = "fr.husi.scenario.journey.LibraryManualFlatListJourneyTest",
        ),
        FeatureJourney(
            id = "library_filter_search_counts",
            userPromise = "Library filters subscriptions by pool role (WL/OPEN), searches by name/link, segment tabs show live counts",
            entryPoints = listOf(
                "LibraryRoleFilter",
                "GroupItemUiState.matchesRoleFilter",
                "GroupItemUiState.matchesLibraryQuery",
                "librarySegmentCounts",
            ),
            testClass = "fr.husi.scenario.journey.LibraryFilteringJourneyTest",
        ),
        FeatureJourney(
            id = "simple_scan_clears_after_prepare",
            userPromise = "Scanning N/N progress is cleared when prepare finishes — a stale 1/1 line never sticks",
            entryPoints = listOf(
                "Probe2kProgress.clearScan",
                "AutoServerSelector.prepareForConnect",
            ),
            testClass = "fr.husi.scenario.journey.SimpleScanClearJourneyTest",
        ),
        FeatureJourney(
            id = "full_mode_expert_health",
            userPromise = "Full mode with expert recover gets tunnel health watchdog and telegram-aligned dashboard test",
            entryPoints = listOf(
                "ExpertConnectRecoverPolicy",
                "SimpleModeSessionHealth",
                "SimpleModeHealthRoute.dashboardConnectionTestUrl",
            ),
            testClass = "fr.husi.scenario.journey.FullModeExpertHealthJourneyTest",
        ),
        FeatureJourney(
            id = "messenger_composite_prepare",
            userPromise = "Autoselect rejects profiles with web.telegram OK but Telegram DC IP egress dead",
            entryPoints = listOf(
                "SimpleModeMessengerProbe",
                "DirectProfileUrlProbe.messengerCompositeDelay",
                "SimpleModeTunnelHealthCheck.probeMessengerWave",
            ),
            testClass = "fr.husi.scenario.journey.MessengerCompositePrepareJourneyTest",
        ),
        FeatureJourney(
            id = "carrier_reconnect_after_outage",
            userPromise = "After carrier outage stop, pending reconnect resumes without manual Connect tap",
            entryPoints = listOf(
                "SimpleModeCarrierReconnect",
                "DefaultNetworkMonitor",
                "UiActivityTracker",
            ),
            testClass = "fr.husi.scenario.journey.CarrierReconnectAfterOutageJourneyTest",
        ),
        FeatureJourney(
            id = "simple_no_internet_blocked",
            userPromise = "Connect on a dead link shows a no-internet banner and never pretends to be preparing — the app blocks before any probe",
            entryPoints = listOf(
                "SimpleModeConnectCoordinator.runConnect",
                "SimpleModeNetworkProbeHooks",
            ),
            testClass = "fr.husi.scenario.journey.SimpleNoInternetBlockedJourneyTest",
        ),
        FeatureJourney(
            id = "simple_all_servers_dead_prompt_timeout",
            userPromise = "All-servers-dead prompt cannot hang the app: unresolved prompt resolves to wait-for-google and stops the service",
            entryPoints = listOf(
                "SimpleModeConnectCoordinator.handleAllServersDead",
                "MainViewModel.promptSimpleModeAllServersDead",
            ),
            testClass = "fr.husi.scenario.journey.SimpleAllServersDeadPromptTimeoutJourneyTest",
        ),
        FeatureJourney(
            id = "simple_all_servers_dead_banner",
            userPromise = "After the revival watch exhausts, the UI keeps a persistent \"no working servers\" banner instead of silently returning to Stopped",
            entryPoints = listOf(
                "SimpleModeConnectCoordinator.handleAllServersDead",
                "SimpleHomeScreen.onAllServersDead",
            ),
            testClass = "fr.husi.scenario.journey.SimpleAllServersDeadBannerJourneyTest",
        ),
        FeatureJourney(
            id = "wl_server_revival_watch",
            userPromise = "BS dead sweep (WL pool or open-fallback 0 url-ok) keeps watching and auto-connects when a flapping server revives — one Connect tap, not a retry loop",
            entryPoints = listOf(
                "SimpleModeConnectCoordinator.awaitWlServerRevival",
                "AutoServerSelector.prepareForConnect",
                "AutoServerSelectorProbePolicy.wlNoUrlOkDeadEndsPrepare",
            ),
            testClass = "fr.husi.scenario.journey.WlServerRevivalWatchJourneyTest",
        ),
        FeatureJourney(
            id = "simple_adapt_timeout_activity_clear",
            userPromise = "After an adapt prepare timeout without a tunnel rebuild the stale Preparing activity is cleared, so a healthy Connected session never sticks in Preparing",
            entryPoints = listOf(
                "SimpleModeVpnCoordinator.applyReselectAndRestart",
                "SimpleModeNetworkAdaptation.clearActivityAfterPrepareTimeout",
            ),
            testClass = "fr.husi.scenario.journey.SimpleAdaptTimeoutActivityClearJourneyTest",
        ),
        FeatureJourney(
            id = "simple_screen_state_tones",
            userPromise = "Simple screen keeps problem states visible: FAILED (no internet / all servers dead) instead of silent Stopped, RECOVERING pulse with the Attempt N of M fallback pill, always-shown step trail with the failing stage red",
            entryPoints = listOf(
                "SimpleHomeScreen.statusTone",
                "SimpleModeActivityText.isSimpleModeRecoveringActivity",
                "AutoServerSelectorSessionFallback.parseQueue",
            ),
            testClass = "fr.husi.scenario.journey.SimpleScreenStateTonesJourneyTest",
        ),
        FeatureJourney(
            id = "notification_switch_instant_warm",
            userPromise = "Notification «Сменить» headlessly switches to the best warm reserve verified by live probes (never the dead one) without opening the picker UI; non-switch outcomes keep the connection and only show a toast; the full picker opens only when configured",
            entryPoints = listOf(
                "WarmReserveSwitchPolicy.decideLiveManualSwitch",
                "WarmReserveQualityPolicy.compareForManualSwitch",
                "WarmReserveSwitchPolicy.resolveNotificationAction",
                "WarmReservePool.selectReserveIds",
            ),
            testClass = "fr.husi.scenario.journey.NotificationSwitchInstantWarmJourneyTest",
        ),
    )

    fun byId(id: String): FeatureJourney? = all.find { it.id == id }
}
