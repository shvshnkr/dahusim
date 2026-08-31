package fr.husi.simplemode

import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.bg.SubscriptionAutoUpdateRunner
import fr.husi.bg.SubscriptionUpdateMode
import fr.husi.database.AutoServerSelector
import fr.husi.database.AutoServerSelectorSessionFallback
import fr.husi.database.DataStore
import fr.husi.database.UserPoolPolicy
import fr.husi.database.PrepareForConnectResult
import fr.husi.database.PrepareOwner
import fr.husi.database.SagerDatabase
import fr.husi.ktx.exitApplication
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.repository.resolveRepository
import fr.husi.ui.SimpleModeAllServersDeadChoice
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs simple-mode pre-connect outside Compose scope so probes continue when the activity backgrounds.
 */
object SimpleModeConnectCoordinator {

    internal const val ALL_SERVERS_DEAD_PROMPT_TIMEOUT_MS = 30_000L
    internal const val WL_SERVER_REVIVAL_WATCH_MS = 6 * 60_000L
    internal const val WL_SERVER_REVIVAL_POLL_INTERVAL_MS = 45_000L

    private val connectScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectJob: Job? = null

    @Volatile
    private var autoselectPrepareCompletedForConnect = false

    fun isInFlight(): Boolean = connectJob?.isActive == true

    /** Autoselect prepare already URL-probed the chosen profile; skip manual pre-connect probe. */
    fun consumeAutoselectPrepareProbe(): Boolean {
        val had = autoselectPrepareCompletedForConnect
        autoselectPrepareCompletedForConnect = false
        return had
    }

    fun clearPrepareConnectMarkers() {
        autoselectPrepareCompletedForConnect = false
        DataStore.simpleModePrepareVerifiedProfileId = 0L
    }

    fun markPrepareVerifiedForConnect(profileId: Long) {
        autoselectPrepareCompletedForConnect = true
        DataStore.simpleModePrepareVerifiedProfileId = profileId
    }

    /**
     * Skip duplicate direct sing-box probe when prepare already URL-tested this profile.
     */
    fun shouldSkipManualProfileProbe(selectedProfileId: Long): Boolean {
        val verifiedId = DataStore.simpleModePrepareVerifiedProfileId
        if (verifiedId > 0L && selectedProfileId == verifiedId) {
            consumeAutoselectPrepareProbe()
            clearPrepareVerifiedProfileIdOnly()
            return true
        }
        if (selectedProfileId in AutoServerSelector.peekLastPrepareUrlVerifiedIds()) {
            val queue = AutoServerSelector.parseEffectiveFallbackQueue()
            if (selectedProfileId in queue) {
                simpleModeLog(
                    "SimpleMode",
                    "H17 manual_profile_probe_skipped reason=prepare_queue_url_verified profileId=$selectedProfileId",
                )
                return true
            }
        }
        return false
    }

    private fun clearPrepareVerifiedProfileIdOnly() {
        DataStore.simpleModePrepareVerifiedProfileId = 0L
    }

    /**
     * All-probes-dead resolution. The [ConnectHost.promptAllServersDead] wait must be bounded —
     * a lost/ignored prompt previously left [connectJob] in flight forever, freezing the UI in
     * "Preparing…" with an unresponsive Connect button (field log 2026-08-18 02:46, BS).
     */
    internal suspend fun handleAllServersDead(
        host: ConnectHost,
        promptTimeoutMs: Long = ALL_SERVERS_DEAD_PROMPT_TIMEOUT_MS,
    ) {
        DataStore.simpleModeActivity = ""
        simpleModeLog("SimpleMode", "connect_blocked_all_probes_dead")
        withContext(Dispatchers.Main) { host.setPermissionPending(false) }
        val choice = resolveAllServersDeadChoice(
            prompt = { withContext(Dispatchers.Main) { host.promptAllServersDead() } },
            timeoutMs = promptTimeoutMs,
        )
        when (choice) {
            SimpleModeAllServersDeadChoice.WaitForGoogle -> {
                DataStore.autoConnectPausedUntilGoogle = true
                // Persistent banner instead of the 30s prompt alone: the prompt can time out or
                // be dismissed, and a silent Stopped state reads as "Connect is broken" on BS
                // (field 2026-08-21 — user tapped Connect, all servers were dead, nothing
                // explained why). The UI shows it until the next attempt / successful connect.
                withContext(Dispatchers.Main) { host.onAllServersDead() }
                resolveRepository().stopService()
            }
            SimpleModeAllServersDeadChoice.ExitApp -> exitApplication()
        }
    }

