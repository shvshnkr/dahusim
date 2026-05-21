package fr.husi.database

import fr.husi.utils.simpleModeLog

/**
 * 2K probe scheduling helpers. Full background probing stays behind
 * [DataStore.probe2kBackgroundSchedulerEnabled]; connect-time path uses warm-state filtering.
 */
object ProbeScheduler {

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

    suspend fun logDueProbeBudget(limit: Int = 32) {
        if (!DataStore.probe2kBackgroundSchedulerEnabled) return
        val now = System.currentTimeMillis()
        val due = SagerDatabase.probeStateDao.dueForProbe(now, limit)
        simpleModeLog(
            "SimpleMode",
            "H35 probe_scheduler_due count=${due.size} limit=$limit",
        )
    }
}
