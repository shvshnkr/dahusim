package fr.husi.simplemode

import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.bg.UnderlyingCarrierState
import fr.husi.database.DataStore
import fr.husi.repository.resolveRepository
import fr.husi.utils.simpleModeLog

/**
 * Auto-resume VPN after a carrier outage stop without requiring a manual connect tap.
 * Active in simple mode and in full mode when expert connect recover is enabled.
 */
internal object SimpleModeCarrierReconnect {

    private val pendingTtlMs = SimpleModeVpnSessionMarker.HEARTBEAT_STALE_MS

    fun isPendingValid(nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = DataStore.simpleModePendingCarrierReconnectAt
        if (at <= 0L) return false
        return nowMs - at < pendingTtlMs
    }

    fun markPending(reason: String) {
        if (!ExpertConnectRecoverPolicy.allowsFullModeHealthRecover()) return
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
        scheduleCarrierReconnectResume()
    }

    fun clearPending(reason: String) {
        if (DataStore.simpleModePendingCarrierReconnectAt <= 0L) return
        DataStore.simpleModePendingCarrierReconnectAt = 0L
        simpleModeLog("SimpleMode", "H42 carrier_reconnect_cleared reason=$reason")
    }

    fun shouldDeferGracefulStop(): Boolean =
        UnderlyingCarrierState.awaitingRestore || UnderlyingCarrierState.outageDuringVpnSession

    internal fun canResumeNow(
        pendingValid: Boolean = isPendingValid(),
        recoverAllowed: Boolean = ExpertConnectRecoverPolicy.allowsFullModeHealthRecover(),
        serviceState: ServiceState = BackendState.status.value.state,
        profileId: Long = DataStore.selectedProxy,
    ): Boolean =
        pendingValid && recoverAllowed && serviceState == ServiceState.Stopped && profileId > 0L

    fun tryResumeIfDue(trigger: String) {
        if (!isPendingValid()) {
            clearPending("expired")
            return
        }
        if (!canResumeNow()) return
        val profileId = DataStore.selectedProxy
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
