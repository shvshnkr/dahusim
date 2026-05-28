package fr.husi.database

import fr.husi.utils.simpleModeLog
import kotlin.random.Random

object ProxyProbeStateStore {

    suspend fun loadMap(profileIds: Collection<Long>): Map<Long, ProxyProbeState> {
        if (profileIds.isEmpty()) return emptyMap()
        return SagerDatabase.probeStateDao
            .getByProfileIds(profileIds.distinct())
            .associateBy { it.profileId }
    }

    suspend fun persistPrepareResults(
        proxies: List<ProxyEntity>,
        tcpMs: Map<Long, Int>,
        urlMs: Map<Long, Int>,
    ) {
        if (!DataStore.probe2kPersistenceEnabled || proxies.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = loadMap(proxies.map { it.id })
        val updates = ArrayList<ProxyProbeState>(proxies.size)
        for (proxy in proxies) {
            val id = proxy.id
            val tcp = tcpMs[id]?.takeIf { it > 0 }
            val url = urlMs[id]?.takeIf { it > 0 }
            if (tcp == null && url == null) continue
            val prev = existing[id]
            val priority = resolveSourcePriority(proxy)
            updates += applyProbeResult(
                prev = prev,
                profileId = id,
                tcpMs = tcp,
                urlMs = url,
                sourcePriority = priority,
                errorClass = "",
                nowMs = now,
            )
        }
        if (updates.isNotEmpty()) {
            SagerDatabase.probeStateDao.upsertAll(updates)
        }
        Probe2kProgress.refreshPoolCounts()
    }

    suspend fun persistTcpFailures(
        proxies: List<ProxyEntity>,
        probedIds: Set<Long>,
    ) {
        if (!DataStore.probe2kPersistenceEnabled) return
        val now = System.currentTimeMillis()
        val existing = loadMap(proxies.map { it.id })
        val failures = ArrayList<ProxyProbeState>()
        for (proxy in proxies) {
            if (proxy.id in probedIds) continue
            failures += applyProbeResult(
                prev = existing[proxy.id],
                profileId = proxy.id,
                tcpMs = null,
                urlMs = null,
                sourcePriority = resolveSourcePriority(proxy),
                errorClass = "tcp_timeout",
                nowMs = now,
            )
        }
        if (failures.isNotEmpty()) {
            SagerDatabase.probeStateDao.upsertAll(failures)
            Probe2kProgress.refreshPoolCounts()
        }
    }

    suspend fun recordFailure(profileId: Long, errorClass: String = "connect_fail") {
        if (!DataStore.probe2kPersistenceEnabled || profileId <= 0L) return
        val now = System.currentTimeMillis()
        val prev = SagerDatabase.probeStateDao.getByProfileId(profileId)
        val next = applyProbeResult(
            prev = prev,
            profileId = profileId,
            tcpMs = null,
            urlMs = null,
            sourcePriority = prev?.sourcePriority ?: ProbeSourcePriority.SUBSCRIPTION,
            errorClass = errorClass,
            nowMs = now,
        )
        SagerDatabase.probeStateDao.upsertAll(listOf(next))
        Probe2kProgress.refreshPoolCounts()
    }

    suspend fun recordConnected(profileId: Long) {
        if (!DataStore.probe2kPersistenceEnabled || profileId <= 0L) return
        val now = System.currentTimeMillis()
        val prev = SagerDatabase.probeStateDao.getByProfileId(profileId)
        val next = if (prev != null) {
            prev.copy(
                state = ProbeState.ALIVE,
                lastOkAt = now,
                lastCheckedAt = now,
                failCountConsecutive = 0,
                nextProbeAt = now + Probe2kDefaults.ALIVE_URL_FRESH_MS,
                lastErrorClass = "",
            )
        } else {
            ProxyProbeState(
                profileId = profileId,
                state = ProbeState.ALIVE,
                lastCheckedAt = now,
                lastOkAt = now,
                failCountConsecutive = 0,
                nextProbeAt = now + Probe2kDefaults.ALIVE_URL_FRESH_MS,
            )
        }
        SagerDatabase.probeStateDao.upsertAll(listOf(next))
        Probe2kProgress.refreshPoolCounts()
    }

    suspend fun recordUrlSuccess(profileId: Long, urlMs: Int) {
        if (!DataStore.probe2kPersistenceEnabled || profileId <= 0L) return
        val now = System.currentTimeMillis()
        val prev = SagerDatabase.probeStateDao.getByProfileId(profileId)
        val next = applyProbeResult(
            prev = prev,
            profileId = profileId,
            tcpMs = prev?.lastTcpMs?.takeIf { it > 0 },
            urlMs = urlMs.takeIf { it > 0 },
            sourcePriority = prev?.sourcePriority ?: ProbeSourcePriority.SUBSCRIPTION,
            errorClass = "",
            nowMs = now,
        )
        SagerDatabase.probeStateDao.upsertAll(listOf(next))
    }

    fun isFreshAlive(state: ProxyProbeState?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (state == null) return false
        if (state.state != ProbeState.ALIVE && state.state != ProbeState.CANDIDATE) return false
        val tcpFresh = state.lastTcpMs > 0 && nowMs - state.lastCheckedAt <= Probe2kDefaults.ALIVE_TCP_FRESH_MS
        val urlFresh = state.lastUrlMs > 0 && nowMs - state.lastOkAt <= Probe2kDefaults.ALIVE_URL_FRESH_MS
        return tcpFresh || urlFresh
    }

    /** URL-verified within freshness window; used for session fallback and warm reserve. */
    fun isFreshUrlVerified(state: ProxyProbeState?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (state == null) return false
        if (state.state == ProbeState.DEAD || state.state == ProbeState.CEMETERY) return false
        return state.lastUrlMs > 0 && nowMs - state.lastOkAt <= Probe2kDefaults.ALIVE_URL_FRESH_MS
    }

    fun probeStateRank(state: ProxyProbeState?): Int = when (state?.state) {
        ProbeState.ALIVE -> 0
        ProbeState.CANDIDATE -> 1
        ProbeState.UNKNOWN -> 2
        ProbeState.SUSPECT -> 3
        ProbeState.DEAD -> 4
        ProbeState.CEMETERY -> 5
        else -> 2
    }

    fun persistedDelayScore(state: ProxyProbeState?): Int {
        if (state == null) return Int.MAX_VALUE
        if (state.ewmaDelayMs > 0) return state.ewmaDelayMs
        if (state.lastUrlMs > 0) return state.lastUrlMs
        if (state.lastTcpMs > 0) return state.lastTcpMs
        return Int.MAX_VALUE
    }

    suspend fun logPoolSnapshot(tag: String = "prepare") {
        if (!DataStore.probe2kPersistenceEnabled) return
        Probe2kProgress.refreshPoolCounts()
        simpleModeLog(
            "SimpleMode",
            "H35 probe_pool_snapshot tag=$tag alive=${DataStore.probe2kPoolAlive} candidate=${DataStore.probe2kPoolCandidate} " +
                "suspect=${DataStore.probe2kPoolSuspect} dead=${DataStore.probe2kPoolDead} jail=${DataStore.probe2kPoolCemetery} " +
                "unknown=${DataStore.probe2kPoolUnknown} reason=${DataStore.probe2kLastSelectionReason}",
        )
    }

    fun recordSelectionReason(reason: String) {
        DataStore.probe2kLastSelectionReason = reason
    }

    private fun resolveSourcePriority(proxy: ProxyEntity): Int {
        if (proxy.id == DataStore.selectedProxy || proxy.id == DataStore.autoSelectLastKnownGood) {
            return ProbeSourcePriority.PINNED
        }
        return ProbeSourcePriority.SUBSCRIPTION
    }

    private fun applyProbeResult(
        prev: ProxyProbeState?,
        profileId: Long,
        tcpMs: Int?,
        urlMs: Int?,
        sourcePriority: Int,
        errorClass: String,
        nowMs: Long,
    ): ProxyProbeState {
        val hadSuccess = tcpMs != null || urlMs != null
        val failStreak = if (hadSuccess) 0 else (prev?.failCountConsecutive ?: 0) + 1
        val successWindow = if (hadSuccess) {
            ((prev?.successCountWindow ?: 0) + 1).coerceAtMost(32)
        } else {
            prev?.successCountWindow ?: 0
        }
        val delaySample = urlMs ?: tcpMs
        val ewma = when {
            delaySample == null -> prev?.ewmaDelayMs ?: 0
            prev?.ewmaDelayMs == null || prev.ewmaDelayMs <= 0 -> delaySample
            else -> {
                val alpha = Probe2kDefaults.EWMA_ALPHA
                (alpha * delaySample + (1.0 - alpha) * prev.ewmaDelayMs).toInt()
            }
        }
        // Same fail-streak ladder for subscriptions and built-in helpers (sourcePriority is metadata only).
        val state = when {
            urlMs != null && urlMs > 0 -> ProbeState.ALIVE
            tcpMs != null && tcpMs > 0 -> ProbeState.CANDIDATE
            failStreak >= 6 -> ProbeState.CEMETERY
            failStreak >= 3 -> ProbeState.DEAD
            failStreak >= 1 -> ProbeState.SUSPECT
            else -> prev?.state ?: ProbeState.UNKNOWN
        }
        val nextProbeAt = computeNextProbeAt(state, failStreak, nowMs)
        if (prev?.state == ProbeState.CEMETERY && state != ProbeState.CEMETERY) {
            simpleModeLog(
                "SimpleMode",
                "H35 probe_unjail profileId=$profileId from=JAIL to=$state tcp=${tcpMs ?: "-"} url=${urlMs ?: "-"}",
            )
        }
        return ProxyProbeState(
            profileId = profileId,
            state = state,
            lastCheckedAt = nowMs,
            lastOkAt = if (hadSuccess) nowMs else prev?.lastOkAt ?: 0L,
            lastFailAt = if (hadSuccess) prev?.lastFailAt ?: 0L else nowMs,
            failCountConsecutive = failStreak,
            successCountWindow = successWindow,
            ewmaDelayMs = ewma,
            lastErrorClass = if (hadSuccess) "" else errorClass.ifBlank { prev?.lastErrorClass ?: "" },
            nextProbeAt = nextProbeAt,
            sourcePriority = sourcePriority,
            lastTcpMs = tcpMs ?: prev?.lastTcpMs ?: -1,
            lastUrlMs = urlMs ?: prev?.lastUrlMs ?: -1,
        )
    }

    private fun computeNextProbeAt(state: Int, failStreak: Int, nowMs: Long): Long {
        val base = when (state) {
            ProbeState.ALIVE -> Probe2kDefaults.ALIVE_URL_FRESH_MS
            ProbeState.CANDIDATE -> Probe2kDefaults.SUSPECT_RETRY_MS
            ProbeState.SUSPECT -> Probe2kDefaults.SUSPECT_RETRY_MS
            ProbeState.DEAD -> Probe2kDefaults.DEAD_BACKOFF_MS * failStreak.coerceIn(1, 4)
            ProbeState.CEMETERY -> Probe2kDefaults.CEMETERY_BACKOFF_MS
            else -> Probe2kDefaults.SUSPECT_RETRY_MS
        }
        val jitter = Random.nextLong(0, (base / 8).coerceAtLeast(1_000L))
        return nowMs + base + jitter
    }
}
