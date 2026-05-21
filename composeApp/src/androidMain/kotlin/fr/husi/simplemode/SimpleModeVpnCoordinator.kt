package fr.husi.simplemode

import fr.husi.bg.BackendState
import fr.husi.bg.NetworkReachabilityProbe
import fr.husi.bg.ServiceRegistry
import fr.husi.bg.ServiceState
import fr.husi.bg.WhitelistNetworkRoutingState
import fr.husi.database.AutoServerSelector
import kotlinx.coroutines.CancellationException
import fr.husi.database.DataStore
import fr.husi.database.PrepareForConnectResult
import fr.husi.repository.resolveRepository
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * When simple-mode VPN is up and the underlying network or WL/open class changes,
 * re-probe reachability, re-run [AutoServerSelector], and restart on a suitable profile.
 */
internal object SimpleModeVpnCoordinator {

    private const val ADAPT_DEBOUNCE_MS = 2_500L
    private const val ADAPT_PREPARE_TIMEOUT_MS = 30_000L

    private val adaptScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adaptMutex = Mutex()
    private val adaptGeneration = AtomicInteger(0)
    private var adaptJob: Job? = null
    private var lastAdaptAt = 0L

    fun cancelAdaptation() {
        adaptGeneration.incrementAndGet()
        AutoServerSelector.cancelAdaptPrepare("adapt")
        adaptJob?.cancel()
        adaptJob = null
    }

    fun scheduleAdaptation(reason: String) {
        if (!DataStore.simpleMode) return
        // #region agent log
        simpleModeDebugEvent(
            runId = "handoff-reconnect",
            hypothesisId = "H2_ADAPT_TRIGGER",
            location = "SimpleModeVpnCoordinator.scheduleAdaptation",
            message = "Adaptation scheduled",
            data = mapOf(
                "reason" to reason,
                "connected" to DataStore.serviceState.connected.toString(),
                "simpleMode" to DataStore.simpleMode.toString(),
                "jobActive" to (adaptJob?.isActive == true).toString(),
            ),
        )
        // #endregion
        val supersedeActiveAdapt = adaptJob?.isActive == true
        if (supersedeActiveAdapt) {
            adaptGeneration.incrementAndGet()
            AutoServerSelector.cancelAdaptPrepare("adapt_supersede")
        }
        adaptJob?.cancel()
        adaptJob = adaptScope.launch {
            try {
                adaptMutex.withLock {
                    if (!isActive) return@withLock
                    val now = System.currentTimeMillis()
                    val bypassDebounce = bypassAdaptDebounce(reason)
                    if (!supersedeActiveAdapt && !bypassDebounce && now - lastAdaptAt < ADAPT_DEBOUNCE_MS) {
                        simpleModeLog("SimpleMode", "H30 wl_adapt_skipped reason=debounce trigger=$reason")
                        return@withLock
                    }
                    lastAdaptAt = now
                    val adaptGen = adaptGeneration.incrementAndGet()
                    adaptLocked(reason, adaptGen)
                }
            } catch (_: CancellationException) {
                simpleModeLog("SimpleMode", "H30 wl_adapt_cancelled trigger=$reason")
            } finally {
                if (adaptJob === coroutineContext[Job]) {
                    adaptJob = null
                }
            }
        }
    }

