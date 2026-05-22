package fr.husi.database

import fr.husi.bootstrap.WhitelistBuiltinBootstrap
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.ktx.Logs
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class PrepareOwner {
    CONNECT,
    ADAPT,
}

private data class PrepareSession(val owner: PrepareOwner, val id: Int)

sealed class PrepareForConnectResult {
    data class Success(val profileId: Long) : PrepareForConnectResult()
    data object NoProfiles : PrepareForConnectResult()
    data object AllProbesDead : PrepareForConnectResult()
}

/**
 * Keeps server auto-selection deterministic by reusing recent health metrics
 * and a persisted fallback queue for reconnect retries.
 */
object AutoServerSelector {

    private const val TCP_PROBE_BATCH_CAP = 128
    /** Upper bound on connect-time TCP rounds (128 × 16 ≈ 2k profiles per prepare). */
    private const val TCP_PROBE_MAX_ROUNDS = 16
    private const val PROFILE_FAILURE_COOLDOWN_MS = 30L * 60 * 1000

    @Volatile
    private var probeUiActive = false

    private val prepareMutex = Mutex()
    private val connectPrepareGeneration = AtomicInteger(0)
    private val adaptPrepareGeneration = AtomicInteger(0)
    private val recentProbeFailures = ConcurrentHashMap<Long, Long>()

    fun cancelConnectPrepare(reason: String = "connect") {
        connectPrepareGeneration.incrementAndGet()
        simpleModeLog("SimpleMode", "H31 prepare_cancel_requested reason=$reason owner=connect")
    }

    fun cancelAdaptPrepare(reason: String = "adapt") {
        adaptPrepareGeneration.incrementAndGet()
        simpleModeLog("SimpleMode", "H31 prepare_cancel_requested reason=$reason owner=adapt")
    }

    /** Cancels both connect and adaptation prepares (legacy callers). */
    fun cancelInFlightPrepare(reason: String = "legacy") {
        cancelAdaptPrepare(reason)
        cancelConnectPrepare(reason)
    }

    private fun newPrepareSession(owner: PrepareOwner): PrepareSession {
        val id = when (owner) {
            PrepareOwner.CONNECT -> connectPrepareGeneration.incrementAndGet()
            PrepareOwner.ADAPT -> adaptPrepareGeneration.incrementAndGet()
        }
        return PrepareSession(owner, id)
    }

    private fun ensurePrepareCurrent(session: PrepareSession) {
        val current = when (session.owner) {
            PrepareOwner.CONNECT -> connectPrepareGeneration.get()
            PrepareOwner.ADAPT -> adaptPrepareGeneration.get()
        }
        if (session.id != current) {
            throw CancellationException("prepareForConnect superseded")
        }
    }

    private fun isPrepareCurrent(session: PrepareSession): Boolean {
        val current = when (session.owner) {
            PrepareOwner.CONNECT -> connectPrepareGeneration.get()
            PrepareOwner.ADAPT -> adaptPrepareGeneration.get()
        }
        return session.id == current
    }

    /** Stale [prepareForConnect] runs must not overwrite UI after tunnel is up, except live probe progress. */
    private fun setSimpleModeActivity(text: String) {
        if (!probeUiActive && BackendState.status.value.state == ServiceState.Connected) {
            simpleModeLog("SimpleMode", "H19 activity_write_skipped_while_connected text=${text.take(48)}")
            return
        }
        DataStore.simpleModeActivity = text
    }

    private fun probeConcurrency(whitelistBuiltinOnly: Boolean): Int {
        val base = DataStore.connectionTestConcurrent
        return if (whitelistBuiltinOnly) {
            (base * 2).coerceIn(8, 16)
        } else {
            base.coerceIn(2, 12)
        }
    }

    private fun tcpProbeConcurrency(whitelistBuiltinOnly: Boolean): Int {
        val base = DataStore.connectionTestConcurrent
        return if (whitelistBuiltinOnly) {
            (base * 3).coerceIn(12, 32)
        } else {
            base.coerceIn(4, 24)
        }
    }

    private fun buildCompactTcpProbePool(
        proxies: List<ProxyEntity>,
        priorityFirstIds: Set<Long>,
        maxTotal: Int,
    ): List<ProxyEntity> {
        val priority = proxies.filter { it.id in priorityFirstIds }
        val restCap = (maxTotal - priority.size).coerceAtLeast(0)
        val rest = proxies.filter { it.id !in priorityFirstIds }.take(restCap)
        return (priority + rest).distinctBy { it.id }
    }

    suspend fun prepareForConnect(
        networkHandoff: Boolean = false,
        owner: PrepareOwner = PrepareOwner.CONNECT,
    ): PrepareForConnectResult {
        val session = newPrepareSession(owner)
        val lockAttemptAt = System.currentTimeMillis()
        // #region agent log
        simpleModeDebugEvent(
            runId = "handoff-reconnect",
            hypothesisId = "H1_PREPARE_LOCK_WAIT",
            location = "AutoServerSelector.prepareForConnect:before_mutex",
            message = "Prepare requested",
            data = mapOf(
                "owner" to owner.name,
                "networkHandoff" to networkHandoff.toString(),
                "gen" to session.id.toString(),
            ),
        )
        // #endregion
        return prepareMutex.withLock {
            val lockWaitMs = (System.currentTimeMillis() - lockAttemptAt).coerceAtLeast(0)
            // #region agent log
            simpleModeDebugEvent(
                runId = "handoff-reconnect",
                hypothesisId = "H1_PREPARE_LOCK_WAIT",
                location = "AutoServerSelector.prepareForConnect:entered_mutex",
                message = "Prepare entered mutex",
                data = mapOf(
                    "owner" to owner.name,
                    "networkHandoff" to networkHandoff.toString(),
                    "gen" to session.id.toString(),
                    "lockWaitMs" to lockWaitMs.toString(),
                ),
            )
            // #endregion
            probeUiActive = true
            try {
                ensurePrepareCurrent(session)
                val result = prepareForConnectLocked(session, networkHandoff)
                ensurePrepareCurrent(session)
                result
            } catch (e: CancellationException) {
                simpleModeLog(
                    "SimpleMode",
                    "H31 prepare_aborted gen=${session.id} owner=${session.owner.name.lowercase()}",
                )
                throw e
            } finally {
                probeUiActive = false
            }
        }
    }

