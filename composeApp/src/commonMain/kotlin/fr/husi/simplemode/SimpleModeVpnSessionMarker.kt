package fr.husi.simplemode

import fr.husi.database.DataStore
import fr.husi.utils.simpleModeLog

/**
 * Persists "VPN was active" across process death to detect unclean stops (kill, OEM limits).
 * Cleared on graceful disconnect, connect attempt, session upgrade, or stale heartbeat.
 */
object SimpleModeVpnSessionMarker {

    const val HEARTBEAT_STALE_MS = 30 * 60 * 1000L

    fun markActive(nowMs: Long = System.currentTimeMillis()) {
        if (!ExpertConnectRecoverPolicy.allowsFullModeHealthRecover()) return
        DataStore.simpleModeVpnSessionExpected = true
        DataStore.simpleModeVpnLastHeartbeatMs = nowMs
    }

    fun touchHeartbeat(nowMs: Long = System.currentTimeMillis()) {
        if (!DataStore.simpleModeVpnSessionExpected) return
        DataStore.simpleModeVpnLastHeartbeatMs = nowMs
    }

    fun markGracefulStop(reason: String) {
        if (!DataStore.simpleModeVpnSessionExpected && DataStore.simpleModeVpnLastHeartbeatMs == 0L) {
            return
        }
        DataStore.simpleModeVpnSessionExpected = false
        DataStore.simpleModeVpnLastHeartbeatMs = 0L
        simpleModeLog("SimpleMode", "H41 vpn_session_marker_cleared reason=$reason")
    }

    fun clearOnConnectAttempt() {
        markGracefulStop("connect_attempt")
        simpleModeLog("SimpleMode", "H41 unclean_stop_ack_connect")
    }

    /**
     * @param batteryRestrictedForLog mirrors [rememberShouldRequestBatteryOptimizations] for H41 log only.
     */
    fun evaluateUncleanStop(
        nowMs: Long = System.currentTimeMillis(),
        batteryRestrictedForLog: Boolean = false,
    ): Boolean {
        val lastHeartbeatMs = DataStore.simpleModeVpnLastHeartbeatMs
        return when (
            evaluateUncleanStopState(
                sessionExpected = DataStore.simpleModeVpnSessionExpected,
                lastHeartbeatMs = lastHeartbeatMs,
                nowMs = nowMs,
            )
        ) {
            UncleanStopEvaluation.None -> false
            UncleanStopEvaluation.ClearStale -> {
                markGracefulStop("heartbeat_stale")
                false
            }
            UncleanStopEvaluation.ShowNotice -> {
                simpleModeLog(
                    "SimpleMode",
                    "H41 unclean_stop_detected batteryRestricted=$batteryRestrictedForLog " +
                        "lastHeartbeatMs=$lastHeartbeatMs ageMs=${nowMs - lastHeartbeatMs}",
                )
                true
            }
        }
    }

    internal enum class UncleanStopEvaluation {
        None,
        ShowNotice,
        ClearStale,
    }

    internal fun evaluateUncleanStopState(
        sessionExpected: Boolean,
        lastHeartbeatMs: Long,
        nowMs: Long,
    ): UncleanStopEvaluation {
        if (!sessionExpected) return UncleanStopEvaluation.None
        if (lastHeartbeatMs <= 0L) return UncleanStopEvaluation.ShowNotice
        if (nowMs - lastHeartbeatMs > HEARTBEAT_STALE_MS) {
            return UncleanStopEvaluation.ClearStale
        }
        return UncleanStopEvaluation.ShowNotice
    }
}
