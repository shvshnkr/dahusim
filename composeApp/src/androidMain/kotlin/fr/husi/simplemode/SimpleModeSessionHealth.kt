package fr.husi.simplemode

import fr.husi.bg.NetworkReachabilityProbe
import fr.husi.bg.ServiceRegistry
import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
import fr.husi.ktx.readableMessage
import fr.husi.repository.resolveRepository
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Periodic URL health check while simple-mode VPN stays connected.
 * Switches via the existing fallback queue when the current server degrades.
 */
internal object SimpleModeSessionHealth {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checkLock = Mutex()
    private var job: Job? = null
    private var stallWatchdogJob: Job? = null
    private var monitoredProfileId: Long = -1L
    private var monitoredOutboundTag: String = ""
    private var consecutiveFails: Int = 0
    private var lastOnDemandAt: Long = 0L
    private var lastHealthError: String? = null
    private var lastHealthFailAt: Long = 0L
    private val lastCheckCompletedAt = AtomicLong(0L)
    private val stallRecoveryInFlight = AtomicBoolean(false)
    private val stallDeferTracker = StallDeferTracker()

    fun schedule(
        profileId: Long,
        outboundTag: String,
        firstCheckDelayMs: Long = SimpleModeSessionHealthPolicy.CHECK_INTERVAL_MS,
    ) {
        if (!DataStore.simpleMode || outboundTag.isBlank()) return
        cancel()
        monitoredProfileId = profileId
        monitoredOutboundTag = outboundTag
        consecutiveFails = 0
        lastHealthFailAt = 0L
        stallDeferTracker.reset()
        lastCheckCompletedAt.set(System.currentTimeMillis())
        stallWatchdogJob = scope.launch {
            while (isActive && DataStore.simpleMode && DataStore.serviceState.connected) {
                delay(SimpleModeSessionHealthPolicy.STALL_TICK_MS)
                maybeRecoverFromStalledProbe()
            }
        }
        job = scope.launch {
            delay(firstCheckDelayMs.coerceAtLeast(0L))
            while (isActive && DataStore.simpleMode && DataStore.serviceState.connected) {
                val activeProfileId = DataStore.selectedProxy
                if (activeProfileId <= 0L) break
                if (activeProfileId != monitoredProfileId) {
                    ensureMonitoring("profile_drift")
                    break
                }
                val activeOutboundTag = resolveMonitoredOutboundTag(activeProfileId)
                if (activeOutboundTag.isBlank()) break
                val keepRunning = runHealthCheck(activeProfileId, activeOutboundTag)
                if (!keepRunning) break
                delay(
                    SimpleModeSessionHealthPolicy.nextCheckDelayMs(
                        consecutiveFails,
                        DataStore.activeWhitelistRestrictedNetwork,
                    ),
                )
            }
        }
    }

    fun scheduleOnConnect(profileId: Long, outboundTag: String) {
        schedule(
            profileId = profileId,
            outboundTag = outboundTag,
            firstCheckDelayMs = SimpleModeSessionHealthPolicy.CONNECT_FIRST_CHECK_DELAY_MS,
        )
    }

    fun triggerQuickCheck(reason: String) {
        if (!DataStore.simpleMode) {
            logQuickCheckSkipped(reason, "simple_mode_off")
            return
        }
        if (!DataStore.serviceState.connected) {
            logQuickCheckSkipped(reason, "not_connected")
            return
        }
        ensureMonitoring(reason)
        var profileId = monitoredProfileId
        var outboundTag = monitoredOutboundTag
        if (profileId <= 0L || outboundTag.isBlank()) {
            profileId = DataStore.selectedProxy
            outboundTag = resolveMonitoredOutboundTag(profileId)
        }
        if (profileId <= 0L || outboundTag.isBlank()) {
            logQuickCheckSkipped(reason, "no_monitored_session")
            return
        }
        val now = System.currentTimeMillis()
        val minGap = SimpleModeSessionHealthPolicy.onDemandMinGapMs(reason)
        if (now - lastOnDemandAt < minGap) {
            logQuickCheckSkipped(reason, "debounce gapMs=${now - lastOnDemandAt}")
            return
        }
        lastOnDemandAt = now
        scope.launch {
            simpleModeLog(
                "SimpleMode",
                "H34 session_health_quick_check reason=$reason profileId=$profileId",
            )
            runHealthCheck(profileId, outboundTag)
        }
    }

    fun hasPendingDegradation(): Boolean {
        if (consecutiveFails > 0) return true
        val failAt = lastHealthFailAt
        return failAt > 0L &&
            System.currentTimeMillis() - failAt < SimpleModeSessionHealthPolicy.RECENT_FAIL_WINDOW_MS
    }