    private suspend fun prepareForConnectLocked(
        session: PrepareSession,
        networkHandoff: Boolean,
    ): PrepareForConnectResult {
        val selectedBefore = DataStore.selectedProxy
        val whitelistBuiltinOnly = DataStore.simpleModeUseWhitelistBuiltinPoolOnly
        DataStore.simpleModeUseWhitelistBuiltinPoolOnly = false

        WhitelistBuiltinBootstrap.ensureGroupAndProfiles()

        val allProxies = SagerDatabase.proxyDao.getAll()
        val subscriptionWhitelistMarked = allProxies.filter { it.isSubscriptionWhitelistMarked() }
        val subscriptionWhitelistIds = subscriptionWhitelistMarked.map { it.id }.toSet()

        val builtinFour = WhitelistBuiltinBootstrap.whitelistPoolProxies()
        val builtinFourIds = builtinFour.map { it.id }.toSet()

        val handoffPriorityIds = if (networkHandoff) {
            buildHandoffPriorityIds(selectedBefore)
        } else {
            emptySet()
        }

        val priorityFirstIds: Set<Long>
        val proxies: List<ProxyEntity>
        if (whitelistBuiltinOnly) {
            priorityFirstIds = (builtinFourIds + subscriptionWhitelistIds + handoffPriorityIds).toSet()
            val priorityHead = (builtinFour + subscriptionWhitelistMarked.sortedBy { it.userOrder })
                .distinctBy { it.id }
            val rest = allProxies.filter { it.id !in priorityFirstIds }
            proxies = priorityHead + rest
        } else {
            priorityFirstIds = handoffPriorityIds
            proxies = allProxies.filter { it.id !in subscriptionWhitelistIds }
        }
        simpleModeLog(
            "SimpleMode",
            "H24 autoselect_pool wlNet=$whitelistBuiltinOnly handoff=$networkHandoff " +
                "subsWlMarked=${subscriptionWhitelistMarked.size} pool=${proxies.size} " +
                "priorityFirst=${priorityFirstIds.size}",
        )
        val subscriptionCompactReprobe = AutoServerSelectorProbePolicy.useCompactReprobeForProxySetChange(
            proxies = proxies,
            whitelistBuiltinOnly = whitelistBuiltinOnly,
            networkHandoff = networkHandoff,
        )
        val effectiveHandoff = networkHandoff || subscriptionCompactReprobe
        val forceFullProbeReason = AutoServerSelectorProbePolicy.forceFullProbeReason(
            proxies = proxies,
            whitelistBuiltinOnly = whitelistBuiltinOnly,
            networkHandoff = networkHandoff,
        )
        if (subscriptionCompactReprobe) {
            simpleModeLog(
                "SimpleMode",
                "H25 compact_reprobe_proxy_set_changed pool=${proxies.size} graceMs=180000",
            )
        }
        val probeStates = if (DataStore.probe2kPersistenceEnabled || DataStore.probe2kWarmRankingEnabled) {
            ProxyProbeStateStore.loadMap(proxies.map { it.id })
        } else {
            emptyMap()
        }
        val connectPool = ProbePoolEligibility.filterSelectable(proxies, probeStates)
        val jailedCount = ProbePoolEligibility.countJailed(probeStates)
        if (jailedCount > 0) {
            simpleModeLog(
                "SimpleMode",
                "H35 probe_pool_jail count=$jailedCount total=${proxies.size} selectable=${connectPool.size}",
            )
        }
        if (!effectiveHandoff && forceFullProbeReason?.contains("proxy_set_changed") != true &&
            forceFullProbeReason?.contains("wl_to_open") != true
        ) {
            tryLastKnownGoodFastPath(
                proxies = connectPool,
                priorityFirstIds = priorityFirstIds,
                session = session,
                selectedBefore = selectedBefore,
                builtinProfileIds = builtinFourIds,
                whitelistBuiltinOnly = whitelistBuiltinOnly,
            )?.let { best ->
                ProxyProbeStateStore.recordSelectionReason("lkg_fast_path")
                simpleModeLog("SimpleMode", "H26 lkg_fast_path best=$best reason=url_verified")
                return PrepareForConnectResult.Success(best)
            }
        }

        if (proxies.isEmpty()) {
            // #region agent log
            simpleModeDebugEvent(
                runId = "run1",
                hypothesisId = "H4",
                location = "AutoServerSelector.kt:prepareForConnect",
                message = if (whitelistBuiltinOnly) {
                    "no whitelist builtin proxies"
                } else {
                    "no proxies in database"
                },
            )
            // #endregion
            simpleModeLog(
                "SimpleMode",
                if (whitelistBuiltinOnly) {
                    "H4 no_proxies_whitelist_builtin"
                } else {
                    "H4 no_proxies_global"
                },
            )
            return PrepareForConnectResult.NoProfiles
        }

        if (connectPool.isEmpty()) {
            simpleModeLog(
                "SimpleMode",
                "H22 prepare_all_in_jail total=${proxies.size} jailed=$jailedCount",
            )
            return PrepareForConnectResult.AllProbesDead
        }

        val availableCount = connectPool.count { it.status == ProxyEntity.STATUS_AVAILABLE }
        val initialCount = connectPool.count { it.status == ProxyEntity.STATUS_INITIAL }
        val badCount = connectPool.count {
            it.status == ProxyEntity.STATUS_UNREACHABLE ||
                it.status == ProxyEntity.STATUS_UNAVAILABLE ||
                it.status == ProxyEntity.STATUS_INVALID
        }
        val shouldQuickProbe = effectiveHandoff ||
            initialCount == connectPool.size ||
            availableCount == 0 ||
            forceFullProbeReason != null
        if (forceFullProbeReason != null) {
            simpleModeLog(
                "SimpleMode",
                "H25 full_probe_forced reason=$forceFullProbeReason handoff=$effectiveHandoff " +
                    "initial=$initialCount avail=$availableCount",
            )
        } else if (effectiveHandoff) {
            simpleModeLog(
                "SimpleMode",
                "H33 handoff_probe_compact initial=$initialCount avail=$availableCount",
            )
        }
        val urlTestCap = if (effectiveHandoff) {
            12
        } else {
            (probeConcurrency(whitelistBuiltinOnly) * 2).coerceIn(12, 32)
        }
        val extraUrlTestByTcp = if (effectiveHandoff) 4 else 8
        val parallelUrlPoolSize = (urlTestCap + extraUrlTestByTcp).coerceAtMost(connectPool.size)
        val urlSupplementCap = if (effectiveHandoff) 6 else 10
        val compactTcpProbe = whitelistBuiltinOnly || effectiveHandoff ||
            connectPool.size > TCP_PROBE_BATCH_CAP
        val tcpBatchCap = TCP_PROBE_BATCH_CAP
        val probePoolOrdered = BuiltinPoolPolicy.reorderForCompactProbe(
            proxies = connectPool,
            builtinProfileIds = builtinFourIds,
            whitelistBuiltinOnly = whitelistBuiltinOnly,
        )
        ensurePrepareCurrent(session)
        val urlConcurrency = probeConcurrency(whitelistBuiltinOnly)
        val tcpConcurrency = tcpProbeConcurrency(whitelistBuiltinOnly)

        var quickProbePings: Map<Long, Int> = emptyMap()
        var tcpTestedCount = 0
        var urlTestDelays: Map<Long, Int> = emptyMap()
        var urlTestCandidates: List<ProxyEntity> = emptyList()

        if (shouldQuickProbe) {
            val parallelUrlPool = ProbeScheduler.filterUrlCandidatesForWarmState(
                candidates = buildStratifiedUrlPool(
                    proxies = connectPool,
                    cap = parallelUrlPoolSize,
                    priorityFirstIds = priorityFirstIds,
                    probeStates = probeStates,
                    builtinProfileIds = builtinFourIds,
                    whitelistBuiltinOnly = whitelistBuiltinOnly,
                ),
                probeStates = probeStates,
                networkHandoff = effectiveHandoff,
            )
            simpleModeLog(
                "SimpleMode",
                "H14 quick_probe_started tcp_batch=$tcpBatchCap pool=${connectPool.size} " +
                    "parallel_url_pool=${parallelUrlPool.size} compactTcp=$compactTcpProbe " +
                    "handoff=$effectiveHandoff tcpConc=$tcpConcurrency urlConc=$urlConcurrency",
            )
            coroutineScope {
                val tcpJob = async(Dispatchers.IO) {
                    probeTcpInBatches(
                        connectPool = connectPool,
                        probePoolOrdered = probePoolOrdered,
                        priorityFirstIds = priorityFirstIds,
                        probeStates = probeStates,
                        tcpBatchCap = tcpBatchCap,
                        compactTcpProbe = compactTcpProbe,
                        tcpConcurrency = tcpConcurrency,
                        session = session,
                    ) { round, doneInRound, totalInRound, cumulativeTested, poolSize ->
                        setSimpleModeActivity(
                            if (compactTcpProbe && poolSize > totalInRound) {
                                "Testing TCP $cumulativeTested/$poolSize"
                            } else {
                                "Testing TCP $doneInRound/$totalInRound"
                            },
                        )
                    }
                }
                val urlJob = async(Dispatchers.IO) {
                    if (parallelUrlPool.isEmpty()) {
                        emptyMap()
                    } else {
                        setSimpleModeActivity("Testing URL 0/${parallelUrlPool.size}")
                        simpleModeLog(
                            "SimpleMode",
                            "H17 urltest_started candidates=${parallelUrlPool.size} baseCap=$urlTestCap extraTcp=$extraUrlTestByTcp mode=parallel_stratified",
                        )
                        urlTestTopCandidates(parallelUrlPool, urlConcurrency, session) { done, total ->
                            setSimpleModeActivity("Testing URL $done/$total")
                        }
                    }
                }
                ensurePrepareCurrent(session)
                val tcpProbeResult = tcpJob.await()
                quickProbePings = tcpProbeResult.pings
                tcpTestedCount = tcpProbeResult.testedCount
                val quickProbeAlive = quickProbePings.size
                val quickProbeHead = quickProbePings.entries
                    .sortedBy { it.value }
                    .take(5)
                    .joinToString(";") { "${it.key}:${it.value}" }
                simpleModeLog(
                    "SimpleMode",
                    "H14 quick_probe_done alive=$quickProbeAlive tested=$tcpTestedCount " +
                        "pool=${connectPool.size} best=$quickProbeHead",
                )
                ensurePrepareCurrent(session)
                var merged = urlJob.await().toMutableMap()
                val preUrlSorted = connectPool.sortedWith(
                    compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
                        .thenBy { if (quickProbePings.containsKey(it.id)) 0 else 1 }
                        .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .thenBy { statusRank(it.status) }
                        .thenBy { pingRank(it.ping) }
                        .thenByDescending { throughputRank(it) },
                )
                val baseUrlTest = buildStratifiedUrlPool(
                    proxies = preUrlSorted,
                    cap = urlTestCap,
                    priorityFirstIds = priorityFirstIds,
                    builtinProfileIds = builtinFourIds,
                    whitelistBuiltinOnly = whitelistBuiltinOnly,
                )
                val baseIds = baseUrlTest.map { it.id }.toSet()
                val extraTcpForUrlTest = if (quickProbePings.isNotEmpty()) {
                    connectPool
                        .asSequence()
                        .filter { it.id !in baseIds && quickProbePings.containsKey(it.id) }
                        .sortedBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .take(extraUrlTestByTcp)
                        .toList()
                } else {
                    emptyList()
                }
                urlTestCandidates = (baseUrlTest + extraTcpForUrlTest).distinctBy { it.id }
                val missing = urlTestCandidates
                    .filter { it.id !in merged }
                    .sortedBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                    .take(urlSupplementCap)
                if (missing.isNotEmpty()) {
                    simpleModeLog(
                        "SimpleMode",
                        "H17 urltest_supplement candidates=${missing.size} cap=$urlSupplementCap",
                    )
                    merged.putAll(
                        urlTestTopCandidates(missing, urlConcurrency, session) { done, total ->
                            setSimpleModeActivity("Testing URL $done/$total")
                        },
                    )
                }
                urlTestDelays = merged
            }
        } else {
            quickProbePings = emptyMap()
            val preUrlSorted = connectPool.sortedWith(
                compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
                    .thenBy { if (quickProbePings.containsKey(it.id)) 0 else 1 }
                    .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                    .thenBy { statusRank(it.status) }
                    .thenBy { pingRank(it.ping) }
                    .thenByDescending { throughputRank(it) },
            )
            val baseUrlTest = buildStratifiedUrlPool(
                proxies = preUrlSorted,
                cap = urlTestCap,
                priorityFirstIds = priorityFirstIds,
                builtinProfileIds = builtinFourIds,
                whitelistBuiltinOnly = whitelistBuiltinOnly,
            )
            urlTestCandidates = baseUrlTest
            urlTestDelays = if (urlTestCandidates.isNotEmpty()) {
                setSimpleModeActivity("Testing URL 0/${urlTestCandidates.size}")
                simpleModeLog(
                    "SimpleMode",
                    "H17 urltest_started candidates=${urlTestCandidates.size} baseCap=$urlTestCap extraTcp=$extraUrlTestByTcp mode=sequential",
                )
                urlTestTopCandidates(urlTestCandidates, urlConcurrency, session) { done, total ->
                    setSimpleModeActivity("Testing URL $done/$total")
                }
            } else {
                emptyMap()
            }
        }
        if (DataStore.probe2kPersistenceEnabled && (quickProbePings.isNotEmpty() || urlTestDelays.isNotEmpty())) {
            ProxyProbeStateStore.persistPrepareResults(
                proxies = connectPool,
                builtinProfileIds = builtinFourIds,
                tcpMs = quickProbePings,
                urlMs = urlTestDelays,
            )
            ProxyProbeStateStore.logPoolSnapshot("prepare")
        }
        if (urlTestCandidates.isNotEmpty()) {
            val urlOk = urlTestDelays.size
            val urlHead = urlTestDelays.entries
                .sortedBy { it.value }
                .take(5)
                .joinToString(";") { "${it.key}:${it.value}" }
            simpleModeLog(
                "SimpleMode",
                "H17 urltest_done success=$urlOk tested=${urlTestCandidates.size} best=$urlHead",
            )
        }

        val tcpPoolFullyTested = !compactTcpProbe ||
            (shouldQuickProbe && tcpTestedCount >= connectPool.size)
        val allProbesDead = shouldQuickProbe &&
            quickProbePings.isEmpty() &&
            urlTestDelays.isEmpty() &&
            tcpPoolFullyTested
        if (allProbesDead) {
            simpleModeLog(
                "SimpleMode",
                "H22 prepare_all_probes_dead count=${connectPool.size} testedTcp=$tcpTestedCount " +
                    "jailed=$jailedCount whitelistDual=$whitelistBuiltinOnly",
            )
            simpleModeDebugEvent(
                runId = "run1",
                hypothesisId = "H22",
                location = "AutoServerSelector.kt:prepareForConnect",
                message = "all tcp and url probes failed",
                data = mapOf("count" to connectPool.size.toString()),
            )
            return PrepareForConnectResult.AllProbesDead
        }

        val ranked = connectPool
            .sortedWith(
                if (quickProbePings.isNotEmpty()) {
                    // Prefer low composite: real URL latency wins over TCP+synthetic when URL ran.
                    compareBy<ProxyEntity> { if (isInFailureCooldown(it.id)) 1 else 0 }
                        .thenBy { warmProbeStateRank(probeStates, it.id) }
                        .thenBy { compositeSelectionScore(it, urlTestDelays, quickProbePings, probeStates) }
                        .thenBy { statusRank(it.status) }
                        .thenBy { pingRank(it.ping) }
                        .thenByDescending { throughputRank(it) }
                        .thenBy { urlTestDelays[it.id] ?: Int.MAX_VALUE }
                        .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .thenByDescending { it.id == DataStore.autoSelectLastKnownGood }
                        .thenBy { if (it.id in priorityFirstIds) 0 else 1 }
                        .thenBy {
                            BuiltinPoolPolicy.openNetSelectionRank(it.id, builtinFourIds, whitelistBuiltinOnly)
                        }
                        .thenBy { it.userOrder }
                } else {
                    compareBy<ProxyEntity> { if (isInFailureCooldown(it.id)) 1 else 0 }
                        .thenBy { warmProbeStateRank(probeStates, it.id) }
                        .thenBy { if (urlTestDelays.containsKey(it.id)) 0 else 1 }
                        .thenBy { urlTestDelays[it.id] ?: ProxyProbeStateStore.persistedDelayScore(probeStates[it.id]) }
                        .thenByDescending { throughputRank(it) }
                        .thenBy { if (quickProbePings.containsKey(it.id)) 0 else 1 }
                        .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .thenBy { statusRank(it.status) }
                        .thenBy { pingRank(it.ping) }
                        .thenByDescending { it.id == DataStore.autoSelectLastKnownGood }
                        .thenBy { if (it.id in priorityFirstIds) 0 else 1 }
                        .thenBy {
                            BuiltinPoolPolicy.openNetSelectionRank(it.id, builtinFourIds, whitelistBuiltinOnly)
                        }
                        .thenBy { it.userOrder }
                },
            )
            .map { it.id }
        val rankedWithQuota = BuiltinFallbackQuota.apply(
            rankedIds = ranked,
            builtinProfileIds = builtinFourIds,
        )
        if (rankedWithQuota != ranked) {
            simpleModeLog(
                "SimpleMode",
                "H35 builtin_fallback_cap before=${ranked.size} after=${rankedWithQuota.size}",
            )
        }
        val rankedFinal = rankedWithQuota
        val quickProbeAlive = quickProbePings.size
        if (initialCount == connectPool.size) {
            // #region agent log
            simpleModeDebugEvent(
                runId = "run2",
                hypothesisId = "H1",
                location = "AutoServerSelector.kt:prepareForConnect",
                message = "all profiles untested, fallback to deterministic order",
                data = mapOf(
                    "count" to connectPool.size.toString(),
                    "selectedBefore" to selectedBefore.toString(),
                ),
            )
            // #endregion
            simpleModeLog(
                "SimpleMode",
                "H1 all_initial count=${connectPool.size} selectedBefore=$selectedBefore",
            )
        }
        val rankedHead = rankedFinal.take(5).joinToString(";") { id ->
            val proxy = connectPool.first { it.id == id }
            val ut = urlTestDelays[id]?.toString() ?: "-"
            val qp = quickProbePings[id]?.toString() ?: "-"
            val co = if (quickProbePings.isNotEmpty()) {
                compositeSelectionScore(proxy, urlTestDelays, quickProbePings, probeStates).toString()
            } else {
                "-"
            }
            "${proxy.id}|co=$co|url=$ut|tcp=$qp|st=${proxy.status}|ping=${proxy.ping}|tp=${throughputRank(proxy)}"
        }

        if (quickProbePings.isNotEmpty()) {
            val h20 = rankedFinal.take(8).joinToString(";") { id ->
                val proxy = connectPool.first { it.id == id }
                val co = compositeSelectionScore(proxy, urlTestDelays, quickProbePings, probeStates)
                "${proxy.id}:$co"
            }
            simpleModeLog(
                "SimpleMode",
                "H20 rank_composite_head=$h20 urlCandidates=${urlTestCandidates.size}",
            )
        }

        DataStore.autoSelectFallbackQueue = rankedFinal.joinToString(",")
        DataStore.autoSelectFallbackIndex = 0
        val best = rankedFinal.first()
        setSimpleModeActivity("Ranking ${rankedFinal.size} servers…")
        if (selectedBefore != best) {
            Logs.d("AutoSelect: switch selected profile $selectedBefore -> $best")
            DataStore.selectedProxy = best
        }
        // #region agent log
        simpleModeDebugEvent(
            runId = "run1",
            hypothesisId = "H4",
            location = "AutoServerSelector.kt:prepareForConnect",
            message = "prepared fallback queue",
            data = mapOf(
                "selectedBefore" to selectedBefore.toString(),
                "best" to best.toString(),
                "queueSize" to rankedFinal.size.toString(),
                "groupCount" to connectPool.map { it.groupId }.toSet().size.toString(),
                "availableCount" to availableCount.toString(),
                "initialCount" to initialCount.toString(),
                "badCount" to badCount.toString(),
                "probeAlive" to quickProbeAlive.toString(),
                "urlTestOk" to urlTestDelays.size.toString(),
                "bestPing" to (connectPool.firstOrNull { it.id == best }?.ping?.toString() ?: "0"),
                "rankedHead" to rankedHead,
            ),
        )
        // #endregion
        simpleModeLog(
            "SimpleMode",
            "H4 queue_prepared before=$selectedBefore best=$best size=${rankedFinal.size} avail=$availableCount initial=$initialCount bad=$badCount probeAlive=$quickProbeAlive urlOk=${urlTestDelays.size} head=$rankedHead",
        )
        if (shouldQuickProbe && !effectiveHandoff) {
            AutoServerSelectorProbePolicy.recordFullProbe(proxies, whitelistBuiltinOnly)
        }
        recordPrepareSelectionReason(
            initialCount = initialCount,
            proxyCount = connectPool.size,
            quickProbeAlive = quickProbeAlive,
            urlTestOk = urlTestDelays.size,
            effectiveHandoff = effectiveHandoff,
            forceFullProbeReason = forceFullProbeReason,
        )
        return PrepareForConnectResult.Success(best)
    }

