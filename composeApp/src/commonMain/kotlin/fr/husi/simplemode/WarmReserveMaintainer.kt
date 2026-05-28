package fr.husi.simplemode

import fr.husi.database.AutoServerSelectorSessionFallback
import fr.husi.database.DataStore
import fr.husi.database.Probe2kDefaults
import fr.husi.database.ProxyProbeStateStore
import fr.husi.database.SagerDatabase
import fr.husi.database.WarmReservePool
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
import kotlinx.coroutines.withTimeoutOrNull

/**
 * URL-verifies a small warm reserve from the fallback queue during an active simple-mode VPN session.
 */
object WarmReserveMaintainer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cycleLock = Mutex()
    private var job: Job? = null
    private var monitoredProfileId: Long = -1L
    private var lastReplenishAt: Long = 0L

    fun schedule(connectedProfileId: Long) {
        if (!WarmReserveMaintainerPolicy.canSchedule(WarmReservePool.isFeatureEnabled(), connectedProfileId)) {
            return
        }
        cancel()
        monitoredProfileId = connectedProfileId
        simpleModeLog(
            "SimpleMode",
            "H37 warm_reserve_schedule profileId=$connectedProfileId target=${WarmReservePool.targetCount()}",
        )
        job = scope.launch {
            cycleLock.withLock { runCycle(connectedProfileId, reason = "initial") }
            while (isActive && WarmReservePool.isFeatureEnabled() && DataStore.serviceState.connected) {
                delay(Probe2kDefaults.WARM_RESERVE_CYCLE_MS)
                if (DataStore.selectedProxy != monitoredProfileId &&
                    DataStore.selectedProxy > 0L
                ) {
                    monitoredProfileId = DataStore.selectedProxy
                }
                cycleLock.withLock { runCycle(monitoredProfileId, reason = "periodic") }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        monitoredProfileId = -1L
        lastReplenishAt = 0L
        DataStore.probe2kWarmReserveStatus = ""
        simpleModeLog("SimpleMode", "H37 warm_reserve_cancel")
    }

    suspend fun runOnceReplenishIfDue(connectedProfileId: Long): Boolean {
        if (!WarmReserveMaintainerPolicy.canSchedule(WarmReservePool.isFeatureEnabled(), connectedProfileId)) {
            return false
        }
        return withTimeoutOrNull(Probe2kDefaults.WARM_RESERVE_PRE_FALLBACK_BUDGET_MS) {
            cycleLock.withLock {
                runCycle(connectedProfileId, reason = "pre_fallback")
            }
            true
        } == true
    }

    private suspend fun runCycle(connectedProfileId: Long, reason: String) {
        if (!WarmReservePool.isFeatureEnabled() || !DataStore.serviceState.connected) return
        if (connectedProfileId <= 0L) return
        val wlOnly = DataStore.activeWhitelistRestrictedNetwork
        val queue = AutoServerSelectorSessionFallback.parseQueue(DataStore.autoSelectFallbackQueue)
        if (queue.isEmpty()) {
            simpleModeLog("SimpleMode", "H37 warm_reserve_cycle_skip reason=empty_queue trigger=$reason")
            return
        }
        var probeStates = ProxyProbeStateStore.loadMap(queue)
        var reserveIds = WarmReservePool.selectReserveIds(queue, connectedProfileId, probeStates)
        val target = WarmReservePool.targetCount()
        var freshAlive = WarmReservePool.countFreshUrlAlive(reserveIds, probeStates)
        var deficit = WarmReservePool.deficit(reserveIds, probeStates)
        simpleModeLog(
            "SimpleMode",
            "H37 warm_reserve_cycle_start trigger=$reason target=$target reserveIds=$reserveIds " +
                "freshAlive=$freshAlive deficit=$deficit wlOnly=$wlOnly queueSize=${queue.size}",
        )
        if (reserveIds.isNotEmpty() && DataStore.serviceState.connected) {
            val verifyResults = WarmReserveLiveProbe.probeUrlDelaysParallel(
                profileIds = reserveIds,
                whitelistOnly = wlOnly,
            )
            verifyResults.forEach { (id, ms) ->
                simpleModeLog(
                    "SimpleMode",
                    "H37 warm_reserve_verify id=$id ok=${ms != null}${ms?.let { " ms=$it" } ?: ""}",
                )
            }
            probeStates = ProxyProbeStateStore.loadMap(queue)
        }
        reserveIds = WarmReservePool.selectReserveIds(queue, connectedProfileId, probeStates)
        freshAlive = WarmReservePool.countFreshUrlAlive(reserveIds, probeStates)
        deficit = WarmReservePool.deficit(reserveIds, probeStates)
        if (deficit > 0) {
            val now = System.currentTimeMillis()
            if (WarmReserveMaintainerPolicy.shouldSkipReplenish(reason, now, lastReplenishAt)) {
                simpleModeLog(
                    "SimpleMode",
                    "H37 warm_reserve_replenish_skip reason=debounce deficit=$deficit",
                )
            } else {
                lastReplenishAt = now
                val candidates = WarmReservePool.replenishCandidates(
                    queue = queue,
                    connectedId = connectedProfileId,
                    reserveIds = reserveIds,
                    probeStates = probeStates,
                    limit = deficit,
                )
                val replenishResults = if (candidates.isNotEmpty() && DataStore.serviceState.connected) {
                    WarmReserveLiveProbe.probeUrlDelaysParallel(
                        profileIds = candidates,
                        whitelistOnly = wlOnly,
                    )
                } else {
                    emptyMap()
                }
                var promoted = 0
                replenishResults.forEach { (id, ms) ->
                    if (ms != null) {
                        promoted++
                        simpleModeLog("SimpleMode", "H37 warm_reserve_replenish id=$id ok=true ms=$ms")
                    } else {
                        simpleModeLog("SimpleMode", "H37 warm_reserve_replenish id=$id ok=false")
                    }
                }
                probeStates = ProxyProbeStateStore.loadMap(queue)
                reserveIds = WarmReservePool.selectReserveIds(queue, connectedProfileId, probeStates)
                freshAlive = WarmReservePool.countFreshUrlAlive(reserveIds, probeStates)
                simpleModeLog(
                    "SimpleMode",
                    "H37 warm_reserve_replenish attempted=${candidates.size} promoted=$promoted",
                )
            }
        }
        WarmReservePool.updateStatusSnapshot(reserveIds, probeStates)
        if (freshAlive < target) {
            simpleModeLog(
                "SimpleMode",
                "H37 warm_reserve_partial alive=$freshAlive target=$target",
            )
        }
        ProxyProbeStateStore.logPoolSnapshot("warm_reserve")
    }
}