    fun cancel() {
        job?.cancel()
        job = null
        stallWatchdogJob?.cancel()
        stallWatchdogJob = null
        monitoredProfileId = -1L
        monitoredOutboundTag = ""
        consecutiveFails = 0
        lastOnDemandAt = 0L
        lastHealthError = null
        lastHealthFailAt = 0L
        lastCheckCompletedAt.set(0L)
        stallRecoveryInFlight.set(false)
        stallDeferTracker.reset()
    }

    private fun ensureMonitoring(reason: String) {
        if (!DataStore.simpleMode || !DataStore.serviceState.connected) return
        val profileId = DataStore.selectedProxy
        val outboundTag = resolveMonitoredOutboundTag(profileId)
        if (profileId <= 0L || outboundTag.isBlank()) return
        val monitoringStale = SimpleModeSessionHealthPolicy.isMonitoringStale(
            lastCheckCompletedAt = lastCheckCompletedAt.get(),
            nowMs = System.currentTimeMillis(),
        )
        val alreadyHealthy = job?.isActive == true &&
            monitoredProfileId == profileId &&
            monitoredOutboundTag == outboundTag &&
            !monitoringStale
        if (alreadyHealthy) return
        simpleModeLog(
            "SimpleMode",
            "H34 session_health_reschedule reason=$reason profileId=$profileId stale=$monitoringStale",
        )
        schedule(profileId, outboundTag)
    }

    private fun resolveMonitoredOutboundTag(profileId: Long): String {
        val fromService = ServiceRegistry.baseService?.data?.proxy?.config?.mainTag.orEmpty()
        if (fromService.isNotBlank()) return fromService
        if (monitoredProfileId == profileId && monitoredOutboundTag.isNotBlank()) {
            return monitoredOutboundTag
        }
        return ""
    }

    private fun logQuickCheckSkipped(reason: String, skip: String) {
        simpleModeLog(
            "SimpleMode",
            "H34 session_health_quick_check_skipped reason=$reason skip=$skip",
        )
    }

    private suspend fun runHealthCheck(profileId: Long, outboundTag: String): Boolean = checkLock.withLock {
        if (!DataStore.simpleMode || !DataStore.serviceState.connected) return@withLock false
        if (DataStore.selectedProxy != profileId) return@withLock false
        val ok = runUrlHealthCheck(profileId, outboundTag)
        lastCheckCompletedAt.set(System.currentTimeMillis())
        if (ok) {
            consecutiveFails = 0
            lastHealthError = null
            WarmReserveSessionCache.markLive(profileId)
            SimpleModeVpnSessionMarker.touchHeartbeat()
            if (DataStore.simpleModeActivity == ACTIVITY_CONNECTION_UNSTABLE_RECHECKING) {
                DataStore.simpleModeActivity = ""
            }
            return@withLock true
        }
        consecutiveFails++
        lastHealthFailAt = System.currentTimeMillis()
        simpleModeLog(
            "SimpleMode",
            "H34 session_health_fail profileId=$profileId streak=$consecutiveFails",
        )
        val failLimit = if (DataStore.activeWhitelistRestrictedNetwork) {
            1
        } else {
            SimpleModeSessionHealthPolicy.CONSECUTIVE_FAIL_LIMIT_OPEN
        }
        if (consecutiveFails < failLimit) {
            DataStore.simpleModeActivity = ACTIVITY_CONNECTION_UNSTABLE_RECHECKING
            return@withLock true
        }
        handleUnhealthySession(profileId)
        return@withLock false
    }

    private suspend fun maybeRecoverFromStalledProbe() {
        if (!DataStore.simpleMode || !DataStore.serviceState.connected) return
        val profileId = DataStore.selectedProxy
        if (profileId <= 0L || profileId != monitoredProfileId) return
        val completedAt = lastCheckCompletedAt.get()
        if (completedAt <= 0L) return
        val stalledMs = System.currentTimeMillis() - completedAt
        if (stalledMs < SimpleModeSessionHealthPolicy.STALL_RECOVERY_MS) return
        if (!stallRecoveryInFlight.compareAndSet(false, true)) return
        try {
            lastHealthError = SimpleModeSessionHealthPolicy.STALL_PROBE_ERROR
            lastHealthFailAt = System.currentTimeMillis()
            val nowMs = System.currentTimeMillis()
            val deferRecovery = stallDeferTracker.tryDefer(
                nowMs = nowMs,
                warmReserveVerifiedRecently = WarmReserveSessionCache.hasRecentVerifySuccess(
                    SimpleModeSessionHealthPolicy.WARM_STALL_DEFER_MS,
                ),
                profileSessionLive = WarmReserveSessionCache.isSessionLive(profileId),
            )
            if (deferRecovery) {
                simpleModeLog(
                    "SimpleMode",
                    "H34 session_health_stall_deferred profileId=$profileId stalledMs=$stalledMs",
                )
                ensureMonitoring("stall_deferred")
                return
            }
            simpleModeLog(
                "SimpleMode",
                "H34 session_health_stall_recovery profileId=$profileId stalledMs=$stalledMs",
            )
            handleUnhealthySession(profileId, SessionRecoverContext.StallWatchdog)
        } finally {
            lastCheckCompletedAt.set(System.currentTimeMillis())
            stallRecoveryInFlight.set(false)
        }
    }