    private fun recordPrepareSelectionReason(
        initialCount: Int,
        proxyCount: Int,
        quickProbeAlive: Int,
        urlTestOk: Int,
        effectiveHandoff: Boolean,
        forceFullProbeReason: String?,
    ) {
        val reason = when {
            initialCount == proxyCount -> "all_initial"
            effectiveHandoff -> "handoff_probe"
            forceFullProbeReason != null -> "full_probe:$forceFullProbeReason"
            quickProbeAlive == 0 && urlTestOk == 0 && DataStore.probe2kWarmRankingEnabled -> "warm_persisted_only"
            quickProbeAlive == 0 && urlTestOk == 0 -> "heuristic_only"
            else -> "live_probe_rank"
        }
        ProxyProbeStateStore.recordSelectionReason(reason)
    }

    private fun buildHandoffPriorityIds(selectedBefore: Long): Set<Long> {
        val ids = LinkedHashSet<Long>()
        if (selectedBefore > 0L) ids += selectedBefore
        val lastGood = DataStore.autoSelectLastKnownGood
        if (lastGood > 0L) ids += lastGood
        DataStore.autoSelectFallbackQueue
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .take(12)
            .forEach { ids += it }
        return ids
    }

    fun tryMoveToFallback(currentId: Long): Long? {
        val queue = DataStore.autoSelectFallbackQueue
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
        if (queue.isEmpty()) {
            // #region agent log
            simpleModeDebugEvent(
                runId = "run1",
                hypothesisId = "H1",
                location = "AutoServerSelector.kt:tryMoveToFallback",
                message = "fallback queue empty",
                data = mapOf("currentId" to currentId.toString()),
            )
            // #endregion
            simpleModeLog("SimpleMode", "H1 fallback_queue_empty currentId=$currentId")
            return null
        }
        val probeStates = if (DataStore.probe2kPersistenceEnabled) {
            runBlocking { ProxyProbeStateStore.loadMap(queue) }
        } else {
            emptyMap()
        }

        val currentIndex = queue.indexOf(currentId).takeIf { it >= 0 } ?: DataStore.autoSelectFallbackIndex
        val startIndex = currentIndex + 1
        var nextIndex = startIndex
        var next = -1L
        while (nextIndex < queue.size) {
            val candidate = queue[nextIndex]
            if (ProbePoolEligibility.isSelectableForConnect(probeStates[candidate])) {
                next = candidate
                break
            }
            nextIndex++
        }
        if (next < 0L) {
            // #region agent log
            simpleModeDebugEvent(
                runId = "run1",
                hypothesisId = "H1",
                location = "AutoServerSelector.kt:tryMoveToFallback",
                message = "fallback exhausted",
                data = mapOf(
                    "currentId" to currentId.toString(),
                    "currentIndex" to currentIndex.toString(),
                    "queueSize" to queue.size.toString(),
                ),
            )
            // #endregion
            simpleModeLog(
                "SimpleMode",
                "H1 fallback_exhausted currentId=$currentId currentIndex=$currentIndex size=${queue.size}",
            )
            return null
        }

        DataStore.autoSelectFallbackIndex = nextIndex
        DataStore.selectedProxy = next
        setSimpleModeActivity("Trying next server ${nextIndex + 1}/${queue.size}")
        Logs.w("AutoSelect fallback: move to profile $next")
        // #region agent log
        simpleModeDebugEvent(
            runId = "run1",
            hypothesisId = "H1",
            location = "AutoServerSelector.kt:tryMoveToFallback",
            message = "fallback moved",
            data = mapOf(
                "currentId" to currentId.toString(),
                "nextId" to next.toString(),
                "nextIndex" to nextIndex.toString(),
                "queueSize" to queue.size.toString(),
            ),
        )
        // #endregion
        simpleModeLog(
            "SimpleMode",
            "H1 fallback_moved currentId=$currentId nextId=$next nextIndex=$nextIndex size=${queue.size}",
        )
        return next
    }