    internal suspend fun resolveAllServersDeadChoice(
        prompt: suspend () -> SimpleModeAllServersDeadChoice,
        timeoutMs: Long = ALL_SERVERS_DEAD_PROMPT_TIMEOUT_MS,
    ): SimpleModeAllServersDeadChoice = withTimeoutOrNull(timeoutMs) { prompt() }
        ?.also { simpleModeLog("SimpleMode", "H21 all_servers_dead_choice choice=$it") }
        ?: SimpleModeAllServersDeadChoice.WaitForGoogle.also {
            simpleModeLog("SimpleMode", "H21 all_servers_dead_prompt_timeout")
        }

    /**
     * Bounded BS revival watch: after an AllProbesDead sweep, re-prepare on a poll interval until
     * a candidate verifies (auto-connect) or the watch window elapses (then the AllServersDead
     * prompt path runs). Cancelled by any new connect tap via [cancel].
     */
    internal suspend fun awaitWlServerRevival(
        initial: PrepareForConnectResult,
        refreshBudgetMs: Long,
        whitelistOnly: Boolean,
        watchMs: Long = WL_SERVER_REVIVAL_WATCH_MS,
        pollIntervalMs: Long = WL_SERVER_REVIVAL_POLL_INTERVAL_MS,
        prepare: suspend () -> PrepareForConnectResult = {
            prepareWithRefresh(refreshBudgetMs, whitelistOnly, compactWlSweep = true)
        },
    ): PrepareForConnectResult {
        val deadline = System.currentTimeMillis() + watchMs
        var result = initial
        var attempt = 0
        while (result is PrepareForConnectResult.AllProbesDead &&
            System.currentTimeMillis() < deadline
        ) {
            attempt++
            val waitMs = pollIntervalMs.coerceAtMost(
                (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L),
            )
            DataStore.simpleModeActivity = ACTIVITY_WAITING_FOR_SERVERS
            simpleModeLog(
                "SimpleMode",
                "H21 server_revival_watch attempt=$attempt waitMs=$waitMs " +
                    "leftMs=${(deadline - System.currentTimeMillis()).coerceAtLeast(0)}",
            )
            delay(waitMs)
            result = prepare()
        }
        if (result is PrepareForConnectResult.AllProbesDead) {
            simpleModeLog("SimpleMode", "H21 server_revival_watch exhausted attempts=$attempt")
        }
        return result
    }

    private suspend fun prepareWithRefresh(
        refreshBudgetMs: Long,
        whitelistOnly: Boolean,
        compactWlSweep: Boolean = false,
    ): PrepareForConnectResult = onDefaultDispatcher {
        coroutineScope {
            val refreshJob = async {
                DataStore.simpleModeActivity = "Refreshing subscriptions…"
                withTimeoutOrNull(refreshBudgetMs) {
                    SubscriptionAutoUpdateRunner.refreshDueWithBudget(
                        mode = SubscriptionUpdateMode.ForegroundInteractive,
                        budgetMs = refreshBudgetMs,
                        connectRefresh = true,
                    )
                }.also { outcome ->
                    simpleModeLog(
                        "SimpleMode",
                        if (outcome == null) {
                            "H21 preconnect_subscription_refresh timeout budgetMs=$refreshBudgetMs " +
                                "whitelistOnly=$whitelistOnly"
                        } else {
                            "H21 preconnect_subscription_refresh done " +
                                "allSucceeded=${outcome.allSucceeded} " +
                                "staleFails=${outcome.transportFailuresWhileVpnConnected} " +
                                "whitelistOnly=$whitelistOnly"
                        },
                    )
                }
            }
            try {
                DataStore.simpleModeActivity = "Finding best server…"
                AutoServerSelector.prepareForConnect(
                    owner = PrepareOwner.CONNECT,
                    compactWlSweep = compactWlSweep,
                )
            } finally {
                refreshJob.cancel()
            }
        }
    }

    fun cancel(reason: String = "connect") {
        AutoServerSelector.cancelConnectPrepare(reason)
        connectJob?.cancel()
        connectJob = null
        clearPrepareConnectMarkers()
    }

    fun takeOverByFullUi(reason: String = "full_manual_connect") {
        AutoServerSelector.clearPersistedFallbackQueueIfNeeded(reason)
        if (!isInFlight()) return
        simpleModeLog("SimpleMode", "handoff_takeover_by_full_ui reason=$reason")
        cancel(reason)
    }

    fun start(
        host: ConnectHost,
    ) {
        if (BackendState.status.value.state == ServiceState.Stopping) {
            DataStore.simpleModeActivity = "Stopping previous session…"
            simpleModeLog("SimpleMode", "connect_block_reason=service_stopping")
            return
        }
        cancel("connect_supersede")
        UserPoolPolicy.simpleModeUserPoolFallbackUsed = false
        DataStore.simpleModeActivity = "Checking network…"
        connectJob = connectScope.launch {
            runConnect(host)
        }
    }

