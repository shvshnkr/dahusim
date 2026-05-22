package fr.husi.database

import fr.husi.bootstrap.WhitelistBuiltinBootstrap
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 2K background TCP maintenance while VPN is down. Uses the same [ProxyProbeState] jail
 * and backoff rules as connect-time probes (built-in helpers included).
 */
object ProbeScheduler {

    suspend fun runBackgroundMaintenanceIfDue() {
        runBackgroundMaintenance(force = false)
    }

    suspend fun runBackgroundMaintenance(force: Boolean = false) {
        if (!DataStore.probe2kPersistenceEnabled) return
        if (!force && !DataStore.probe2kBackgroundSchedulerEnabled) return
        val now = System.currentTimeMillis()
        if (!force && now - DataStore.probe2kLastBackgroundRunAt < Probe2kDefaults.BACKGROUND_MIN_INTERVAL_MS) {
            simpleModeLog(
                "SimpleMode",
                "H35 probe_scheduler_skip reason=interval elapsedMs=${now - DataStore.probe2kLastBackgroundRunAt}",
            )
            return
        }
        if (DataStore.serviceState.connected) {
            simpleModeLog("SimpleMode", "H35 probe_scheduler_skip reason=vpn_connected")
            return
        }
        WhitelistBuiltinBootstrap.ensureGroupAndProfiles()
        val batchSize = DataStore.probe2kBackgroundBatchSize.coerceIn(8, 96)
        val workers = DataStore.probe2kBackgroundTcpWorkers.coerceIn(4, 48)
        val dueStates = SagerDatabase.probeStateDao.dueForProbe(now, batchSize)
        val dueIds = dueStates.map { it.profileId }.toMutableSet()
        if (dueIds.size < batchSize) {
            val need = batchSize - dueIds.size
            SagerDatabase.probeStateDao.unprobedProfileIds(need).forEach { dueIds += it }
        }
        if (dueIds.isEmpty()) {
            simpleModeLog("SimpleMode", "H35 probe_scheduler_idle")
            DataStore.probe2kLastBackgroundRunAt = now
            return
        }
        val proxies = dueIds.mapNotNull { SagerDatabase.proxyDao.getById(it) }
        if (proxies.isEmpty()) {
            DataStore.probe2kLastBackgroundRunAt = now
            return
        }
        val builtinIds = WhitelistBuiltinBootstrap.whitelistPoolProxies().map { it.id }.toSet()
        val whitelistOnly = DataStore.simpleModeUseWhitelistBuiltinPoolOnly
        val ordered = BuiltinPoolPolicy.reorderForCompactProbe(
            proxies = proxies,
            builtinProfileIds = builtinIds,
            whitelistBuiltinOnly = whitelistOnly,
        )
        simpleModeLog(
            "SimpleMode",
            "H35 probe_scheduler_start batch=${ordered.size} workers=$workers preset=${DataStore.probe2kPowerPreset} " +
                "due=${dueStates.size} wlOnly=$whitelistOnly",
        )
        Probe2kProgress.publishScan(0, ordered.size)
        val tcpMs = withContext(Dispatchers.IO) {
            ProfileTcpProber.probeTcpBatch(
                proxies = ordered,
                concurrency = workers,
                timeoutMs = Probe2kDefaults.TCP_PROBE_TIMEOUT_MS,
                onProgress = { done, total -> Probe2kProgress.publishScan(done, total) },
            )
        }
        ProxyProbeStateStore.persistPrepareResults(
            proxies = ordered,
            builtinProfileIds = builtinIds,
            tcpMs = tcpMs,
            urlMs = emptyMap(),
        )
        ProxyProbeStateStore.persistTcpFailures(
            proxies = ordered,
            builtinProfileIds = builtinIds,
            probedIds = tcpMs.keys,
        )
        Probe2kProgress.clearScan()
        Probe2kProgress.refreshPoolCounts()
        ProxyProbeStateStore.logPoolSnapshot("background")
        DataStore.probe2kLastBackgroundRunAt = now
    }

    fun filterUrlCandidatesForWarmState(
        candidates: List<ProxyEntity>,
        probeStates: Map<Long, ProxyProbeState>,
        networkHandoff: Boolean,
        whitelistBuiltinOnly: Boolean = false,
    ): List<ProxyEntity> {
        if (!DataStore.probe2kWarmRankingEnabled || networkHandoff) return candidates
        val now = System.currentTimeMillis()
        val eligible = ProbePoolEligibility.filterSelectable(candidates, probeStates)
        val filtered = eligible.filter { proxy ->
            val state = probeStates[proxy.id]
            if (whitelistBuiltinOnly) {
                val urlFresh = state != null &&
                    state.lastUrlMs > 0 &&
                    now - state.lastOkAt <= Probe2kDefaults.ALIVE_URL_FRESH_MS
                !urlFresh
            } else {
                !ProxyProbeStateStore.isFreshAlive(state, now)
            }
        }
        if (filtered.size != candidates.size) {
            simpleModeLog(
                "SimpleMode",
                "H35 warm_skip_url_probe skipped=${candidates.size - filtered.size} remain=${filtered.size}",
            )
        }
        return filtered.ifEmpty { eligible.ifEmpty { candidates } }
    }

    fun prioritizeTcpTargets(
        targets: List<ProxyEntity>,
        probeStates: Map<Long, ProxyProbeState>,
        priorityFirstIds: Set<Long>,
    ): List<ProxyEntity> {
        val now = System.currentTimeMillis()
        val eligible = ProbePoolEligibility.filterSelectable(targets, probeStates)
        if (!DataStore.probe2kWarmRankingEnabled) return eligible
        return eligible.sortedWith(
            compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
                .thenBy { ProxyProbeStateStore.probeStateRank(probeStates[it.id]) }
                .thenBy { ProxyProbeStateStore.persistedDelayScore(probeStates[it.id]) }
                .thenBy { it.userOrder },
        )
    }
}