    fun markConnected(profileId: Long) {
        DataStore.autoSelectLastKnownGood = profileId
        recentProbeFailures.remove(profileId)
        AutoServerSelectorProbePolicy.recordPostConnectUrlVerified(profileId)
        AutoServerSelectorSessionFallback.syncIndexForConnected(profileId)
        if (DataStore.probe2kPersistenceEnabled) {
            runBlocking { ProxyProbeStateStore.recordConnected(profileId) }
        }
    }

    fun recordProbeFailure(profileId: Long, skipReason: String? = null) {
        if (profileId <= 0L) return
        if (skipReason != null) {
            simpleModeLog(
                "SimpleMode",
                "H32 probe_failure_skipped profileId=$profileId reason=$skipReason",
            )
            return
        }
        recentProbeFailures[profileId] = System.currentTimeMillis()
        simpleModeLog("SimpleMode", "H32 probe_failure_recorded profileId=$profileId")
        if (DataStore.probe2kPersistenceEnabled) {
            runBlocking { ProxyProbeStateStore.recordFailure(profileId) }
        }
    }

    private fun warmProbeStateRank(probeStates: Map<Long, ProxyProbeState>, profileId: Long): Int {
        if (!DataStore.probe2kWarmRankingEnabled) return 0
        return ProxyProbeStateStore.probeStateRank(probeStates[profileId])
    }

