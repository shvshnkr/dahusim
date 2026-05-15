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

/**
 * When simple-mode VPN is up and the underlying network or WL/open class changes,
 * re-probe reachability, re-run [AutoServerSelector], and restart on a suitable profile.
 */
internal object SimpleModeVpnCoordinator {

    private const val ADAPT_DEBOUNCE_MS = 2_500L

    private val adaptScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adaptMutex = Mutex()
    private var adaptJob: Job? = null
    private var lastAdaptAt = 0L

    fun cancelAdaptation() {
        adaptJob?.cancel()
        adaptJob = null
    }

    fun scheduleAdaptation(reason: String) {
        if (!DataStore.simpleMode) return
        adaptJob?.cancel()
        adaptJob = adaptScope.launch {
            try {
                adaptMutex.withLock {
                    if (!isActive) return@withLock
                    val now = System.currentTimeMillis()
                    if (now - lastAdaptAt < ADAPT_DEBOUNCE_MS) {
                        simpleModeLog("SimpleMode", "H30 wl_adapt_skipped reason=debounce trigger=$reason")
                        return@withLock
                    }
                    lastAdaptAt = now
                    adaptLocked(reason)
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
     * After a failed post-connect URL test on a restricted network: reselect and restart
     * instead of pausing until Google is reachable.
     */
    suspend fun tryRecoverAfterUnhealthyPostConnect(
        failedProfileId: Long,
        whitelistOnly: Boolean,
    ): Boolean {
        if (!DataStore.simpleMode || !whitelistOnly) return false
        DataStore.autoConnectPausedUntilGoogle = false
        DataStore.simpleModeActivity = "Restricted network: trying another server…"
        simpleModeLog(
            "SimpleMode",
            "H30 wl_post_connect_recover start failedProfileId=$failedProfileId",
        )
        if (applyReselectAndRestart("post_connect_unhealthy", whitelistOnly, failedProfileId)) {
            return true
        }
        val fallback = AutoServerSelector.tryMoveToFallback(failedProfileId)
        if (fallback != null) {
            simpleModeLog(
                "SimpleMode",
                "H30 wl_post_connect_fallback failedProfileId=$failedProfileId nextId=$fallback",
            )
            ServiceRegistry.baseService?.reload() ?: resolveRepository().reloadService()
            return true
        }
        return false
    }

    private suspend fun adaptLocked(reason: String) {
        if (!DataStore.simpleMode) return
        if (!DataStore.serviceState.connected) {
            simpleModeLog("SimpleMode", "H30 wl_adapt_skipped reason=not_connected trigger=$reason")
            return
        }
        val reachability = NetworkReachabilityProbe.probe()
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
        if (!currentCoroutineContext().isActive) return
        AutoServerSelector.cancelInFlightPrepare()
        val previousId = DataStore.selectedProxy
        applyReselectAndRestart(reason, reachability.whitelistOnly, previousId)
    }

    private suspend fun applyReselectAndRestart(
        reason: String,
        whitelistOnly: Boolean,
        previousProfileId: Long,
    ): Boolean {
        if (!currentCoroutineContext().isActive) return false
        val prep = try {
            SimpleModeNetworkAdaptation.reselectForNetwork(whitelistOnly)
        } catch (_: CancellationException) {
            simpleModeLog("SimpleMode", "H30 wl_adapt_prepare_cancelled reason=$reason")
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
                if (!currentCoroutineContext().isActive) return false
                val newId = prep.profileId
                DataStore.selectedProxy = newId
                simpleModeLog(
                    "SimpleMode",
                    "H30 wl_adapt_restart reason=$reason wl=$whitelistOnly prev=$previousProfileId new=$newId",
                )
                if (!DataStore.serviceState.connected) {
                    return true
                }
                ServiceRegistry.baseService?.reload() ?: resolveRepository().reloadService()
                return true
            }
        }
    }
}
