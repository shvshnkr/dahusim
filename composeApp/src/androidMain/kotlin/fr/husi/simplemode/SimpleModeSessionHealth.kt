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

/**
 * Periodic URL health check while simple-mode VPN stays connected.
 * Switches via the existing fallback queue when the current server degrades.
 */
internal object SimpleModeSessionHealth {

    private const val ON_DEMAND_MIN_GAP_MS = 15_000L
    private const val ON_DEMAND_UI_MIN_GAP_MS = 8_000L
    private const val RECENT_FAIL_WINDOW_MS = 120_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checkLock = Mutex()
    private var job: Job? = null
    private var monitoredProfileId: Long = -1L
    private var monitoredOutboundTag: String = ""
    private var consecutiveFails: Int = 0
    private var lastOnDemandAt: Long = 0L
    private var lastHealthError: String? = null
    private var lastHealthFailAt: Long = 0L

    fun schedule(profileId: Long, outboundTag: String) {
        if (!DataStore.simpleMode || outboundTag.isBlank()) return
        cancel()
        monitoredProfileId = profileId
        monitoredOutboundTag = outboundTag
        consecutiveFails = 0
        lastHealthFailAt = 0L
        job = scope.launch {
            delay(SimpleModeSessionHealthPolicy.CHECK_INTERVAL_MS)
            while (isActive && DataStore.simpleMode && DataStore.serviceState.connected) {
                val keepRunning = runHealthCheck(profileId, outboundTag)
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

    fun triggerQuickCheck(reason: String) {
        if (!DataStore.simpleMode) {
            logQuickCheckSkipped(reason, "simple_mode_off")
            return
        }
        if (!DataStore.serviceState.connected) {
            logQuickCheckSkipped(reason, "not_connected")
            return
        }
        var profileId = monitoredProfileId
        var outboundTag = monitoredOutboundTag
        if (profileId <= 0L || outboundTag.isBlank()) {
            if (reason == "ui_attach" || reason == "ui_resume") {
                ensureMonitoring(reason)
                profileId = monitoredProfileId
                outboundTag = monitoredOutboundTag
            }
            if (profileId <= 0L || outboundTag.isBlank()) {
                logQuickCheckSkipped(reason, "no_monitored_session")
                return
            }
        }
        val now = System.currentTimeMillis()
        val minGap = if (reason == "ui_resume" || reason == "ui_attach") {
            ON_DEMAND_UI_MIN_GAP_MS
        } else {
            ON_DEMAND_MIN_GAP_MS
        }
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
        return failAt > 0L && System.currentTimeMillis() - failAt < RECENT_FAIL_WINDOW_MS
    }

    fun cancel() {
        job?.cancel()
        job = null
        monitoredProfileId = -1L
        monitoredOutboundTag = ""
        consecutiveFails = 0
        lastOnDemandAt = 0L
        lastHealthError = null
        lastHealthFailAt = 0L
    }

    private fun ensureMonitoring(reason: String) {
        if (job?.isActive == true && monitoredProfileId > 0L && monitoredOutboundTag.isNotBlank()) {
            return
        }
        val profileId = DataStore.selectedProxy
        val outboundTag = ServiceRegistry.baseService?.data?.proxy?.config?.mainTag.orEmpty()
        if (profileId <= 0L || outboundTag.isBlank()) return
        simpleModeLog(
            "SimpleMode",
            "H34 session_health_reschedule reason=$reason profileId=$profileId",
        )
        schedule(profileId, outboundTag)
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
        if (ok) {
            consecutiveFails = 0
            lastHealthError = null
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

    private suspend fun handleUnhealthySession(profileId: Long) {
        if (!DataStore.serviceState.connected) return
        AutoServerSelector.recordHealthProbeFailure(profileId, error = lastHealthError)
        DataStore.simpleModeActivity = "Server degraded, switching…"
        val wlOnly = DataStore.activeWhitelistRestrictedNetwork
        if (SimpleModeVpnCoordinator.tryRecoverAfterUnhealthySession(
                failedProfileId = profileId,
                lastHealthError = lastHealthError,
                messengerProbeInvolved = wlOnly,
            )) {
            return
        }
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