    private fun isInFailureCooldown(profileId: Long): Boolean {
        val failedAt = recentProbeFailures[profileId] ?: return false
        if (System.currentTimeMillis() - failedAt >= PROFILE_FAILURE_COOLDOWN_MS) {
            recentProbeFailures.remove(profileId)
            return false
        }
        return true
    }

    private suspend fun tryLastKnownGoodFastPath(
        proxies: List<ProxyEntity>,
        priorityFirstIds: Set<Long>,
        session: PrepareSession,
        selectedBefore: Long,
        builtinProfileIds: Set<Long>,
        whitelistBuiltinOnly: Boolean,
    ): Long? {
        val goodId = DataStore.autoSelectLastKnownGood
        if (goodId <= 0L || !AutoServerSelectorProbePolicy.isLastKnownGoodUrlFresh(goodId)) {
            return null
        }
        val good = proxies.find { it.id == goodId } ?: return null
        if (isInFailureCooldown(goodId)) return null
        ensurePrepareCurrent(session)
        setSimpleModeActivity("Verifying last server…")
        val lkgDelay = DirectProfileUrlProbe.urlTestDelay(good) ?: return null
        if (lkgDelay <= 0) return null
        ensurePrepareCurrent(session)
        val urlPool = buildStratifiedUrlPool(
            proxies = listOf(good) + proxies.filter { it.id != goodId },
            cap = 12,
            priorityFirstIds = priorityFirstIds + goodId,
            builtinProfileIds = builtinProfileIds,
            whitelistBuiltinOnly = whitelistBuiltinOnly,
        )
        val urlDelays = if (urlPool.size <= 1) {
            mapOf(goodId to lkgDelay)
        } else {
            urlTestTopCandidates(urlPool, probeConcurrency(false), session)
        }
        if (urlDelays[goodId] == null && urlDelays.isNotEmpty()) {
            val alt = urlDelays.minBy { it.value }.key
            simpleModeLog("SimpleMode", "H26 lkg_fast_path_fallback alt=$alt good=$goodId")
            return finalizeRankedSelection(
                proxies = proxies,
                priorityFirstIds = priorityFirstIds,
                selectedBefore = selectedBefore,
                quickProbePings = emptyMap(),
                urlTestDelays = urlDelays,
                preferId = alt,
                builtinProfileIds = builtinProfileIds,
                whitelistBuiltinOnly = whitelistBuiltinOnly,
            )
        }
        if (urlDelays[goodId] == null) return null
        return finalizeRankedSelection(
            proxies = proxies,
            priorityFirstIds = priorityFirstIds,
            selectedBefore = selectedBefore,
            quickProbePings = emptyMap(),
            urlTestDelays = urlDelays,
            preferId = goodId,
            builtinProfileIds = builtinProfileIds,
            whitelistBuiltinOnly = whitelistBuiltinOnly,
        )
    }

