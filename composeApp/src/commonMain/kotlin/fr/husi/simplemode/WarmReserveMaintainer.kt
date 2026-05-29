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
        WarmReserveSessionCache.clear()
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
        val cache = WarmReserveSessionCache
        val wlOnly = DataStore.activeWhitelistRestrictedNetwork
        val queue = AutoServerSelectorSessionFallback.parseQueue(DataStore.autoSelectFallbackQueue)
        if (queue.isEmpty()) {
            simpleModeLog("SimpleMode", "H37 warm_reserve_cycle_skip reason=empty_queue trigger=$reason")
            return
        }
        var probeStates = ProxyProbeStateStore.loadMap(queue)
        val target = WarmReservePool.targetCount()
        var reserveIds = WarmReservePool.selectReserveIds(queue, connectedProfileId, probeStates, target = target, cache = cache)
        var liveAlive = WarmReservePool.countSessionLive(reserveIds, cache)
        var freshAlive = WarmReservePool.countFreshUrlAlive(reserveIds, probeStates)
        var deficit = WarmReservePool.deficit(reserveIds, cache, target)
        simpleModeLog(
            "SimpleMode",
            "H37 warm_reserve_cycle_start trigger=$reason target=$target reserveIds=$reserveIds " +
                "liveAlive=$liveAlive freshAlive=$freshAlive deficit=$deficit wlOnly=$wlOnly queueSize=${queue.size}",
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
        reserveIds = WarmReservePool.liveReserveIds(queue, connectedProfileId, cache, probeStates, target)
        liveAlive = WarmReservePool.countSessionLive(reserveIds, cache)
        freshAlive = WarmReservePool.countFreshUrlAlive(reserveIds, probeStates)
        deficit = WarmReservePool.deficit(reserveIds, cache, target)
        if (deficit > 0) {
            val now = System.currentTimeMillis()
            if (WarmReserveMaintainerPolicy.shouldSkipReplenish(reason, now, lastReplenishAt)) {
                simpleModeLog(
                    "SimpleMode",
                    "H37 warm_reserve_replenish_skip reason=debounce deficit=$deficit",
                )
            } else {
                lastReplenishAt = now
                val scanLimit = maxOf(
                    Probe2kDefaults.WARM_REPLENISH_SCAN_LIMIT,
                    target * 4,
                )
                var candidates = WarmReservePool.replenishScanCandidates(
                    queue = queue,
                    connectedId = connectedProfileId,
                    reserveIds = reserveIds,
                    probeStates = probeStates,
                    cache = cache,
                    scanLimit = scanLimit,
                )
                var promoted = 0
                var scanIdx = 0
                while (
                    liveAlive < target &&
                    scanIdx < candidates.size &&
                    DataStore.serviceState.connected
                ) {
                    val batch = candidates.drop(scanIdx).take(Probe2kDefaults.WARM_SWITCH_LIVE_PARALLELISM)
                    scanIdx += batch.size
                    if (batch.isEmpty()) break
                    val replenishResults = WarmReserveLiveProbe.probeUrlDelaysParallel(
                        profileIds = batch,
                        whitelistOnly = wlOnly,
                    )
                    replenishResults.forEach { (id, ms) ->
                        if (ms != null) {
                            promoted++
                            simpleModeLog("SimpleMode", "H37 warm_reserve_replenish id=$id ok=true ms=$ms")
                        } else {
                            simpleModeLog("SimpleMode", "H37 warm_reserve_replenish id=$id ok=false")
                        }
                    }
                    probeStates = ProxyProbeStateStore.loadMap(queue)
                    reserveIds = WarmReservePool.liveReserveIds(queue, connectedProfileId, cache, probeStates, target)
                    liveAlive = WarmReservePool.countSessionLive(reserveIds, cache)
                    if (liveAlive < target && scanIdx >= candidates.size) {
                        candidates = WarmReservePool.replenishScanCandidates(
                            queue = queue,
                            connectedId = connectedProfileId,
                            reserveIds = reserveIds,
                            probeStates = probeStates,
                            cache = cache,
                            scanLimit = scanLimit,
                        )
                        scanIdx = 0
                        if (candidates.isEmpty()) break
                    }
                }
                freshAlive = WarmReservePool.countFreshUrlAlive(reserveIds, probeStates)
                simpleModeLog(
                    "SimpleMode",
                    "H37 warm_reserve_replenish scanLimit=$scanLimit promoted=$promoted liveAlive=$liveAlive",
                )
            }
        }
        WarmReservePool.updateStatusSnapshot(reserveIds, probeStates, cache)
        if (liveAlive < target) {
            simpleModeLog(
                "SimpleMode",
                "H37 warm_reserve_partial liveAlive=$liveAlive freshAlive=$freshAlive target=$target " +
                    "warmFailed=${cache.warmFailedIdsSnapshot()}",
            )
        }
        ProxyProbeStateStore.logPoolSnapshot("warm_reserve")
    }
}