    /**
     * Re-runs auto-select and reloads the tunnel after a failed health/post-connect check.
     */
    suspend fun tryRecoverAfterUnhealthySession(failedProfileId: Long): Boolean {
        if (!DataStore.simpleMode) return false
        val whitelistOnly = DataStore.activeWhitelistRestrictedNetwork
        if (whitelistOnly) {
            DataStore.autoConnectPausedUntilGoogle = false
        }
        DataStore.simpleModeActivity = if (whitelistOnly) {
            "Restricted network: trying another server…"
        } else {
            "Server degraded, reselecting…"
        }
        simpleModeLog(
            "SimpleMode",
            "H30 session_recover start failedProfileId=$failedProfileId wl=$whitelistOnly",
        )
        val recoverGen = adaptGeneration.incrementAndGet()
        AutoServerSelector.cancelAdaptPrepare("session_recover")
        if (applyReselectAndRestart("session_unhealthy", whitelistOnly, failedProfileId, recoverGen)) {
            return true
        }
        val fallback = AutoServerSelector.tryMoveToFallback(failedProfileId)
        if (fallback != null) {
            simpleModeLog(
                "SimpleMode",
                "H30 session_recover_fallback failedProfileId=$failedProfileId nextId=$fallback",
            )
            if (!DataStore.simpleMode) {
                simpleModeLog("SimpleMode", "H30 wl_adapt_reload_skipped reason=simple_mode_off")
                return true
            }
            DataStore.selectedProxy = fallback
            requestTunnelReload(whitelistOnly, "session_recover_fallback", fallback)
            return true
        }
        return false
    }

    private suspend fun adaptLocked(reason: String, adaptGen: Int) {
        // #region agent log
        simpleModeDebugEvent(
            runId = "handoff-reconnect",
            hypothesisId = "H2_ADAPT_TRIGGER",
            location = "SimpleModeVpnCoordinator.adaptLocked:entry",
            message = "Adaptation started",
            data = mapOf(
                "reason" to reason,
                "gen" to adaptGen.toString(),
                "connected" to DataStore.serviceState.connected.toString(),
                "selectedProxy" to DataStore.selectedProxy.toString(),
            ),
        )
        // #endregion
        if (!DataStore.simpleMode) return
        if (!DataStore.serviceState.connected) {
            simpleModeLog("SimpleMode", "H30 wl_adapt_skipped reason=not_connected trigger=$reason")
            return
        }
        if (!isAdaptCurrent(adaptGen)) return
        val reachability = NetworkReachabilityProbe.probe(fast = true)
        if (!isAdaptCurrent(adaptGen)) return
        DataStore.activeWhitelistRestrictedNetwork = reachability.whitelistOnly
        if (reachability.whitelistOnly) {
            DataStore.autoConnectPausedUntilGoogle = false
        }
        DataStore.simpleModeActivity = if (reachability.whitelistOnly) {
            "Restricted network: selecting server…"
        } else {
            "Network changed: selecting server…"
        }
        ServiceRegistry.baseService?.data?.resetNetwork()
        WhitelistNetworkRoutingState.applyReachability(
            reachability,
            requestReloadOnChange = false,
        )
        if (!currentCoroutineContext().isActive || !isAdaptCurrent(adaptGen)) return
        AutoServerSelector.cancelAdaptPrepare("adapt_locked")
        val previousId = DataStore.selectedProxy
        applyReselectAndRestart(reason, reachability.whitelistOnly, previousId, adaptGen)
    }

    private fun isAdaptCurrent(adaptGen: Int): Boolean = adaptGen == adaptGeneration.get()

    private fun bypassAdaptDebounce(reason: String): Boolean =
        reason == "network_handoff" ||
            reason == "reachability_flip" ||
            reason == "session_health_exhausted"

    private fun requiresTunnelRebuild(reason: String): Boolean =
        reason == "network_handoff" ||
            reason == "reachability_flip" ||
            reason == "session_unhealthy" ||
            reason == "session_recover_fallback" ||
            reason == "session_health_exhausted"

