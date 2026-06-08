package fr.husi.simplemode

import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.bg.UnderlyingCarrierState
import fr.husi.database.DataStore
import fr.husi.repository.resolveRepository
import fr.husi.utils.simpleModeLog

/**
 * Auto-resume simple-mode VPN after a carrier outage stop without requiring a manual connect tap.
 */
internal object SimpleModeCarrierReconnect {

    private val pendingTtlMs = SimpleModeVpnSessionMarker.HEARTBEAT_STALE_MS

    fun isPendingValid(nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = DataStore.simpleModePendingCarrierReconnectAt
        if (at <= 0L) return false
        return nowMs - at < pendingTtlMs
    }

    fun markPending(reason: String) {
        if (!DataStore.simpleMode) return
        if (!UnderlyingCarrierState.outageDuringVpnSession && !UnderlyingCarrierState.awaitingRestore) {
            return
        }
        val now = System.currentTimeMillis()
        DataStore.simpleModePendingCarrierReconnectAt = now
        DataStore.simpleModeActivity = "Network changed, reconnecting…"
        simpleModeLog(
            "SimpleMode",
            "H42 carrier_reconnect_pending reason=$reason profileId=${DataStore.selectedProxy}",
        )
    }

    fun clearPending(reason: String) {
        if (DataStore.simpleModePendingCarrierReconnectAt <= 0L) return
        DataStore.simpleModePendingCarrierReconnectAt = 0L
        simpleModeLog("SimpleMode", "H42 carrier_reconnect_cleared reason=$reason")
    }

    fun shouldDeferGracefulStop(): Boolean =
        UnderlyingCarrierState.awaitingRestore || UnderlyingCarrierState.outageDuringVpnSession

    fun tryResumeIfDue(trigger: String) {
        if (!isPendingValid()) {
            clearPending("expired")
            return
        }
        if (!DataStore.simpleMode) return
        if (BackendState.status.value.state != ServiceState.Stopped) return
        val profileId = DataStore.selectedProxy
        if (profileId <= 0L) return
        clearPending("resuming")
        SimpleModeConnectCoordinator.markPrepareVerifiedForConnect(profileId)
        DataStore.simpleModeActivity = "Network changed, reconnecting…"
        simpleModeLog(
            "SimpleMode",
            "H42 carrier_reconnect_resume trigger=$trigger profileId=$profileId",
        )
        resolveRepository().startService()
    }
}
