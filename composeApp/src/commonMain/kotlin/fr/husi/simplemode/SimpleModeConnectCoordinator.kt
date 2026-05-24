package fr.husi.simplemode

import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.bg.SubscriptionAutoUpdateRunner
import fr.husi.bg.SubscriptionUpdateMode
import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
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

    private val connectScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectJob: Job? = null

    fun isInFlight(): Boolean = connectJob?.isActive == true

    fun cancel(reason: String = "connect") {
        AutoServerSelector.cancelConnectPrepare(reason)
        connectJob?.cancel()
        connectJob = null
    }

    fun start(
        host: ConnectHost,
    ) {
        cancel("connect_supersede")
        DataStore.simpleModeAutoselectPoolMerged = false
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
            val prep = onDefaultDispatcher {
                coroutineScope {
                    val refreshJob = async {
                        DataStore.simpleModeActivity = "Refreshing subscriptions…"
                        withTimeoutOrNull(refreshBudgetMs) {
                            SubscriptionAutoUpdateRunner.refreshDueWithBudget(
                                mode = SubscriptionUpdateMode.ForegroundInteractive,
                                budgetMs = refreshBudgetMs,
                            )
                        }.also { outcome ->
                            simpleModeLog(
                                "SimpleMode",
                                if (outcome == null) {
                                    "H21 preconnect_subscription_refresh timeout budgetMs=$refreshBudgetMs " +
                                        "whitelistOnly=${net.whitelistOnly}"
                                } else {
                                    "H21 preconnect_subscription_refresh done " +
                                        "allSucceeded=${outcome.allSucceeded} " +
                                        "staleFails=${outcome.transportFailuresWhileVpnConnected} " +
                                        "whitelistOnly=${net.whitelistOnly}"
                                },
                            )
                        }
                    }
                    try {
                        DataStore.simpleModeActivity = "Finding best server…"
                        AutoServerSelector.prepareForConnect(owner = PrepareOwner.CONNECT)
                    } finally {
                        refreshJob.cancel()
                    }
                }
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
                    DataStore.simpleModeActivity = ""
                    simpleModeLog("SimpleMode", "connect_blocked_all_probes_dead")
                    withContext(Dispatchers.Main) { host.setPermissionPending(false) }
                    val choice = withContext(Dispatchers.Main) { host.promptAllServersDead() }
                    when (choice) {
                        SimpleModeAllServersDeadChoice.WaitForGoogle -> {
                            DataStore.autoConnectPausedUntilGoogle = true
                            resolveRepository().stopService()
                        }
                        SimpleModeAllServersDeadChoice.ExitApp -> exitApplication()
                    }
                }
                is PrepareForConnectResult.Success -> {
                    val selected = prep.profileId
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
                    val vpnProfileId = resolveVpnProfileId(selected)
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
                    DataStore.simpleModeActivity = "Starting VPN…"
                    withContext(Dispatchers.Main) { host.requestVpnConnect() }
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
        fun onNoInternet()
        fun onNoProfile()
        fun onNeedForegroundForPermission()
        fun onNeedUnlockForPermission()
        suspend fun promptAllServersDead(): SimpleModeAllServersDeadChoice
    }
}
