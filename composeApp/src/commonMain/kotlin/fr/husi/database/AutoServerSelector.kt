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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

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

    /** Stale [prepareForConnect] runs (e.g. double tap) must not overwrite UI after tunnel is up. */
    private fun setSimpleModeActivityUnlessConnected(text: String) {
        if (BackendState.status.value.state == ServiceState.Connected) {
            simpleModeLog("SimpleMode", "H19 activity_write_skipped_while_connected text=${text.take(48)}")
            return
        }
        DataStore.simpleModeActivity = text
    }

    suspend fun prepareForConnect(): PrepareForConnectResult {
        val selectedBefore = DataStore.selectedProxy
        val whitelistBuiltinOnly = DataStore.simpleModeUseWhitelistBuiltinPoolOnly
        DataStore.simpleModeUseWhitelistBuiltinPoolOnly = false

        WhitelistBuiltinBootstrap.ensureGroupAndProfiles()

        val allProxies = SagerDatabase.proxyDao.getAll()
        val subscriptionWhitelistMarked = allProxies.filter { it.isSubscriptionWhitelistMarked() }
        val subscriptionWhitelistIds = subscriptionWhitelistMarked.map { it.id }.toSet()

        val builtinFour = WhitelistBuiltinBootstrap.whitelistPoolProxies()
        val builtinFourIds = builtinFour.map { it.id }.toSet()

        val priorityFirstIds: Set<Long>
        val proxies: List<ProxyEntity>
        if (whitelistBuiltinOnly) {
            priorityFirstIds = builtinFourIds + subscriptionWhitelistIds
            val priorityHead = (builtinFour + subscriptionWhitelistMarked.sortedBy { it.userOrder })
                .distinctBy { it.id }
            val rest = allProxies.filter { it.id !in priorityFirstIds }
            proxies = priorityHead + rest
        } else {
            priorityFirstIds = emptySet()
            proxies = allProxies.filter { it.id !in subscriptionWhitelistIds }
        }
        simpleModeLog(
            "SimpleMode",
            "H24 autoselect_pool wlNet=$whitelistBuiltinOnly subsWlMarked=${subscriptionWhitelistMarked.size} " +
                "pool=${proxies.size} priorityFirst=${priorityFirstIds.size}",
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
        )
        val shouldQuickProbe = initialCount == proxies.size ||
            availableCount == 0 ||
            forceFullProbeReason != null
        if (forceFullProbeReason != null) {
            simpleModeLog(
                "SimpleMode",
                "H25 full_probe_forced reason=$forceFullProbeReason initial=$initialCount avail=$availableCount",
            )
        }
        val urlTestCap = (DataStore.connectionTestConcurrent * 2).coerceIn(12, 32)
        val extraUrlTestByTcp = 8
        val parallelUrlPoolSize = (urlTestCap + extraUrlTestByTcp).coerceAtMost(proxies.size)
        val urlSupplementCap = 10

        var quickProbePings: Map<Long, Int> = emptyMap()
        var urlTestDelays: Map<Long, Int> = emptyMap()
        var urlTestCandidates: List<ProxyEntity> = emptyList()

        if (shouldQuickProbe) {
            setSimpleModeActivityUnlessConnected("Quick testing servers...")
            val parallelUrlPool = buildStratifiedUrlPool(
                proxies = proxies,
                cap = parallelUrlPoolSize,
                priorityFirstIds = priorityFirstIds,
            )
            simpleModeLog(
                "SimpleMode",
                "H14 quick_probe_started count=${proxies.size} parallel_url_pool=${parallelUrlPool.size} stratified=true",
            )
            coroutineScope {
                val tcpJob = async(Dispatchers.IO) { quickTcpProbe(proxies) }
                val urlJob = async(Dispatchers.IO) {
                    if (parallelUrlPool.isEmpty()) {
                        emptyMap()
                    } else {
                        setSimpleModeActivityUnlessConnected("Testing servers (URL)...")
                        simpleModeLog(
                            "SimpleMode",
                            "H17 urltest_started candidates=${parallelUrlPool.size} baseCap=$urlTestCap extraTcp=$extraUrlTestByTcp mode=parallel_stratified",
                        )
                        urlTestTopCandidates(parallelUrlPool)
                    }
                }
                quickProbePings = tcpJob.await()
                val quickProbeAlive = quickProbePings.size
                val quickProbeHead = quickProbePings.entries
                    .sortedBy { it.value }
                    .take(5)
                    .joinToString(";") { "${it.key}:${it.value}" }
                simpleModeLog(
                    "SimpleMode",
                    "H14 quick_probe_done alive=$quickProbeAlive tested=${proxies.size} best=$quickProbeHead",
                )
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
                    merged.putAll(urlTestTopCandidates(missing))
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
                setSimpleModeActivityUnlessConnected("Testing servers (URL)...")
                simpleModeLog(
                    "SimpleMode",
                    "H17 urltest_started candidates=${urlTestCandidates.size} baseCap=$urlTestCap extraTcp=$extraUrlTestByTcp mode=sequential",
                )
                urlTestTopCandidates(urlTestCandidates)
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
        setSimpleModeActivityUnlessConnected("Selecting server 1/${ranked.size}")
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
        if (shouldQuickProbe) {
            AutoServerSelectorProbePolicy.recordFullProbe(proxies, whitelistBuiltinOnly)
        }
        return PrepareForConnectResult.Success(best)
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
        setSimpleModeActivityUnlessConnected("Trying next server ${nextIndex + 1}/${queue.size}")
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

    private suspend fun urlTestTopCandidates(candidates: List<ProxyEntity>): Map<Long, Int> = coroutineScope {
        val concurrency = DataStore.connectionTestConcurrent.coerceIn(2, 12)
        val semaphore = Semaphore(concurrency)
        val result = HashMap<Long, Int>()
        candidates.map { proxy ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val ms = profileUrlTestDelay(proxy)
                    if (ms != null && ms > 0) {
                        synchronized(result) {
                            result[proxy.id] = ms
                        }
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

    private suspend fun quickTcpProbe(proxies: List<ProxyEntity>): Map<Long, Int> = coroutineScope {
        val concurrency = DataStore.connectionTestConcurrent.coerceIn(4, 24)
        val semaphore = Semaphore(concurrency)
        val result = HashMap<Long, Int>()
        proxies.map { proxy ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
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
                }
            }
        }.awaitAll()
        result.toMap()
    }
}
