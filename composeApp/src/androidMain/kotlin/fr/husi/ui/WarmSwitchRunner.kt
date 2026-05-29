package fr.husi.ui

import fr.husi.database.DataStore
import fr.husi.database.ProfileManager
import fr.husi.database.ProxyProbeStateStore
import fr.husi.database.WarmReservePool
import fr.husi.simplemode.WarmReserveLiveProbe
import fr.husi.simplemode.WarmReserveSessionCache
import fr.husi.simplemode.WarmReserveSwitchPolicy
import fr.husi.simplemode.WarmSwitchDecision
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.delay

internal const val SIMPLE_MODE_ACTIVITY_COMPARING_BACKUPS = "Comparing backups"

enum class WarmSwitchRowStatus {
    Pending,
    Testing,
    Ok,
    Failed,
}

data class WarmSwitchRowUi(
    val profileId: Long,
    val name: String,
    val status: WarmSwitchRowStatus,
    val latencyMs: Int? = null,
    val isCurrent: Boolean = false,
)

internal object WarmSwitchRunner {

    suspend fun runCompareAndDecide(
        onRows: (List<WarmSwitchRowUi>) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
        onActivityLine: (String) -> Unit,
    ): WarmSwitchDecision {
        val connectedId = DataStore.selectedProxy
        val queue = WarmReserveSwitchPolicy.parseQueue(DataStore.autoSelectFallbackQueue)
        if (queue.isEmpty() || connectedId <= 0L) {
            return WarmSwitchDecision.NoReserves
        }
        val probeStates = ProxyProbeStateStore.loadMap(queue)
        val reserveIds = WarmReservePool.selectReserveIds(queue, connectedId, probeStates)
        val probeIds = (listOf(connectedId) + reserveIds).distinct()
        val total = probeIds.size

        val wlOnly = DataStore.activeWhitelistRestrictedNetwork
        val rowState = probeIds.associate { id ->
            id to WarmSwitchRowUi(
                profileId = id,
                name = ProfileManager.getProfile(id)?.displayName() ?: "#$id",
                status = WarmSwitchRowStatus.Pending,
                isCurrent = id == connectedId,
            )
        }.toMutableMap()

        fun emitRows() {
            onRows(probeIds.map { rowState.getValue(it) })
        }

        emitRows()
        onProgress(0, total)
        onActivityLine("$SIMPLE_MODE_ACTIVITY_COMPARING_BACKUPS 0/$total")

        val liveUrlMs = WarmReserveLiveProbe.probeUrlDelaysParallel(
            profileIds = probeIds,
            whitelistOnly = wlOnly,
        ) { done, batchTotal, profileId, urlMs ->
            rowState[profileId] = rowState.getValue(profileId).copy(
                status = if (urlMs != null) WarmSwitchRowStatus.Ok else WarmSwitchRowStatus.Failed,
                latencyMs = urlMs,
            )
            emitRows()
            onProgress(done, batchTotal)
            onActivityLine("$SIMPLE_MODE_ACTIVITY_COMPARING_BACKUPS $done/$batchTotal")
        }

        val updatedStates = ProxyProbeStateStore.loadMap(queue)
        val decision = WarmReserveSwitchPolicy.decideManualSwitch(
            queue = queue,
            connectedId = connectedId,
            liveUrlMs = liveUrlMs,
            probeStates = updatedStates,
        )
        if (decision is WarmSwitchDecision.SwitchTo) {
            WarmReserveSessionCache.markLive(decision.profileId)
        }
        simpleModeLog(
            "SimpleMode",
            "H37 warm_switch_decision decision=${decision::class.simpleName} connected=$connectedId reserves=$reserveIds",
        )
        delay(300L)
        return decision
    }
}