    private suspend fun applyReselectAndRestart(
        reason: String,
        whitelistOnly: Boolean,
        previousProfileId: Long,
        adaptGen: Int,
    ): Boolean {
        if (!currentCoroutineContext().isActive || !isAdaptCurrent(adaptGen)) {
            simpleModeLog("SimpleMode", "H30 wl_adapt_superseded before_prepare gen=$adaptGen reason=$reason")
            return false
        }
        val networkHandoff = reason == "network_handoff" || reason == "reachability_flip"
        val prep = try {
            withTimeoutOrNull(ADAPT_PREPARE_TIMEOUT_MS) {
                SimpleModeNetworkAdaptation.reselectForNetwork(
                    whitelistBuiltinOnly = whitelistOnly,
                    networkHandoff = networkHandoff,
                )
            } ?: run {
                simpleModeLog(
                    "SimpleMode",
                    "H30 wl_adapt_prepare_timeout reason=$reason gen=$adaptGen ms=$ADAPT_PREPARE_TIMEOUT_MS",
                )
                if (requiresTunnelRebuild(reason)) {
                    requestTunnelReload(whitelistOnly, "${reason}_timeout", previousProfileId)
                    return true
                }
                return false
            }
        } catch (_: CancellationException) {
            simpleModeLog("SimpleMode", "H30 wl_adapt_prepare_cancelled reason=$reason gen=$adaptGen")
            return false
        }
        if (!isAdaptCurrent(adaptGen)) {
            simpleModeLog("SimpleMode", "H30 wl_adapt_superseded after_prepare gen=$adaptGen reason=$reason")
            return false
        }
        when (prep) {
            PrepareForConnectResult.NoProfiles -> {
                simpleModeLog("SimpleMode", "H30 wl_adapt_no_profiles reason=$reason")
                DataStore.simpleModeActivity = ""
                return false
            }
            PrepareForConnectResult.AllProbesDead -> {
                simpleModeLog("SimpleMode", "H30 wl_adapt_all_dead reason=$reason")
                DataStore.simpleModeActivity = ""
                if (BackendState.status.value.state != ServiceState.Connected) {
                    return false
                }
                resolveRepository().stopService()
                return true
            }
            is PrepareForConnectResult.Success -> {
                if (!currentCoroutineContext().isActive || !isAdaptCurrent(adaptGen)) {
                    simpleModeLog("SimpleMode", "H30 wl_adapt_superseded before_reload gen=$adaptGen reason=$reason")
                    return false
                }
                val newId = prep.profileId
                val sameProfile = newId == previousProfileId
                if (sameProfile && !requiresTunnelRebuild(reason)) {
                    simpleModeLog(
                        "SimpleMode",
                        "H30 wl_adapt_unchanged reason=$reason profileId=$newId",
                    )
                    DataStore.simpleModeActivity = ""
                    return true
                }
                DataStore.selectedProxy = newId
                simpleModeLog(
                    "SimpleMode",
                    if (sameProfile) {
                        "H30 wl_adapt_reload_same_profile reason=$reason profileId=$newId gen=$adaptGen"
                    } else {
                        "H30 wl_adapt_restart reason=$reason wl=$whitelistOnly prev=$previousProfileId new=$newId gen=$adaptGen"
                    },
                )
                // #region agent log
                simpleModeDebugEvent(
                    runId = "handoff-reconnect",
                    hypothesisId = "H3_RELOAD_EXEC",
                    location = "SimpleModeVpnCoordinator.applyReselectAndRestart:reload",
                    message = "Adaptation requests service reload",
                    data = mapOf(
                        "reason" to reason,
                        "whitelistOnly" to whitelistOnly.toString(),
                        "prevProfileId" to previousProfileId.toString(),
                        "newProfileId" to newId.toString(),
                        "sameProfile" to sameProfile.toString(),
                        "gen" to adaptGen.toString(),
                    ),
                )
                // #endregion
                if (!DataStore.simpleMode) {
                    simpleModeLog("SimpleMode", "H30 wl_adapt_reload_skipped reason=simple_mode_off")
                    DataStore.simpleModeActivity = ""
                    return true
                }
                if (!DataStore.serviceState.connected) {
                    return true
                }
                requestTunnelReload(whitelistOnly, reason, newId)
                return true
            }
        }
    }

    private fun requestTunnelReload(whitelistOnly: Boolean, reason: String, profileId: Long) {
        DataStore.selectedProxy = profileId
        SimpleModeTunnelRestart.markModeReconnect(whitelistOnly)
        ServiceRegistry.baseService?.reload() ?: resolveRepository().reloadService()
        simpleModeLog(
            "SimpleMode",
            "H30 tunnel_reload reason=$reason profileId=$profileId wl=$whitelistOnly",
        )
    }
}