    private fun finalizeRankedSelection(
        proxies: List<ProxyEntity>,
        priorityFirstIds: Set<Long>,
        selectedBefore: Long,
        quickProbePings: Map<Long, Int>,
        urlTestDelays: Map<Long, Int>,
        preferId: Long? = null,
        builtinProfileIds: Set<Long> = emptySet(),
        whitelistBuiltinOnly: Boolean = false,
    ): Long {
        val ranked = proxies.sortedWith(
            compareBy<ProxyEntity> { if (it.id == preferId) 0 else 1 }
                .thenBy { if (isInFailureCooldown(it.id)) 1 else 0 }
                .thenBy { compositeSelectionScore(it, urlTestDelays, quickProbePings) }
                .thenBy { statusRank(it.status) }
                .thenBy { pingRank(it.ping) }
                .thenByDescending { throughputRank(it) }
                .thenByDescending { it.id == DataStore.autoSelectLastKnownGood }
                .thenBy { if (it.id in priorityFirstIds) 0 else 1 }
                .thenBy {
                    BuiltinPoolPolicy.openNetSelectionRank(it.id, builtinProfileIds, whitelistBuiltinOnly)
                }
                .thenBy { it.userOrder },
        ).map { it.id }
        DataStore.autoSelectFallbackQueue = ranked.joinToString(",")
        DataStore.autoSelectFallbackIndex = 0
        val best = ranked.first()
        if (selectedBefore != best) {
            DataStore.selectedProxy = best
        }
        return best
    }

