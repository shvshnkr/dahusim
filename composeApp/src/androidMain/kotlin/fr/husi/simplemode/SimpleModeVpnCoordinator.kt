package fr.husi.simplemode

import fr.husi.bg.BackendState
import fr.husi.bg.NetworkReachabilityProbe
import fr.husi.bg.ServiceRegistry
import fr.husi.bg.ServiceState
import fr.husi.bg.WhitelistNetworkRoutingState
import fr.husi.database.AutoServerSelector
import fr.husi.database.AutoServerSelectorProbePolicy
import kotlinx.coroutines.CancellationException
import fr.husi.database.DataStore
import fr.husi.database.DirectProfileUrlProbe
import fr.husi.database.PrepareForConnectResult
import fr.husi.database.SagerDatabase
import fr.husi.repository.resolveRepository
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
    private const val HANDOFF_PREPARE_TIMEOUT_MS = 45_000L
    private const val HANDOFF_STABILIZE_MS = 700L
    private const val HANDOFF_PRESERVE_TIMEOUT_MS = 3_200L
    private const val HANDOFF_RECHECK_TIMEOUT_MS = 4_200L
    private const val HANDOFF_RECHECK_BACKOFF_MS = 600L
    private const val ALL_DEAD_RECOVERY_WINDOW_MS = 90_000L
    private const val ALL_DEAD_MAX_RECOVERY_STEPS = 3
    private const val ALL_DEAD_RETRY_BACKOFF_MS = 1_400L
    private const val HANDOFF_COALESCE_MS = 8_000L

    private enum class HandoffState {
        IDLE,
        STABILIZING,
        PRESERVE_CHECK,
        RESELECT,
        RELOAD,
    }

    private val adaptScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val adaptMutex = Mutex()
    private val adaptGeneration = AtomicInteger(0)
    private var adaptJob: Job? = null
    private var lastAdaptAt = 0L
    private var allDeadRecoveryCount = 0
    private var lastAllDeadRecoveryAt = 0L
    @Volatile
    private var handoffState = HandoffState.IDLE

    @Volatile
    private var lastHandoffAdaptScheduledAt = 0L

    fun shouldCoalesceReachabilityFlip(): Boolean {
        if (adaptJob?.isActive == true) return true
        val elapsed = System.currentTimeMillis() - lastHandoffAdaptScheduledAt
        return lastHandoffAdaptScheduledAt > 0L && elapsed < HANDOFF_COALESCE_MS
    }

    fun markTunnelHealthyAfterProbe() {
        SimpleModeTunnelRecoveryLimiter.resetOnHealthyConnect()
    }

    fun cancelAdaptation() {
        adaptGeneration.incrementAndGet()
        AutoServerSelector.cancelAdaptPrepare("adapt")
        adaptJob?.cancel()
        adaptJob = null
    }

    fun scheduleAdaptation(reason: String) {
        if (!DataStore.simpleMode) return
        if (isNetworkHandoffReason(reason)) {
            lastHandoffAdaptScheduledAt = System.currentTimeMillis()
        }
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
    suspend fun tryRecoverAfterUnhealthySession(
        failedProfileId: Long,
        lastHealthError: String? = null,
        messengerProbeInvolved: Boolean = false,
    ): Boolean {
        val whitelistOnly = DataStore.activeWhitelistRestrictedNetwork
        if (!DataStore.simpleMode) {
            if (DataStore.autoSelectFallbackQueue.isBlank()) return false
            val inconclusive = SimpleModeHealthRoute.isProbeFailureInconclusive(
                error = lastHealthError,
                whitelistOnly = whitelistOnly,
                phase = "post_connect",
                probeUrl = if (messengerProbeInvolved) {
                    SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM
                } else {
                    null
                },
            )
            if (inconclusive) {
                simpleModeLog(
                    "SimpleMode",
                    "H30 session_recover_inconclusive_skip profileId=$failedProfileId " +
                        "error=${lastHealthError.orEmpty()} simpleMode=false",
                )
                AutoServerSelector.recordHealthProbeFailure(failedProfileId, lastHealthError)
                DataStore.simpleModeActivity = ""
                return true
            }
            AutoServerSelector.recordHealthProbeFailure(failedProfileId, lastHealthError)
            val fallback = AutoServerSelector.tryMoveToFallback(failedProfileId)
            if (fallback != null) {
                simpleModeLog(
                    "SimpleMode",
                    "H30 session_recover_fallback simpleMode=false failedProfileId=$failedProfileId nextId=$fallback",
                )
                DataStore.selectedProxy = fallback
                SimpleModeConnectCoordinator.markPrepareVerifiedForConnect(fallback)
                requestTunnelReload(whitelistOnly, "session_recover_fallback", fallback)
                return true
            }
            return false
        }
        if (whitelistOnly) {
            DataStore.autoConnectPausedUntilGoogle = false
        }
        val inconclusive = SimpleModeHealthRoute.isProbeFailureInconclusive(
            error = lastHealthError,
            whitelistOnly = whitelistOnly,
            phase = "post_connect",
            probeUrl = if (messengerProbeInvolved) SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM else null,
        )
        if (inconclusive) {
            simpleModeLog(
                "SimpleMode",
                "H30 session_recover_inconclusive_skip profileId=$failedProfileId error=${lastHealthError.orEmpty()}",
            )
            AutoServerSelector.recordHealthProbeFailure(failedProfileId, lastHealthError)
            DataStore.simpleModeActivity = ""
            return true
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
        AutoServerSelector.recordHealthProbeFailure(failedProfileId, lastHealthError)
        val fallback = AutoServerSelector.tryMoveToFallback(failedProfileId)
        if (fallback != null) {
            simpleModeLog(
                "SimpleMode",
                "H30 session_recover_fallback failedProfileId=$failedProfileId nextId=$fallback",
            )
            DataStore.selectedProxy = fallback
            requestTunnelReload(whitelistOnly, "session_recover_fallback", fallback)
            return true
        }
        val recoverGen = adaptGeneration.incrementAndGet()
        AutoServerSelector.cancelAdaptPrepare("session_recover")
        if (applyReselectAndRestart("session_unhealthy", whitelistOnly, failedProfileId, recoverGen)) {
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
        if (isNetworkHandoffReason(reason) &&
            tryPreserveCurrentSessionAfterHandoff(
                reason = reason,
                whitelistOnly = reachability.whitelistOnly,
                previousProfileId = previousId,
                adaptGen = adaptGen,
            )
        ) {
            handoffState = HandoffState.IDLE
            return
        }
        handoffState = HandoffState.RESELECT
        applyReselectAndRestart(
            reason = reason,
            whitelistOnly = reachability.whitelistOnly,
            previousProfileId = previousId,
            adaptGen = adaptGen,
            handoffPreserveFailed = isNetworkHandoffReason(reason),
        )
        handoffState = HandoffState.IDLE
    }

    private suspend fun tryPreserveCurrentSessionAfterHandoff(
        reason: String,
        whitelistOnly: Boolean,
        previousProfileId: Long,
        adaptGen: Int,
    ): Boolean {
        if (previousProfileId <= 0L) return false
        handoffState = HandoffState.STABILIZING
        delay(HANDOFF_STABILIZE_MS)
        if (!currentCoroutineContext().isActive || !isAdaptCurrent(adaptGen)) return false
        handoffState = HandoffState.PRESERVE_CHECK
        val profile = SagerDatabase.proxyDao.getById(previousProfileId) ?: return false
        val firstProbe = withTimeoutOrNull(HANDOFF_PRESERVE_TIMEOUT_MS) {
            DirectProfileUrlProbe.urlTestDelay(
                profile = profile,
                whitelistOnly = whitelistOnly,
                tier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
            )
        }
        if (firstProbe != null && firstProbe > 0) {
            AutoServerSelectorProbePolicy.recordHandoffPreserveSuccess()
            simpleModeLog(
                "SimpleMode",
                "H30 handoff_preserve_keep reason=$reason profileId=$previousProfileId latencyMs=$firstProbe",
            )
            DataStore.simpleModeActivity = ""
            return true
        }
        simpleModeLog("SimpleMode", "H30 handoff_preserve_recheck reason=$reason profileId=$previousProfileId")
        delay(HANDOFF_RECHECK_BACKOFF_MS)
        if (!currentCoroutineContext().isActive || !isAdaptCurrent(adaptGen)) return false
        val secondProbe = withTimeoutOrNull(HANDOFF_RECHECK_TIMEOUT_MS) {
            DirectProfileUrlProbe.urlTestDelay(
                profile = profile,
                whitelistOnly = whitelistOnly,
                tier = SimpleModeHealthRoute.ProbeTier.CONFIRM,
            )
        }
        if (secondProbe != null && secondProbe > 0) {
            AutoServerSelectorProbePolicy.recordHandoffPreserveSuccess()
            simpleModeLog(
                "SimpleMode",
                "H30 handoff_preserve_keep_after_recheck reason=$reason profileId=$previousProfileId latencyMs=$secondProbe",
            )
            DataStore.simpleModeActivity = ""
            return true
        }
        simpleModeLog("SimpleMode", "H30 handoff_preserve_reselect reason=$reason profileId=$previousProfileId")
        return false
    }

    private fun isAdaptCurrent(adaptGen: Int): Boolean = adaptGen == adaptGeneration.get()

    private fun isNetworkHandoffReason(reason: String): Boolean =
        reason == "network_handoff" || reason.startsWith("network_handoff:")

    private fun bypassAdaptDebounce(reason: String): Boolean =
        isNetworkHandoffReason(reason) ||
            reason == "reachability_flip" ||
            reason == "session_health_exhausted"

    private fun requiresTunnelRebuild(reason: String): Boolean =
        reason == "reachability_flip" ||
            reason == "session_unhealthy" ||
            reason == "session_recover_fallback" ||
            reason == "session_health_exhausted"

    private suspend fun applyReselectAndRestart(
        reason: String,
        whitelistOnly: Boolean,
        previousProfileId: Long,
        adaptGen: Int,
        handoffPreserveFailed: Boolean = false,
    ): Boolean {
        if (!currentCoroutineContext().isActive || !isAdaptCurrent(adaptGen)) {
            simpleModeLog("SimpleMode", "H30 wl_adapt_superseded before_prepare gen=$adaptGen reason=$reason")
            return false
        }
        val networkHandoff = isNetworkHandoffReason(reason) || reason == "reachability_flip"
        if (networkHandoff && handoffPreserveFailed) {
            delay(HANDOFF_RECHECK_BACKOFF_MS)
        }
        val prepareTimeoutMs = if (networkHandoff) {
            HANDOFF_PREPARE_TIMEOUT_MS
        } else {
            ADAPT_PREPARE_TIMEOUT_MS
        }
        val prep = try {
            withTimeoutOrNull(prepareTimeoutMs) {
                SimpleModeNetworkAdaptation.reselectForNetwork(
                    whitelistBuiltinOnly = whitelistOnly,
                    networkHandoff = networkHandoff,
                )
            } ?: run {
                simpleModeLog(
                    "SimpleMode",
                    "H30 wl_adapt_prepare_timeout reason=$reason gen=$adaptGen ms=$prepareTimeoutMs",
                )
                if (requiresTunnelRebuild(reason)) {
                    val reloadProfileId = resolveProfileAfterPrepareTimeout(previousProfileId, networkHandoff)
                    simpleModeLog(
                        "SimpleMode",
                        "H30 wl_adapt_timeout_reload reason=$reason prev=$previousProfileId reload=$reloadProfileId",
                    )
                    requestTunnelReload(whitelistOnly, "${reason}_timeout", reloadProfileId)
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
                return handleAllDeadRecovery(
                    reason = reason,
                    whitelistOnly = whitelistOnly,
                    previousProfileId = previousProfileId,
                    networkHandoff = networkHandoff,
                )
            }
            is PrepareForConnectResult.Success -> {
                resetAllDeadRecovery()
                if (!currentCoroutineContext().isActive || !isAdaptCurrent(adaptGen)) {
                    simpleModeLog("SimpleMode", "H30 wl_adapt_superseded before_reload gen=$adaptGen reason=$reason")
                    return false
                }
                val newId = prep.profileId
                val sameProfile = newId == previousProfileId
                if (sameProfile && !requiresTunnelRebuild(reason) && !handoffPreserveFailed) {
                    simpleModeLog(
                        "SimpleMode",
                        "H30 wl_adapt_unchanged reason=$reason profileId=$newId",
                    )
                    DataStore.simpleModeActivity = ""
                    return true
                }
                DataStore.selectedProxy = newId
                AutoServerSelector.syncFallbackIndexForConnected(newId)
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
                handoffState = HandoffState.RELOAD
                requestTunnelReload(whitelistOnly, reason, newId)
                return true
            }
        }
    }

    private fun resolveProfileAfterPrepareTimeout(
        previousProfileId: Long,
        networkHandoff: Boolean,
    ): Long {
        val selected = DataStore.selectedProxy
        if (selected > 0L && selected != previousProfileId) return selected
        if (networkHandoff) {
            val queueHead = DataStore.autoSelectFallbackQueue
                .split(',')
                .firstOrNull { it.isNotBlank() }
                ?.toLongOrNull()
            if (queueHead != null && queueHead > 0L && queueHead != previousProfileId) {
                return queueHead
            }
        }
        return previousProfileId
    }

    private fun requestTunnelReload(whitelistOnly: Boolean, reason: String, profileId: Long) {
        if (!SimpleModeTunnelRecoveryLimiter.tryConsumeReload(reason)) {
            simpleModeLog(
                "SimpleMode",
                "H30 tunnel_recovery_breaker reason=$reason profileId=$profileId",
            )
            scheduleAdaptation("tunnel_recovery_breaker")
            return
        }
        DataStore.selectedProxy = profileId
        SimpleModeTunnelRestart.markModeReconnect(whitelistOnly)
        ServiceRegistry.baseService?.reload() ?: resolveRepository().reloadService()
        simpleModeLog(
            "SimpleMode",
            "H30 tunnel_reload reason=$reason profileId=$profileId wl=$whitelistOnly",
        )
    }

    private fun resetAllDeadRecovery() {
        allDeadRecoveryCount = 0
        lastAllDeadRecoveryAt = 0L
    }

    private fun nextAllDeadRecoveryStep(): Int {
        val now = System.currentTimeMillis()
        if (now - lastAllDeadRecoveryAt > ALL_DEAD_RECOVERY_WINDOW_MS) {
            allDeadRecoveryCount = 0
        }
        lastAllDeadRecoveryAt = now
        allDeadRecoveryCount = (allDeadRecoveryCount + 1).coerceAtMost(ALL_DEAD_MAX_RECOVERY_STEPS)
        return allDeadRecoveryCount
    }

    private fun scheduleAllDeadRetry(reason: String, step: Int) {
        adaptScope.launch {
            delay(ALL_DEAD_RETRY_BACKOFF_MS * step)
            if (!DataStore.simpleMode || !DataStore.serviceState.connected) return@launch
            scheduleAdaptation("${reason}_all_dead_retry")
        }
    }

    private fun handleAllDeadRecovery(
        reason: String,
        whitelistOnly: Boolean,
        previousProfileId: Long,
        networkHandoff: Boolean,
    ): Boolean {
        val step = nextAllDeadRecoveryStep()
        if (step >= ALL_DEAD_MAX_RECOVERY_STEPS) {
            DataStore.simpleModeActivity = "No healthy servers detected, stopping VPN"
            simpleModeLog(
                "SimpleMode",
                "H30 wl_adapt_all_dead_stop reason=$reason step=$step profileId=$previousProfileId",
            )
            resolveRepository().stopService()
            resetAllDeadRecovery()
            return true
        }
        val fallback = AutoServerSelector.tryMoveToFallback(previousProfileId)
        val reloadProfileId = fallback ?: resolveProfileAfterPrepareTimeout(previousProfileId, networkHandoff)
        DataStore.simpleModeActivity = "Network unstable, retrying server…"
        simpleModeLog(
            "SimpleMode",
            "H30 wl_adapt_all_dead_recovery reason=$reason step=$step prev=$previousProfileId reload=$reloadProfileId",
        )
        requestTunnelReload(whitelistOnly, "${reason}_all_dead_step$step", reloadProfileId)
        scheduleAllDeadRetry(reason, step)
        return true
    }
}
