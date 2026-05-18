package fr.husi.simplemode

import fr.husi.bg.BackendState
import fr.husi.bg.ServiceRegistry
import fr.husi.bg.ServiceState
import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
import fr.husi.ktx.readableMessage
import fr.husi.libcore.Client
import fr.husi.libcore.Libcore
import fr.husi.repository.resolveRepository
import fr.husi.utils.closeQuietly
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic URL health check while simple-mode VPN stays connected.
 * Switches via the existing fallback queue when the current server degrades.
 */
internal object SimpleModeSessionHealth {

    private const val CHECK_INTERVAL_MS = 4L * 60 * 1000
    private const val CONSECUTIVE_FAIL_LIMIT = 2
    private const val WARMUP_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun schedule(profileId: Long, outboundTag: String) {
        if (!DataStore.simpleMode || outboundTag.isBlank()) return
        cancel()
        job = scope.launch {
            var consecutiveFails = 0
            delay(CHECK_INTERVAL_MS)
            while (isActive && DataStore.simpleMode && DataStore.serviceState.connected) {
                if (DataStore.selectedProxy != profileId) break
                if (BackendState.status.value.state != ServiceState.Connected) break
                val ok = runUrlHealthCheck(outboundTag)
                if (ok) {
                    consecutiveFails = 0
                } else {
                    consecutiveFails++
                    simpleModeLog(
                        "SimpleMode",
                        "H34 session_health_fail profileId=$profileId streak=$consecutiveFails",
                    )
                    if (consecutiveFails >= CONSECUTIVE_FAIL_LIMIT) {
                        handleUnhealthySession(profileId)
                        break
                    }
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun runUrlHealthCheck(outboundTag: String): Boolean {
        delay(WARMUP_MS)
        var client: Client? = null
        return try {
            client = Libcore.newClient(null)
            val timeoutMs = (DataStore.connectionTestTimeout * 2).coerceIn(5000, 12_000)
            val latencyMs = client.urlTest(outboundTag, DataStore.connectionTestURL, timeoutMs)
            latencyMs > 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            simpleModeLog(
                "SimpleMode",
                "H34 session_health_error class=${e.javaClass.simpleName} error=${e.readableMessage}",
            )
            false
        } finally {
            client?.closeQuietly()
        }
    }

    private suspend fun handleUnhealthySession(profileId: Long) {
        if (!DataStore.serviceState.connected) return
        AutoServerSelector.recordProbeFailure(profileId)
        DataStore.simpleModeActivity = "Server degraded, switching…"
        val wlOnly = DataStore.activeWhitelistRestrictedNetwork
        if (wlOnly) {
            val recovered = SimpleModeVpnCoordinator.tryRecoverAfterUnhealthyPostConnect(
                failedProfileId = profileId,
                whitelistOnly = true,
            )
            if (recovered) return
        }
        val next = AutoServerSelector.tryMoveToFallback(profileId)
        if (next != null) {
            simpleModeLog(
                "SimpleMode",
                "H34 session_health_switch profileId=$profileId nextId=$next",
            )
            SimpleModeTunnelRestart.markModeReconnect(wlOnly)
            ServiceRegistry.baseService?.reload() ?: resolveRepository().reloadService()
        } else {
            simpleModeLog("SimpleMode", "H34 session_health_exhausted profileId=$profileId")
            DataStore.simpleModeActivity = ""
        }
    }
}
