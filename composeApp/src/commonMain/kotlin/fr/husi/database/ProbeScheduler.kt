package fr.husi.database

import fr.husi.bootstrap.WhitelistBuiltinBootstrap
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 2K probe scheduling helpers. Background TCP maintenance runs when
 * [DataStore.probe2kBackgroundSchedulerEnabled] and VPN is not connected.
 */
object ProbeScheduler {

    suspend fun runBackgroundMaintenanceIfDue() {
        runBackgroundMaintenance(force = false)
    }

    suspend fun runBackgroundMaintenance(force: Boolean = false) {
        if (!DataStore.probe2kPersistenceEnabled) return
        if (!force && !DataStore.probe2kBackgroundSchedulerEnabled) return
        if (DataStore.serviceState.connected) {
            simpleModeLog("SimpleMode", "H35 probe_scheduler_skip reason=vpn_connected")
            return
        }
        val batchSize = DataStore.probe2kBackgroundBatchSize.coerceIn(8, 96)
        val workers = DataStore.probe2kBackgroundTcpWorkers.coerceIn(4, 48)
        val now = System.currentTimeMillis()
        val dueStates = SagerDatabase.probeStateDao.dueForProbe(now, batchSize)
        val dueIds = dueStates.map { it.profileId }.toMutableSet()
        if (dueIds.size < batchSize) {
            val need = batchSize - dueIds.size
            SagerDatabase.probeStateDao.unprobedProfileIds(need).forEach { dueIds += it }
        }
        if (dueIds.isEmpty()) {
            simpleModeLog("SimpleMode", "H35 probe_scheduler_idle")
            return
        }
        val proxies = dueIds.mapNotNull { SagerDatabase.proxyDao.getById(it) }
        if (proxies.isEmpty()) return
        val builtinIds = WhitelistBuiltinBootstrap.whitelistPoolProxies().map { it.id }.toSet()
        simpleModeLog(
            "SimpleMode",
            "H35 probe_scheduler_start batch=${proxies.size} workers=$workers preset=${DataStore.probe2kPowerPreset}",
        )
        Probe2kProgress.publishScan(0, proxies.size)
        val tcpMs = withContext(Dispatchers.IO) {
            ProfileTcpProber.probeTcpBatch(
                proxies = proxies,
                concurrency = workers,
                timeoutMs = Probe2kDefaults.TCP_PROBE_TIMEOUT_MS,
                onProgress = { done, total -> Probe2kProgress.publishScan(done, total) },
            )
        }
        ProxyProbeStateStore.persistPrepareResults(
            proxies = proxies,
            builtinProfileIds = builtinIds,
            tcpMs = tcpMs,
            urlMs = emptyMap(),
        )
        ProxyProbeStateStore.persistTcpFailures(
            proxies = proxies,
            builtinProfileIds = builtinIds,
            probedIds = tcpMs.keys,
        )
        Probe2kProgress.clearScan()
        Probe2kProgress.refreshPoolCounts()
        ProxyProbeStateStore.logPoolSnapshot("background")
    }

    fun filterUrlCandidatesForWarmState(
        candidates: List<ProxyEntity>,
        probeStates: Map<Long, ProxyProbeState>,
        networkHandoff: Boolean,
    ): List<ProxyEntity> {
        if (!DataStore.probe2kWarmRankingEnabled || networkHandoff) return candidates
        val now = System.currentTimeMillis()
        val filtered = candidates.filter { proxy ->
            val state = probeStates[proxy.id]
            !ProxyProbeStateStore.isFreshAlive(state, now)
        }
        if (filtered.size != candidates.size) {
            simpleModeLog(
                "SimpleMode",
                "H35 warm_skip_url_probe skipped=${candidates.size - filtered.size} remain=${filtered.size}",
            )
        }
        return filtered.ifEmpty { candidates }
    }

    fun prioritizeTcpTargets(
        targets: List<ProxyEntity>,
        probeStates: Map<Long, ProxyProbeState>,
        priorityFirstIds: Set<Long>,
    ): List<ProxyEntity> {
        if (!DataStore.probe2kWarmRankingEnabled) return targets
        return targets.sortedWith(
            compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
                .thenBy { ProxyProbeStateStore.probeStateRank(probeStates[it.id]) }
                .thenBy { ProxyProbeStateStore.persistedDelayScore(probeStates[it.id]) }
                .thenBy { it.userOrder },
        )
    }
}