    /**
     * Picks URL-test candidates round-robin across subscription groups so one group
     * does not monopolize the probe budget.
     */
    private fun buildStratifiedUrlPool(
        proxies: List<ProxyEntity>,
        cap: Int,
        priorityFirstIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState> = emptyMap(),
        builtinProfileIds: Set<Long> = emptySet(),
        whitelistBuiltinOnly: Boolean = false,
    ): List<ProxyEntity> {
        if (proxies.isEmpty() || cap <= 0) return emptyList()
        val byGroup = proxies.groupBy { it.groupId }
        val groupQueues = byGroup.mapValues { (_, list) ->
            list.sortedWith(
                heuristicPreTcpOrder(
                    priorityFirstIds = priorityFirstIds,
                    probeStates = probeStates,
                    builtinProfileIds = builtinProfileIds,
                    whitelistBuiltinOnly = whitelistBuiltinOnly,
                ),
            ).toMutableList()
        }
        val groupOrder = groupQueues.keys.sorted()
        val picked = ArrayList<ProxyEntity>(cap.coerceAtMost(proxies.size))
        val used = HashSet<Long>()
        var madeProgress = true
        while (picked.size < cap && madeProgress) {
            madeProgress = false
            for (gid in groupOrder) {
                if (picked.size >= cap) break
                val queue = groupQueues.getValue(gid)
                while (queue.isNotEmpty() && queue.first().id in used) {
                    queue.removeAt(0)
                }
                val next = queue.firstOrNull() ?: continue
                queue.removeAt(0)
                picked += next
                used += next.id
                madeProgress = true
            }
        }
        return picked
    }

    private fun statusRank(status: Int): Int = when (status) {
        ProxyEntity.STATUS_AVAILABLE -> 0
        ProxyEntity.STATUS_INITIAL -> 1
        ProxyEntity.STATUS_UNREACHABLE -> 2
        ProxyEntity.STATUS_UNAVAILABLE,
        ProxyEntity.STATUS_INVALID,
            -> 3

        else -> 4
    }

    private fun pingRank(ping: Int): Int {
        return if (ping > 0) ping else Int.MAX_VALUE
    }

    private fun throughputRank(proxy: ProxyEntity): Long {
        return proxy.rx + proxy.tx
    }