    private suspend fun runConnect(host: ConnectHost) {
        val clickStartedAt = System.currentTimeMillis()
        var preconnectStage = "checking_network"
        val watchdog = connectScope.launch {
            delay(15_000)
            simpleModeLog(
                "SimpleMode",
                "H21 preconnect_stall stage=$preconnectStage elapsedMs=${System.currentTimeMillis() - clickStartedAt} " +
                    "activity=${DataStore.simpleModeActivity.ifBlank { "-" }}",
            )
            if (BackendState.status.value.state != ServiceState.Connected) {
                DataStore.simpleModeActivity = when (preconnectStage) {
                    "subscription_refresh" -> "Updating subscriptions (please wait)…"
                    "prepare_for_connect", "prepare_result" -> "Finding best server…"
                    "permission_request" -> "Starting VPN…"
                    else -> DataStore.simpleModeActivity
                }
            }
        }
        try {
            DataStore.simpleModeActivity = "Checking network..."
            simpleModeLog("SimpleMode", "H21 preconnect_stage stage=checking_network")
            val net = onDefaultDispatcher { probeSimpleModeNetwork() }
            preconnectStage = "network_probe_done"
            simpleModeLog(
                "SimpleMode",
                "H21 preconnect_stage stage=network_probe_done hasInternet=${net.hasAnyInternet} " +
                    "whitelistOnly=${net.whitelistOnly} googleOk=${net.googleOk}",
            )
            if (!net.hasAnyInternet) {
                DataStore.simpleModeActivity = ""
                simpleModeLog("SimpleMode", "connect_blocked_no_internet_probe")
                withContext(Dispatchers.Main) {
                    host.setPermissionPending(false)
                    host.onNoInternet()
                }
                return
            }
            DataStore.simpleModeUseWhitelistBuiltinPoolOnly = net.whitelistOnly
            DataStore.activeWhitelistRestrictedNetwork = net.whitelistOnly
            if (net.whitelistOnly) {
                DataStore.autoConnectPausedUntilGoogle = false
            }
            preconnectStage = "subscription_refresh"
            if (net.whitelistOnly) {
                simpleModeLog(
                    "SimpleMode",
                    "H28 preconnect_subscription_refresh whitelist_net mirror=github_via_yandex",
                )
            }
            val refreshBudgetMs = if (net.whitelistOnly) {
                DataStore.subscriptionConnectRefreshBudgetMs.coerceIn(200L, 8000L)
            } else {
                DataStore.subscriptionConnectRefreshBudgetMs.coerceIn(200L, 2800L)
            }
            simpleModeDebugEvent(
                runId = "run1",
                hypothesisId = "H2",
                location = "SimpleModeConnectCoordinator.kt:connect",
                message = "connect started",
                data = mapOf(
                    "whitelistOnly" to net.whitelistOnly.toString(),
                    "googleOk" to net.googleOk.toString(),
                ),
            )
            preconnectStage = "prepare_for_connect"
            DataStore.simpleModeActivity = "Finding best server…"
            var prep = prepareWithRefresh(refreshBudgetMs, net.whitelistOnly)
            if (prep is PrepareForConnectResult.AllProbesDead && net.whitelistOnly) {
                // BS servers flap on a minute scale (field 2026-08-18: 340 dead at 02:55,
                // alive at 03:17, dead again at 03:20). Instead of giving up on the first
                // sweep, keep watching: re-prepare on a poll interval and auto-connect the
                // moment any candidate verifies.
                prep = awaitWlServerRevival(
                    initial = prep,
                    refreshBudgetMs = refreshBudgetMs,
                    whitelistOnly = net.whitelistOnly,
                )
            }
            preconnectStage = "prepare_result"
            simpleModeLog("SimpleMode", "H21 preconnect_stage stage=prepare_result result=$prep")
            simpleModeLog(
                "SimpleMode",
                "H21 preconnect_done elapsedMs=${System.currentTimeMillis() - clickStartedAt} result=$prep",
            )
            when (prep) {
                PrepareForConnectResult.NoProfiles -> {
                    simpleModeLog("SimpleMode", "connect_blocked_no_profile")
                    withContext(Dispatchers.Main) {
                        host.setPermissionPending(false)
                        host.onNoProfile()
                    }
                }
                PrepareForConnectResult.AllProbesDead -> {
                    handleAllServersDead(host)
                }
                is PrepareForConnectResult.Success -> {
                    var selected = prep.profileId
                    if (selected <= 0L && DataStore.selectedProxy <= 0L) {
                        simpleModeLog("SimpleMode", "connect_blocked_no_profile")
                        withContext(Dispatchers.Main) {
                            host.setPermissionPending(false)
                            host.onNoProfile()
                        }
                        return
                    }
                    preconnectStage = "permission_request"
                    if (isKeyguardBlockingVpnDialog()) {
                        DataStore.simpleModeActivity = "Unlock screen to allow VPN…"
                        simpleModeLog("SimpleMode", "H21 permission_aborted reason=keyguard")
                        withContext(Dispatchers.Main) {
                            host.setPermissionPending(false)
                            host.onNeedUnlockForPermission()
                        }
                        return
                    }
                    DataStore.simpleModeActivity = "Allow VPN when prompted…"
                    simpleModeLog("SimpleMode", "connect_start_selected=$selected")
                    withContext(Dispatchers.Main) { host.setPermissionPending(true) }
                    simpleModeLog(
                        "SimpleMode",
                        "permission_request_started elapsedMs=${System.currentTimeMillis() - clickStartedAt}",
                    )
                    if (!awaitSimpleModeVpnPermissionUi()) {
                        DataStore.simpleModeActivity = "Return to app to allow VPN…"
                        simpleModeLog("SimpleMode", "H21 permission_wait_foreground timeout")
                        withContext(Dispatchers.Main) {
                            host.setPermissionPending(false)
                            host.onNeedForegroundForPermission()
                        }
                        return
                    }
                    var vpnProfileId = resolveVpnProfileId(selected)
                    if (vpnProfileId == null) {
                        // The concurrent subscription refresh deleted the just-selected profile from the DB.
                        // Re-prepare once against the settled DB instead of aborting to onNoProfile.
                        simpleModeLog(
                            "SimpleMode",
                            "H21 permission_retry_prepare reason=profile_missing selected=$selected",
                        )
                        val retry = onDefaultDispatcher {
                            AutoServerSelector.prepareForConnect(owner = PrepareOwner.CONNECT)
                        }
                        if (retry is PrepareForConnectResult.Success) {
                            selected = retry.profileId
                            vpnProfileId = resolveVpnProfileId(selected)
                        }
                    }
                    if (vpnProfileId == null) {
                        simpleModeLog(
                            "SimpleMode",
                            "H21 permission_aborted reason=profile_missing selected=$selected stored=${DataStore.selectedProxy}",
                        )
                        DataStore.simpleModeActivity = ""
                        withContext(Dispatchers.Main) {
                            host.setPermissionPending(false)
                            host.onNoProfile()
                        }
                        return
                    }
                    if (vpnProfileId != DataStore.selectedProxy) {
                        DataStore.selectedProxy = vpnProfileId
                    }
                    markPrepareVerifiedForConnect(vpnProfileId)
                    DataStore.simpleModeActivity = "Starting VPN…"
                    val launched = withContext(Dispatchers.Main) { launchSimpleModeVpnConnect(host) }
                    if (!launched) {
                        DataStore.simpleModeActivity = "Return to app to allow VPN…"
                        withContext(Dispatchers.Main) { host.setPermissionPending(false) }
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            simpleModeLog(
                "SimpleMode",
                "H21 preconnect_cancelled stage=$preconnectStage elapsedMs=${System.currentTimeMillis() - clickStartedAt}",
            )
            throw e
        } catch (t: Throwable) {
            simpleModeLog(
                "SimpleMode",
                "H21 preconnect_failed stage=$preconnectStage class=${t.javaClass.simpleName} error=${t.message ?: "unknown"}",
            )
            if (t is IllegalStateException && preconnectStage == "permission_request") {
                DataStore.simpleModeActivity = "Return to app to allow VPN…"
                withContext(Dispatchers.Main) {
                    host.setPermissionPending(false)
                    host.onNeedForegroundForPermission()
                }
                return
            }
            throw t
        } finally {
            watchdog.cancel()
            withContext(Dispatchers.Main) { host.setPermissionPending(false) }
            if (connectJob === currentCoroutineContext()[Job]) {
                connectJob = null
                if (!BackendState.status.value.state.canStop &&
                    isSimpleModePrepareActivity(DataStore.simpleModeActivity)
                ) {
                    DataStore.simpleModeActivity = ""
                }
            }
        }
    }

    private suspend fun resolveVpnProfileId(preferred: Long): Long? = onDefaultDispatcher {
        val id = preferred.takeIf { it > 0L } ?: DataStore.selectedProxy
        if (id <= 0L) return@onDefaultDispatcher null
        if (SagerDatabase.proxyDao.getById(id) != null) id else null
    }

    interface ConnectHost {
        fun setPermissionPending(pending: Boolean)
        fun requestVpnConnect()
        fun onVpnPermissionDenied()
        fun onNoInternet()
        fun onAllServersDead()
        fun onNoProfile()
        fun onNeedForegroundForPermission()
        fun onNeedUnlockForPermission()
        suspend fun promptAllServersDead(): SimpleModeAllServersDeadChoice
    }
}
