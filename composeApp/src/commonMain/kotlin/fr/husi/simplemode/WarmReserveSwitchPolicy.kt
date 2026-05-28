package fr.husi.simplemode

import fr.husi.database.AutoServerSelectorSessionFallback
import fr.husi.database.ProbeState
import fr.husi.database.ProxyProbeState
import fr.husi.database.ProxyProbeStateStore
import fr.husi.database.WarmReservePool

data class WarmSwitchCandidate(
    val profileId: Long,
    val freshUrlVerified: Boolean,
    val probeState: Int,
)

enum class NotificationSwitchAction {
    /** One-tap switch to [WarmReserveSwitchPolicy.pickBestWarmTarget]. */
    INSTANT_WARM,
    /** Legacy [fr.husi.ui.SwitchActivity] profile picker. */
    OPEN_FULL_PICKER,
}

/** Warm-pool target selection for notification «Сменить». */
internal object WarmReserveSwitchPolicy {

    fun isWarmSwitchAvailable(
        simpleMode: Boolean,
        persistenceEnabled: Boolean,
        vpnConnected: Boolean,
        queue: List<Long>,
    ): Boolean =
        simpleMode && persistenceEnabled && vpnConnected && queue.isNotEmpty()

    fun resolveNotificationAction(
        useFullProfilePicker: Boolean,
        warmAvailable: Boolean,
    ): NotificationSwitchAction =
        if (useFullProfilePicker || !warmAvailable) {
            NotificationSwitchAction.OPEN_FULL_PICKER
        } else {
            NotificationSwitchAction.INSTANT_WARM
        }

    fun loadCandidates(
        queue: List<Long>,
        connectedId: Long,
        probeStates: Map<Long, ProxyProbeState>,
        target: Int = WarmReservePool.targetCount(),
    ): List<WarmSwitchCandidate> {
        val reserveIds = WarmReservePool.selectReserveIds(
            queue = queue,
            connectedId = connectedId,
            probeStates = probeStates,
            target = target,
        )
        return reserveIds.map { id ->
            val state = probeStates[id]
            WarmSwitchCandidate(
                profileId = id,
                freshUrlVerified = ProxyProbeStateStore.isFreshUrlVerified(state),
                probeState = state?.state ?: ProbeState.UNKNOWN,
            )
        }
    }

    fun decideManualSwitch(
        queue: List<Long>,
        connectedId: Long,
        liveUrlMs: Map<Long, Int?>,
        probeStates: Map<Long, ProxyProbeState>,
        target: Int = WarmReservePool.targetCount(),
    ): WarmSwitchDecision {
        val reserveIds = WarmReservePool.selectReserveIds(
            queue = queue,
            connectedId = connectedId,
            probeStates = probeStates,
            target = target,
        )
        return WarmReserveQualityPolicy.compareForManualSwitch(
            connectedId = connectedId,
            reserveIds = reserveIds,
            liveUrlMs = liveUrlMs,
            probeStates = probeStates,
        )
    }

    fun parseQueue(raw: String): List<Long> = AutoServerSelectorSessionFallback.parseQueue(raw)
}
