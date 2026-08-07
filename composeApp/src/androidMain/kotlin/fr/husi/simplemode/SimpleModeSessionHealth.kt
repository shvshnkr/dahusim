package fr.husi.simplemode

import fr.husi.bg.NetworkReachabilityProbe
import fr.husi.bg.ServiceRegistry
import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
import fr.husi.ktx.readableMessage
import fr.husi.repository.resolveRepository
import fr.husi.simplemode.SimpleModeHealthRoute
import fr.husi.simplemode.SimpleModeMessengerProbe
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
    private var lastHealthProbeUrl: String? = null
    private var lastHealthFailAt: Long = 0L
    private var lastHealthOkAt: Long = 0L
    private val lastCheckCompletedAt = AtomicLong(0L)
    private val stallRecoveryInFlight = AtomicBoolean(false)
    private val stallDeferTracker = StallDeferTracker()

    private fun healthRecoverEnabled(): Boolean =
        ExpertConnectRecoverPolicy.allowsFullModeHealthRecover()

    private data class UrlHealthProbeOptions(
        val phase: String = "session_periodic",
        val forceProbe: Boolean = false,
        val skipWarmup: Boolean = false,
        val urls: List<String>? = null,
        val timeoutMs: Int? = null,
    )

    fun schedule(
        profileId: Long,
        outboundTag: String,
        firstCheckDelayMs: Long = SimpleModeSessionHealthPolicy.CHECK_INTERVAL_MS,
    ) {
        if (!healthRecoverEnabled() || outboundTag.isBlank()) return
        cancel()
        monitoredProfileId = profileId
        monitoredOutboundTag = outboundTag
        consecutiveFails = 0
        lastHealthFailAt = 0L
        lastHealthOkAt = 0L
        stallDeferTracker.reset()
        SimpleModeTunnelSoftRecoveryPolicy.resetDebounce()
        lastCheckCompletedAt.set(System.currentTimeMillis())
        stallWatchdogJob = scope.launch {
            while (isActive && healthRecoverEnabled() && DataStore.serviceState.connected) {
                delay(SimpleModeSessionHealthPolicy.STALL_TICK_MS)
                maybeRecoverFromStalledProbe()
            }
        }
        job = scope.launch {
            delay(firstCheckDelayMs.coerceAtLeast(0L))
            while (isActive && healthRecoverEnabled() && DataStore.serviceState.connected) {
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
        if (!healthRecoverEnabled()) {
            logQuickCheckSkipped(reason, "health_recover_off")
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
        lastHealthProbeUrl = null
        lastHealthFailAt = 0L
        lastHealthOkAt = 0L
        lastCheckCompletedAt.set(0L)
        stallRecoveryInFlight.set(false)
        stallDeferTracker.reset()
        SimpleModeTunnelSoftRecoveryPolicy.resetDebounce()
    }

    private fun ensureMonitoring(reason: String) {
        if (!healthRecoverEnabled() || !DataStore.serviceState.connected) return
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
        if (!healthRecoverEnabled() || !DataStore.serviceState.connected) return@withLock false
        if (DataStore.selectedProxy != profileId) return@withLock false
        val ok = runUrlHealthCheck(profileId, outboundTag)
        if (!ok) {
            if (SimpleModeHealthRoute.isCarrierOutageProbeFailure(lastHealthError)) {
                consecutiveFails = 0
                DataStore.simpleModeActivity = "Network changed, reconnecting…"
                lastCheckCompletedAt.set(System.currentTimeMillis())
                return@withLock true
            }
            if (trySoftUpstreamRecovery(
                    profileId = profileId,
                    outboundTag = outboundTag,
                    reason = "health_fail",
                    lastError = lastHealthError,
                    probeUrl = lastHealthProbeUrl,
                )
            ) {
                lastCheckCompletedAt.set(System.currentTimeMillis())
                return@withLock true
            }
        }
        lastCheckCompletedAt.set(System.currentTimeMillis())
        if (ok) {
            consecutiveFails = 0
            lastHealthError = null
            lastHealthProbeUrl = null
            lastHealthOkAt = System.currentTimeMillis()
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
        if (!healthRecoverEnabled() || !DataStore.serviceState.connected) return
        val profileId = DataStore.selectedProxy
        if (profileId <= 0L || profileId != monitoredProfileId) return
        val completedAt = lastCheckCompletedAt.get()
        if (completedAt <= 0L) return
        val stalledMs = System.currentTimeMillis() - completedAt
        if (stalledMs < SimpleModeSessionHealthPolicy.STALL_RECOVERY_MS) return
        if (!stallRecoveryInFlight.compareAndSet(false, true)) return
        try {
            val nowMs = System.currentTimeMillis()
            val wlOnly = DataStore.activeWhitelistRestrictedNetwork
            val deferRecovery = SimpleModeSessionHealthPolicy.shouldDeferStallRecovery(
                tracker = stallDeferTracker,
                nowMs = nowMs,
                consecutiveFails = consecutiveFails,
                lastHealthOkAt = lastHealthOkAt,
                warmReserveVerifiedRecently = WarmReserveSessionCache.hasRecentVerifySuccess(
                    SimpleModeSessionHealthPolicy.WARM_STALL_DEFER_MS,
                ),
                profileSessionLive = WarmReserveSessionCache.isSessionLive(profileId),
                whitelistOnly = wlOnly,
            )
            if (deferRecovery) {
                simpleModeLog(
                    "SimpleMode",
                    "H34 stall_deferred profileId=$profileId stalledMs=$stalledMs wl=$wlOnly",
                )
                return
            }
            lastHealthError = SimpleModeSessionHealthPolicy.STALL_PROBE_ERROR
            lastHealthProbeUrl = null
            lastHealthFailAt = System.currentTimeMillis()
            val outboundTag = resolveMonitoredOutboundTag(profileId)
            if (outboundTag.isBlank()) return
            simpleModeLog(
                "SimpleMode",
                "H34 session_health_stall_recovery profileId=$profileId stalledMs=$stalledMs",
            )
            if (trySoftUpstreamRecovery(
                    profileId = profileId,
                    outboundTag = outboundTag,
                    reason = "stall_watchdog",
                    lastError = lastHealthError,
                    probeUrl = lastHealthProbeUrl,
                    forceProbe = true,
                )
            ) {
                ensureMonitoring("stall_soft_ok")
                return
            }
            handleUnhealthySession(profileId, SessionRecoverContext.StallWatchdog)
        } finally {
            lastCheckCompletedAt.set(System.currentTimeMillis())
            stallRecoveryInFlight.set(false)
        }
    }

    private suspend fun runUrlHealthCheck(
        profileId: Long,
        outboundTag: String,
        options: UrlHealthProbeOptions = UrlHealthProbeOptions(),
    ): Boolean {
        val reachability = NetworkReachabilityProbe.probe(fast = true)
        DataStore.activeWhitelistRestrictedNetwork = reachability.whitelistOnly
        val wlOnly = reachability.whitelistOnly
        if (SimpleModeHealthRoute.skipTunnelHealthCheck(wlOnly) && !options.forceProbe) {
            return true
        }
        if (!options.skipWarmup) {
            delay(SimpleModeHealthRoute.postConnectWarmupMs(wlOnly))
        }
        val baseTimeout = DataStore.connectionTestTimeout * 2
        val timeoutMs = options.timeoutMs
            ?: baseTimeout.coerceIn(5000, 12_000)
        val healthUrls = options.urls ?: SimpleModeHealthRoute.healthCheckUrls(whitelistOnly = wlOnly)
        SimpleModeHealthRoute.logProbeConfig(
            phase = options.phase,
            whitelistOnly = wlOnly,
            route = SimpleModeHealthRoute.Route.TUNNEL_OUTBOUND,
            outboundTag = outboundTag,
            urls = healthUrls,
            timeoutMs = timeoutMs,
        )
        val tunnel = SimpleModeTunnelHealthCheck.probeTunnel(
            phase = options.phase,
            whitelistOnly = wlOnly,
            outboundTag = outboundTag,
            urls = healthUrls,
            timeoutMs = timeoutMs,
        )
        lastHealthError = tunnel.lastError
        lastHealthProbeUrl = tunnel.lastProbeUrl
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

    private suspend fun trySoftUpstreamRecovery(
        profileId: Long,
        outboundTag: String,
        reason: String,
        lastError: String?,
        probeUrl: String?,
        forceProbe: Boolean = false,
    ): Boolean {
        if (!healthRecoverEnabled() || !DataStore.serviceState.connected) return false
        if (DataStore.selectedProxy != profileId) return false
        val wlOnly = DataStore.activeWhitelistRestrictedNetwork
        val nowMs = System.currentTimeMillis()
        if (!SimpleModeTunnelSoftRecoveryPolicy.shouldAttemptSoftRecovery(
                error = lastError,
                whitelistOnly = wlOnly,
                probeUrl = probeUrl,
                nowMs = nowMs,
                healthRecoverEnabled = healthRecoverEnabled(),
                connected = DataStore.serviceState.connected,
            )
        ) {
            return false
        }
        SimpleModeTunnelSoftRecoveryPolicy.markAttempt(nowMs)
        simpleModeLog(
            "SimpleMode",
            "H36 soft_upstream_recovery start profileId=$profileId wl=$wlOnly reason=$reason " +
                "error=${lastError.orEmpty()}",
        )
        ServiceRegistry.baseService?.data?.resetNetwork()
        val baseTimeout = DataStore.connectionTestTimeout * 2
        delay(SimpleModeTunnelSoftRecoveryPolicy.reprobeWarmupMs(wlOnly))
        val reprobeOptions = UrlHealthProbeOptions(
            phase = SimpleModeTunnelSoftRecoveryPolicy.SOFT_REPROBE_PHASE,
            forceProbe = forceProbe,
            skipWarmup = true,
            urls = SimpleModeTunnelSoftRecoveryPolicy.reprobeUrls(wlOnly),
            timeoutMs = SimpleModeTunnelSoftRecoveryPolicy.reprobeTimeoutMs(wlOnly, baseTimeout),
        )
        val ok = runUrlHealthCheck(profileId, outboundTag, reprobeOptions)
        if (ok) {
            consecutiveFails = 0
            lastHealthError = null
            lastHealthProbeUrl = null
            lastHealthOkAt = System.currentTimeMillis()
            WarmReserveSessionCache.markLive(profileId)
            SimpleModeVpnSessionMarker.touchHeartbeat()
            if (DataStore.simpleModeActivity == ACTIVITY_CONNECTION_UNSTABLE_RECHECKING) {
                DataStore.simpleModeActivity = ""
            }
            simpleModeLog(
                "SimpleMode",
                "H36 soft_upstream_recovery ok profileId=$profileId wl=$wlOnly reason=$reason",
            )
            return true
        }
        if (!wlOnly) {
            DataStore.simpleModeActivity = ACTIVITY_CONNECTION_UNSTABLE_RECHECKING
        }
        simpleModeLog(
            "SimpleMode",
            "H36 soft_upstream_recovery fail profileId=$profileId wl=$wlOnly reason=$reason " +
                "error=${lastHealthError.orEmpty()}",
        )
        return false
    }

    private suspend fun handleUnhealthySession(
        profileId: Long,
        context: SessionRecoverContext = SessionRecoverContext.SessionHealth,
    ) {
        if (!DataStore.serviceState.connected) return
        val wlOnly = DataStore.activeWhitelistRestrictedNetwork
        val healthUrls = SimpleModeHealthRoute.healthCheckUrls(whitelistOnly = wlOnly)
        val messengerInvolved = SimpleModeMessengerProbe.compositeRequired(wlOnly) ||
            healthUrls.any { SimpleModeMessengerProbe.isMessengerProbeUrl(it) }
        val probeUrl = if (messengerInvolved) SimpleModeHealthRoute.TUNNEL_HEALTH_TELEGRAM else null
        AutoServerSelector.recordHealthProbeFailure(profileId, error = lastHealthError, whitelistOnly = wlOnly, probeUrl = probeUrl)
        DataStore.simpleModeActivity = "Server degraded, switching…"
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
            SessionRecoverOutcome.NotRecovered -> {
                if (SimpleModeHealthRoute.isCarrierOutageProbeFailure(lastHealthError)) {
                    DataStore.simpleModeActivity = "Network changed, reconnecting…"
                    simpleModeLog(
                        "SimpleMode",
                        "H34 session_health_deferred_carrier_outage profileId=$profileId",
                    )
                    return
                }
            }
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