    private suspend fun runUrlHealthCheck(profileId: Long, outboundTag: String): Boolean {
        val reachability = NetworkReachabilityProbe.probe(fast = true)
        DataStore.activeWhitelistRestrictedNetwork = reachability.whitelistOnly
        val wlOnly = reachability.whitelistOnly
        if (SimpleModeHealthRoute.skipTunnelHealthCheck(wlOnly)) {
            return true
        }
        delay(SimpleModeHealthRoute.postConnectWarmupMs(wlOnly))
        val timeoutMs = (DataStore.connectionTestTimeout * 2).coerceIn(5000, 12_000)
        val healthUrls = SimpleModeHealthRoute.healthCheckUrls(whitelistOnly = wlOnly)
        SimpleModeHealthRoute.logProbeConfig(
            phase = "session_periodic",
            whitelistOnly = wlOnly,
            route = SimpleModeHealthRoute.Route.TUNNEL_OUTBOUND,
            outboundTag = outboundTag,
            urls = healthUrls,
            timeoutMs = timeoutMs,
        )
        val tunnel = SimpleModeTunnelHealthCheck.probeTunnel(
            phase = "session_periodic",
            whitelistOnly = wlOnly,
            outboundTag = outboundTag,
            urls = healthUrls,
            timeoutMs = timeoutMs,
        )
        lastHealthError = tunnel.lastError
        return when (
            SimpleModeHealthRoute.classifyTunnelProbe(
                latencyMs = tunnel.latencyMs,
                wasSyntheticSuccess = tunnel.wasSyntheticSuccess,
                lastError = tunnel.lastError,
            )
        ) {
            is SimpleModeHealthRoute.TunnelHealthOutcome.RealSuccess,
            is SimpleModeHealthRoute.TunnelHealthOutcome.InconclusiveSynthetic,
            -> true

            is SimpleModeHealthRoute.TunnelHealthOutcome.HardFail -> false
        }
    }

    private suspend fun handleUnhealthySession(
        profileId: Long,
        context: SessionRecoverContext = SessionRecoverContext.SessionHealth,
    ) {
        if (!DataStore.serviceState.connected) return
        val wlOnly = DataStore.activeWhitelistRestrictedNetwork
        AutoServerSelector.recordHealthProbeFailure(profileId, error = lastHealthError, whitelistOnly = wlOnly)
        DataStore.simpleModeActivity = "Server degraded, switching…"
        val healthUrls = SimpleModeHealthRoute.healthCheckUrls(whitelistOnly = wlOnly)
        val messengerInvolved = SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM in healthUrls
        when (
            SimpleModeVpnCoordinator.tryRecoverAfterUnhealthySession(
                failedProfileId = profileId,
                lastHealthError = lastHealthError,
                messengerProbeInvolved = messengerInvolved,
                context = context,
            )
        ) {
            SessionRecoverOutcome.SoftKeepConnected,
            SessionRecoverOutcome.HardRecovered,
            -> return
            SessionRecoverOutcome.NotRecovered -> Unit
        }
        WarmReserveMaintainer.runOnceReplenishIfDue(profileId)
        val next = AutoServerSelector.tryMoveToFallback(profileId)
        if (next != null) {
            simpleModeLog(
                "SimpleMode",
                "H34 session_health_switch profileId=$profileId nextId=$next",
            )
            DataStore.selectedProxy = next
            SimpleModeTunnelRestart.markModeReconnect(wlOnly)
            ServiceRegistry.baseService?.reload() ?: resolveRepository().reloadService()
        } else {
            simpleModeLog("SimpleMode", "H34 session_health_exhausted profileId=$profileId")
            SimpleModeVpnCoordinator.scheduleAdaptation("session_health_exhausted")
        }
    }
}
