package fr.husi.database

import fr.husi.bootstrap.WhitelistBuiltinBootstrap
import fr.husi.bg.BackendState
import fr.husi.bg.GuardedProcessPool
import fr.husi.bg.ServiceState
import fr.husi.bg.initPlugins
import fr.husi.bg.launchPlugins
import fr.husi.fmt.buildConfig
import fr.husi.ktx.Logs
import fr.husi.ktx.readableMessage
import fr.husi.libcore.Client
import fr.husi.libcore.Libcore
import fr.husi.plugin.PluginNotFoundException
import fr.husi.utils.closeQuietly
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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

    @Volatile
    private var probeUiActive = false

    private val prepareMutex = Mutex()
    private val prepareGeneration = AtomicInteger(0)

    /** Abort an in-flight [prepareForConnect] (e.g. superseded by user connect or handoff). */
    fun cancelInFlightPrepare() {
        prepareGeneration.incrementAndGet()
        simpleModeLog("SimpleMode", "H31 prepare_cancel_requested")
    }

    private fun ensurePrepareCurrent(generation: Int) {
        if (generation != prepareGeneration.get()) {
            throw CancellationException("prepareForConnect superseded")
        }
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

    suspend fun prepareForConnect(networkHandoff: Boolean = false): PrepareForConnectResult {
        val generation = prepareGeneration.incrementAndGet()
        return prepareMutex.withLock {
            probeUiActive = true
            try {
                ensurePrepareCurrent(generation)
                val result = prepareForConnectLocked(generation, networkHandoff)
                ensurePrepareCurrent(generation)
                result
            } catch (e: CancellationException) {
                simpleModeLog("SimpleMode", "H31 prepare_aborted gen=$generation")
                throw e
            } finally {
                probeUiActive = false
            }
        }
    }

    private suspend fun prepareForConnectLocked(
        generation: Int,
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

        val availableCount = proxies.count { it.status == ProxyEntity.STATUS_AVAILABLE }
        val initialCount = proxies.count { it.status == ProxyEntity.STATUS_INITIAL }
        val badCount = proxies.count {
            it.status == ProxyEntity.STATUS_UNREACHABLE ||
                it.status == ProxyEntity.STATUS_UNAVAILABLE ||
                it.status == ProxyEntity.STATUS_INVALID
        }
        val forceFullProbeReason = AutoServerSelectorProbePolicy.forceFullProbeReason(
            proxies = proxies,
            whitelistBuiltinOnly = whitelistBuiltinOnly,
            networkHandoff = networkHandoff,
        )
        val shouldQuickProbe = networkHandoff ||
            initialCount == proxies.size ||
            availableCount == 0 ||
            forceFullProbeReason != null
        if (forceFullProbeReason != null) {
            simpleModeLog(
                "SimpleMode",
                "H25 full_probe_forced reason=$forceFullProbeReason handoff=$networkHandoff " +
                    "initial=$initialCount avail=$availableCount",
            )
        } else if (networkHandoff) {
            simpleModeLog(
                "SimpleMode",
                "H33 handoff_probe_compact initial=$initialCount avail=$availableCount",
            )
        }
        val urlTestCap = if (networkHandoff) {
            12
        } else {
            (probeConcurrency(whitelistBuiltinOnly) * 2).coerceIn(12, 32)
        }
        val extraUrlTestByTcp = if (networkHandoff) 4 else 8
        val parallelUrlPoolSize = (urlTestCap + extraUrlTestByTcp).coerceAtMost(proxies.size)
        val urlSupplementCap = if (networkHandoff) 6 else 10
        val compactTcpProbe = whitelistBuiltinOnly || networkHandoff
        val tcpProbeTargets = if (compactTcpProbe) {
            buildCompactTcpProbePool(proxies, priorityFirstIds, maxTotal = 128)
        } else {
            proxies
        }
        ensurePrepareCurrent(generation)
        val urlConcurrency = probeConcurrency(whitelistBuiltinOnly)
        val tcpConcurrency = tcpProbeConcurrency(whitelistBuiltinOnly)

        var quickProbePings: Map<Long, Int> = emptyMap()
        var urlTestDelays: Map<Long, Int> = emptyMap()
        var urlTestCandidates: List<ProxyEntity> = emptyList()

        if (shouldQuickProbe) {
            setSimpleModeActivity("Testing TCP 0/${tcpProbeTargets.size}")
            val parallelUrlPool = buildStratifiedUrlPool(
                proxies = proxies,
                cap = parallelUrlPoolSize,
                priorityFirstIds = priorityFirstIds,
            )
            simpleModeLog(
                "SimpleMode",
                "H14 quick_probe_started tcp=${tcpProbeTargets.size} pool=${proxies.size} " +
                    "parallel_url_pool=${parallelUrlPool.size} compactTcp=$compactTcpProbe " +
                    "handoff=$networkHandoff tcpConc=$tcpConcurrency urlConc=$urlConcurrency",
            )
            coroutineScope {
                val tcpJob = async(Dispatchers.IO) {
                    quickTcpProbe(tcpProbeTargets, tcpConcurrency, generation) { done, total ->
                        setSimpleModeActivity("Testing TCP $done/$total")
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
                        urlTestTopCandidates(parallelUrlPool, urlConcurrency, generation) { done, total ->
                            setSimpleModeActivity("Testing URL $done/$total")
                        }
                    }
                }
                ensurePrepareCurrent(generation)
                quickProbePings = tcpJob.await()
                val quickProbeAlive = quickProbePings.size
                val quickProbeHead = quickProbePings.entries
                    .sortedBy { it.value }
                    .take(5)
                    .joinToString(";") { "${it.key}:${it.value}" }
                simpleModeLog(
                    "SimpleMode",
                    "H14 quick_probe_done alive=$quickProbeAlive tested=${tcpProbeTargets.size} best=$quickProbeHead",
                )
                ensurePrepareCurrent(generation)
                var merged = urlJob.await().toMutableMap()
                val preUrlSorted = proxies.sortedWith(
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
                )
                val baseIds = baseUrlTest.map { it.id }.toSet()
                val extraTcpForUrlTest = if (quickProbePings.isNotEmpty()) {
                    proxies
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
                        urlTestTopCandidates(missing, urlConcurrency, generation) { done, total ->
                            setSimpleModeActivity("Testing URL $done/$total")
                        },
                    )
                }
                urlTestDelays = merged
            }
        } else {
            quickProbePings = emptyMap()
            val preUrlSorted = proxies.sortedWith(
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
            )
            urlTestCandidates = baseUrlTest
            urlTestDelays = if (urlTestCandidates.isNotEmpty()) {
                setSimpleModeActivity("Testing URL 0/${urlTestCandidates.size}")
                simpleModeLog(
                    "SimpleMode",
                    "H17 urltest_started candidates=${urlTestCandidates.size} baseCap=$urlTestCap extraTcp=$extraUrlTestByTcp mode=sequential",
                )
                urlTestTopCandidates(urlTestCandidates, urlConcurrency, generation) { done, total ->
                    setSimpleModeActivity("Testing URL $done/$total")
                }
            } else {
                emptyMap()
            }
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

        val allProbesDead = shouldQuickProbe &&
            quickProbePings.isEmpty() &&
            urlTestDelays.isEmpty()
        if (allProbesDead) {
            simpleModeLog(
                "SimpleMode",
                "H22 prepare_all_probes_dead count=${proxies.size} whitelistDual=$whitelistBuiltinOnly",
            )
            simpleModeDebugEvent(
                runId = "run1",
                hypothesisId = "H22",
                location = "AutoServerSelector.kt:prepareForConnect",
                message = "all tcp and url probes failed",
                data = mapOf("count" to proxies.size.toString()),
            )
            return PrepareForConnectResult.AllProbesDead
        }

        val ranked = proxies
            .sortedWith(
                if (quickProbePings.isNotEmpty()) {
                    // Prefer low composite: real URL latency wins over TCP+synthetic when URL ran.
                    compareBy<ProxyEntity> { compositeSelectionScore(it, urlTestDelays, quickProbePings) }
                        .thenBy { statusRank(it.status) }
                        .thenBy { pingRank(it.ping) }
                        .thenByDescending { throughputRank(it) }
                        .thenBy { urlTestDelays[it.id] ?: Int.MAX_VALUE }
                        .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .thenByDescending { it.id == DataStore.autoSelectLastKnownGood }
                        .thenBy { if (it.id in priorityFirstIds) 0 else 1 }
                        .thenBy { it.userOrder }
                } else {
                    compareBy<ProxyEntity> { if (urlTestDelays.containsKey(it.id)) 0 else 1 }
                        .thenBy { urlTestDelays[it.id] ?: Int.MAX_VALUE }
                        .thenByDescending { throughputRank(it) }
                        .thenBy { if (quickProbePings.containsKey(it.id)) 0 else 1 }
                        .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .thenBy { statusRank(it.status) }
                        .thenBy { pingRank(it.ping) }
                        .thenByDescending { it.id == DataStore.autoSelectLastKnownGood }
                        .thenBy { if (it.id in priorityFirstIds) 0 else 1 }
                        .thenBy { it.userOrder }
                },
            )
            .map { it.id }
        val quickProbeAlive = quickProbePings.size
        if (initialCount == proxies.size) {
            // #region agent log
            simpleModeDebugEvent(
                runId = "run2",
                hypothesisId = "H1",
                location = "AutoServerSelector.kt:prepareForConnect",
                message = "all profiles untested, fallback to deterministic order",
                data = mapOf(
                    "count" to proxies.size.toString(),
                    "selectedBefore" to selectedBefore.toString(),
                ),
            )
            // #endregion
            simpleModeLog(
                "SimpleMode",
                "H1 all_initial count=${proxies.size} selectedBefore=$selectedBefore",
            )
        }
        val rankedHead = ranked.take(5).joinToString(";") { id ->
            val proxy = proxies.first { it.id == id }
            val ut = urlTestDelays[id]?.toString() ?: "-"
            val qp = quickProbePings[id]?.toString() ?: "-"
            val co = if (quickProbePings.isNotEmpty()) {
                compositeSelectionScore(proxy, urlTestDelays, quickProbePings).toString()
            } else {
                "-"
            }
            "${proxy.id}|co=$co|url=$ut|tcp=$qp|st=${proxy.status}|ping=${proxy.ping}|tp=${throughputRank(proxy)}"
        }

        if (quickProbePings.isNotEmpty()) {
            val h20 = ranked.take(8).joinToString(";") { id ->
                val proxy = proxies.first { it.id == id }
                val co = compositeSelectionScore(proxy, urlTestDelays, quickProbePings)
                "${proxy.id}:$co"
            }
            simpleModeLog(
                "SimpleMode",
                "H20 rank_composite_head=$h20 urlCandidates=${urlTestCandidates.size}",
            )
        }

        DataStore.autoSelectFallbackQueue = ranked.joinToString(",")
        DataStore.autoSelectFallbackIndex = 0
        val best = ranked.first()
        setSimpleModeActivity("Ranking ${ranked.size} servers…")
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
                "queueSize" to ranked.size.toString(),
                "groupCount" to proxies.map { it.groupId }.toSet().size.toString(),
                "availableCount" to availableCount.toString(),
                "initialCount" to initialCount.toString(),
                "badCount" to badCount.toString(),
                "probeAlive" to quickProbeAlive.toString(),
                "urlTestOk" to urlTestDelays.size.toString(),
                "bestPing" to (proxies.firstOrNull { it.id == best }?.ping?.toString() ?: "0"),
                "rankedHead" to rankedHead,
            ),
        )
        // #endregion
        simpleModeLog(
            "SimpleMode",
            "H4 queue_prepared before=$selectedBefore best=$best size=${ranked.size} avail=$availableCount initial=$initialCount bad=$badCount probeAlive=$quickProbeAlive urlOk=${urlTestDelays.size} head=$rankedHead",
        )
        if (shouldQuickProbe && !networkHandoff) {
            AutoServerSelectorProbePolicy.recordFullProbe(proxies, whitelistBuiltinOnly)
        }
        return PrepareForConnectResult.Success(best)
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

        val currentIndex = queue.indexOf(currentId).takeIf { it >= 0 } ?: DataStore.autoSelectFallbackIndex
        val nextIndex = currentIndex + 1
        if (nextIndex !in queue.indices) {
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

        val next = queue[nextIndex]
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
        AutoServerSelectorProbePolicy.recordPostConnectUrlVerified(profileId)
        DataStore.autoSelectFallbackQueue = ""
        DataStore.autoSelectFallbackIndex = 0
    }

    /**
     * Picks URL-test candidates round-robin across subscription groups so one group
     * does not monopolize the probe budget.
     */
    private fun buildStratifiedUrlPool(
        proxies: List<ProxyEntity>,
        cap: Int,
        priorityFirstIds: Set<Long>,
    ): List<ProxyEntity> {
        if (proxies.isEmpty() || cap <= 0) return emptyList()
        val byGroup = proxies.groupBy { it.groupId }
        val groupQueues = byGroup.mapValues { (_, list) ->
            list.sortedWith(heuristicPreTcpOrder(priorityFirstIds)).toMutableList()
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
    ): Int {
        val tcp = quickProbePings[proxy.id]?.takeIf { it > 0 }
        val url = urlTestDelays[proxy.id]?.takeIf { it > 0 }
        return when {
            url != null -> url
            tcp != null -> {
                val syntheticUrl = (tcp * 3).coerceIn(40, 900)
                10 * tcp + syntheticUrl
            }
            else -> Int.MAX_VALUE / 4
        }
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
    private fun heuristicPreTcpOrder(priorityFirstIds: Set<Long>): Comparator<ProxyEntity> =
        compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
            .thenBy { statusRank(it.status) }
            .thenBy { pingRank(it.ping) }
            .thenByDescending { throughputRank(it) }
            .thenBy { it.userOrder }

    private suspend fun urlTestTopCandidates(
        candidates: List<ProxyEntity>,
        concurrency: Int,
        generation: Int,
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
                    if (generation != prepareGeneration.get() || !currentCoroutineContext().isActive) {
                        return@withPermit
                    }
                    try {
                        val ms = profileUrlTestDelay(proxy)
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

    private suspend fun profileUrlTestDelay(profile: ProxyEntity): Int? = coroutineScope {
        var client: Client? = null
        var processes: GuardedProcessPool? = null
        val cacheFiles = ArrayList<File>()
        var out: Int? = null
        try {
            client = Libcore.newClient(null)
            val config = buildConfig(profile, forTest = true)
            if (config.externalIndex.any { it.chain.isNotEmpty() }) {
                val pluginConfigs = initPlugins(config, false, cacheFiles)
                processes = GuardedProcessPool { Logs.w(it) }
                launchPlugins(config, pluginConfigs, processes, cacheFiles)
                delay(500L)
            }
            val ms = client.newInstanceURLTest(
                config.config,
                "",
                DataStore.connectionTestURL,
                DataStore.connectionTestTimeout,
            )
            if (ms > 0) {
                out = ms
            }
        } catch (e: PluginNotFoundException) {
            Logs.w("AutoSelect urlTest plugin: ${e.plugin}")
        } catch (e: Exception) {
            Logs.d("AutoSelect urlTest ${profile.displayName()}: ${e.readableMessage}")
        } finally {
            client?.closeQuietly()
            processes?.close(this@coroutineScope)
            cacheFiles.forEach { runCatching { it.delete() } }
        }
        out
    }

    private suspend fun quickTcpProbe(
        proxies: List<ProxyEntity>,
        concurrency: Int,
        generation: Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<Long, Int> = coroutineScope {
        val total = proxies.size
        if (total == 0) return@coroutineScope emptyMap()
        val semaphore = Semaphore(concurrency)
        val result = HashMap<Long, Int>()
        val done = AtomicInteger(0)
        var lastReported = 0
        fun reportProgress() {
            val count = done.incrementAndGet()
            if (count == total || count - lastReported >= 8) {
                lastReported = count
                onProgress(count, total)
            }
        }
        onProgress(0, total)
        proxies.map { proxy ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (generation != prepareGeneration.get() || !currentCoroutineContext().isActive) {
                        return@withPermit
                    }
                    try {
                        val bean = runCatching { proxy.requireBean() }.getOrNull() ?: return@withPermit
                        val address = bean.serverAddress.takeIf { it.isNotBlank() } ?: return@withPermit
                        val port = bean.serverPort
                        if (port <= 0) return@withPermit
                        val ping = runCatching {
                            Libcore.tcpPing(address, port.toString(), 1200)
                        }.getOrNull() ?: return@withPermit
                        if (ping > 0) {
                            synchronized(result) {
                                result[proxy.id] = ping
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
}