    /**
     * Lower is better. If we have a real URL-test latency, it dominates (user traffic path);
     * otherwise fall back to TCP + synthetic URL so fast RST ports do not beat untested nodes.
     */
    private fun compositeSelectionScore(
        proxy: ProxyEntity,
        urlTestDelays: Map<Long, Int>,
        quickProbePings: Map<Long, Int>,
        probeStates: Map<Long, ProxyProbeState> = emptyMap(),
    ): Int {
        val tcp = quickProbePings[proxy.id]?.takeIf { it > 0 }
        val url = urlTestDelays[proxy.id]?.takeIf { it > 0 }
        val live = when {
            url != null -> url
            tcp != null -> {
                val syntheticUrl = (tcp * 3).coerceIn(40, 900)
                10 * tcp + syntheticUrl
            }
            else -> null
        }
        if (live != null) return live
        if (!DataStore.probe2kWarmRankingEnabled) return Int.MAX_VALUE / 4
        return ProxyProbeStateStore.persistedDelayScore(probeStates[proxy.id])
    }

    /**
     * Subscription nodes for whitelist-restricted networks (e.g. Aetris «White lists …»).
     * Not used for normal (Google-OK) auto-select; prioritized with built-in helpers on wl-only net.
     */
    private fun ProxyEntity.isSubscriptionWhitelistMarked(): Boolean {
        val n = displayName().lowercase()
        return n.contains("white lists") || n.contains("white list") || n.contains("whitelist")
    }

    /** Order used to start URL tests in parallel with TCP (no TCP results yet). */
    private fun heuristicPreTcpOrder(
        priorityFirstIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState> = emptyMap(),
        builtinProfileIds: Set<Long> = emptySet(),
        whitelistBuiltinOnly: Boolean = false,
    ): Comparator<ProxyEntity> =
        compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
            .thenBy { warmProbeStateRank(probeStates, it.id) }
            .thenBy { statusRank(it.status) }
            .thenBy { pingRank(it.ping) }
            .thenByDescending { throughputRank(it) }
            .thenBy {
                BuiltinPoolPolicy.openNetSelectionRank(it.id, builtinProfileIds, whitelistBuiltinOnly)
            }
            .thenBy { it.userOrder }

    private suspend fun urlTestTopCandidates(
        candidates: List<ProxyEntity>,
        concurrency: Int,
        session: PrepareSession,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<Long, Int> = coroutineScope {
        val total = candidates.size
        if (total == 0) return@coroutineScope emptyMap()
        val semaphore = Semaphore(concurrency)
        val result = HashMap<Long, Int>()
        val done = AtomicInteger(0)
        var lastReported = 0
        fun reportProgress() {
            val count = done.incrementAndGet()
            if (count == total || count - lastReported >= 1) {
                lastReported = count
                onProgress(count, total)
            }
        }
        onProgress(0, total)
        candidates.map { proxy ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (!isPrepareCurrent(session) || !currentCoroutineContext().isActive) {
                        return@withPermit
                    }
                    try {
                        val ms = DirectProfileUrlProbe.urlTestDelay(proxy)
                        if (ms != null && ms > 0) {
                            synchronized(result) {
                                result[proxy.id] = ms
                            }
                        }
                    } finally {
                        reportProgress()
                    }
                }
            }
        }.awaitAll()
        result.toMap()
    }

    private data class TcpProbeBatchResult(
        val pings: Map<Long, Int>,
        val testedCount: Int,
    )

    /**
     * When the first TCP batch finds no alive nodes, keep probing the rest of the pool in
     * further batches (same batch size, warm-state order) until something responds or every
     * selectable profile was tested once.
     */
    private suspend fun probeTcpInBatches(
        connectPool: List<ProxyEntity>,
        probePoolOrdered: List<ProxyEntity>,
        priorityFirstIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        tcpBatchCap: Int,
        compactTcpProbe: Boolean,
        tcpConcurrency: Int,
        session: PrepareSession,
        onProgress: (round: Int, doneInRound: Int, totalInRound: Int, cumulativeTested: Int, poolSize: Int) -> Unit,
    ): TcpProbeBatchResult {
        if (!compactTcpProbe) {
            val pings = quickTcpProbe(probePoolOrdered, tcpConcurrency, session) { done, total ->
                onProgress(1, done, total, done, connectPool.size)
            }
            return TcpProbeBatchResult(pings, probePoolOrdered.size)
        }
        val merged = LinkedHashMap<Long, Int>()
        val testedIds = LinkedHashSet<Long>()
        val maxRounds = ((connectPool.size + tcpBatchCap - 1) / tcpBatchCap).coerceIn(1, TCP_PROBE_MAX_ROUNDS)
        for (round in 1..maxRounds) {
            ensurePrepareCurrent(session)
            val remaining = connectPool.filter { it.id !in testedIds }
            if (remaining.isEmpty()) break
            val batch = if (round == 1) {
                buildCompactTcpProbePool(probePoolOrdered, priorityFirstIds, maxTotal = tcpBatchCap)
            } else {
                ProbeScheduler.prioritizeTcpTargets(remaining, probeStates, priorityFirstIds)
                    .take(tcpBatchCap)
            }
            if (batch.isEmpty()) break
            batch.forEach { testedIds.add(it.id) }
            simpleModeLog(
                "SimpleMode",
                "H14 tcp_probe_round round=$round batch=${batch.size} cumulative=${testedIds.size} " +
                    "pool=${connectPool.size} aliveSoFar=${merged.size}",
            )
            val roundPings = quickTcpProbe(batch, tcpConcurrency, session) { done, total ->
                onProgress(round, done, total, testedIds.size, connectPool.size)
            }
            merged.putAll(roundPings)
            if (merged.isNotEmpty()) break
            if (testedIds.size >= connectPool.size) break
        }
        return TcpProbeBatchResult(merged, testedIds.size)
    }

    private suspend fun quickTcpProbe(
        proxies: List<ProxyEntity>,
        concurrency: Int,
        session: PrepareSession,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<Long, Int> = coroutineScope {
        ProfileTcpProber.probeTcpBatch(
            proxies = proxies,
            concurrency = concurrency,
            timeoutMs = 1200,
            isActive = { isPrepareCurrent(session) },
            onProgress = onProgress,
        )
    }
}
