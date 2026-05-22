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

    private const val CHECK_INTERVAL_MS = 30_000L
    private const val CONSECUTIVE_FAIL_LIMIT = 2
    private const val ON_DEMAND_MIN_GAP_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checkLock = Mutex()
    private var job: Job? = null
    private var monitoredProfileId: Long = -1L
    private var monitoredOutboundTag: String = ""
    private var consecutiveFails: Int = 0
    private var lastOnDemandAt: Long = 0L

    fun schedule(profileId: Long, outboundTag: String) {
        if (!DataStore.simpleMode || outboundTag.isBlank()) return
        cancel()
        monitoredProfileId = profileId
        monitoredOutboundTag = outboundTag
        consecutiveFails = 0
        job = scope.launch {
            delay(CHECK_INTERVAL_MS)
            while (isActive && DataStore.simpleMode && DataStore.serviceState.connected) {
                val keepRunning = runHealthCheck(profileId, outboundTag)
                if (!keepRunning) break
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun triggerQuickCheck(reason: String) {
        if (!DataStore.simpleMode || !DataStore.serviceState.connected) return
        val profileId = monitoredProfileId
        val outboundTag = monitoredOutboundTag
        if (profileId <= 0L || outboundTag.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastOnDemandAt < ON_DEMAND_MIN_GAP_MS) return
        lastOnDemandAt = now
        scope.launch {
            simpleModeLog(
                "SimpleMode",
                "H34 session_health_quick_check reason=$reason profileId=$profileId",
            )
            runHealthCheck(profileId, outboundTag)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        monitoredProfileId = -1L
        monitoredOutboundTag = ""
        consecutiveFails = 0
        lastOnDemandAt = 0L
    }

    private suspend fun runHealthCheck(profileId: Long, outboundTag: String): Boolean = checkLock.withLock {
        if (!DataStore.simpleMode || !DataStore.serviceState.connected) return@withLock false
        if (DataStore.selectedProxy != profileId) return@withLock false
        val ok = runUrlHealthCheck(profileId, outboundTag)
        if (ok) {
            consecutiveFails = 0
            return@withLock true
        }
        consecutiveFails++
        simpleModeLog(
            "SimpleMode",
            "H34 session_health_fail profileId=$profileId streak=$consecutiveFails",
        )
        val failLimit = if (DataStore.activeWhitelistRestrictedNetwork) 1 else CONSECUTIVE_FAIL_LIMIT
        if (consecutiveFails >= failLimit) {
            handleUnhealthySession(profileId)
            return@withLock false
        }
        true
    }

    private suspend fun runUrlHealthCheck(profileId: Long, outboundTag: String): Boolean {
        val reachability = NetworkReachabilityProbe.probe(fast = true)
        DataStore.activeWhitelistRestrictedNetwork = reachability.whitelistOnly
        val wlOnly = reachability.whitelistOnly
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
        return SimpleModeTunnelHealthCheck.check(
            phase = "session_periodic",
            whitelistOnly = wlOnly,
            outboundTag = outboundTag,
            urls = healthUrls,
            timeoutMs = timeoutMs,
        )
    }

    private suspend fun handleUnhealthySession(profileId: Long) {
        if (!DataStore.serviceState.connected) return
        AutoServerSelector.recordHealthProbeFailure(profileId, error = null)
        DataStore.simpleModeActivity = "Server degraded, switching…"
        val wlOnly = DataStore.activeWhitelistRestrictedNetwork
        if (SimpleModeVpnCoordinator.tryRecoverAfterUnhealthySession(profileId)) {
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
