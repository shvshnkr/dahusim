package fr.husi.database

import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.fmt.PrepareTestConfigBuilder
import fr.husi.ktx.Logs
import fr.husi.simplemode.SimpleModeHealthRoute
import fr.husi.simplemode.WarmReserveSessionCache
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Consumed on the next [tryMoveToFallback] call (full manual connect with recovery off). */
    @Volatile
    var ignoreSessionFallbackForManualConnect: Boolean = false

    /** Fallback queue ids after [UserPoolPolicy] membership filter (EXCLUSIVE / user-first pass). */
    fun parseEffectiveFallbackQueue(): List<Long> =
        filterFallbackQueueForUserPool(
            AutoServerSelectorSessionFallback.parseQueue(DataStore.autoSelectFallbackQueue),
        )

    private const val TCP_PROBE_BATCH_CAP = 128
    private const val URL_BATCH_CAP = 32
    /** Upper bound on connect-time TCP rounds (128 × 20 = 2560 ≥ full free-market pool). */
    private const val TCP_PROBE_MAX_ROUNDS = 20
    /** Adaptive prepare TCP timeout: round 1 fast-culls bulk dead ports, later rounds calm down. */
    private const val TCP_PROBE_TIMEOUT_MS_ROUND_1 = 800
    private const val TCP_PROBE_TIMEOUT_MS_LATER_ROUNDS = 1200
    private const val PROFILE_FAILURE_COOLDOWN_MS = 30L * 60 * 1000
    /** Per VPN session: cap fallback reconnects so a huge queue cannot spin for hundreds of hops. */
    private const val MAX_SESSION_FALLBACK_STEPS = 32

    @Volatile
    private var probeUiActive = false
    @Volatile
    private var lastPrepareUrlVerifiedIds: Set<Long> = emptySet()

    /** True while a forced full-sweep quick probe is running; ADAPT timeout widens to 180s. */
    @Volatile
    internal var fullSweepInProgress = false

    private val sessionFallbackSteps = AtomicInteger(0)

    fun peekLastPrepareUrlVerifiedIds(): Set<Long> = lastPrepareUrlVerifiedIds

    internal fun setLastPrepareUrlVerifiedIdsForTest(ids: Set<Long>) {
        lastPrepareUrlVerifiedIds = ids
    }

    internal fun failureCooldownSnapshotForTest(ids: Collection<Long>): Set<Long> =
        failureCooldownSnapshot(ids)

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
            (base * 2).coerceIn(10, 40)
        } else {
            base.coerceIn(2, 32)
        }
    }

    private fun tcpProbeConcurrency(whitelistBuiltinOnly: Boolean): Int {
        val base = DataStore.connectionTestConcurrent
        return if (whitelistBuiltinOnly) {
            (base * 3).coerceIn(16, 48)
        } else {
            base.coerceIn(4, 32)
        }
    }

    private const val WL_URL_PROBE_EARLY_EXIT = 8
    private const val WL_SUBSCRIPTION_URL_PROBE_EARLY_EXIT = 1
    private const val OPEN_URL_PROBE_EARLY_EXIT = 2
    private const val TCP_SURVIVOR_URL_CAP = 64
    private const val CONFIRM_TCP_TOP_K = 12

    suspend fun prepareForConnect(
        networkHandoff: Boolean = false,
        owner: PrepareOwner = PrepareOwner.CONNECT,
        compactWlSweep: Boolean = false,
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
                val result = prepareForConnectLocked(session, networkHandoff, compactWlSweep)
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
                // Prepare pipeline finished (selected, failed, or superseded): drop the last
                // published "Scanning N/N" line so the simple screen never shows a stale scan.
                Probe2kProgress.clearScan()
            }
        }
    }

    private suspend fun prepareForConnectLocked(
        session: PrepareSession,
        networkHandoff: Boolean,
        compactWlSweep: Boolean = false,
    ): PrepareForConnectResult {
        DataStore.simpleModePrepareVerifiedProfileId = 0L
        DirectProfileUrlProbe.clearMessengerSecondaryDelays()
        val allProxies = SagerDatabase.proxyDao.getAll()
        val groups = SagerDatabase.groupDao.allGroups().first()
        val userTag = UserSubscriptionTag.resolve(allProxies, groups)
        val probeStatesEnabled = DataStore.probe2kPersistenceEnabled || DataStore.probe2kWarmRankingEnabled
        val probeStatesAll = if (probeStatesEnabled) {
            ProxyProbeStateStore.loadMap(allProxies.map { it.id })
        } else {
            emptyMap()
        }
        val userMode = UserPoolPolicy.effectiveMode()
        simpleModeLog(
            "SimpleMode",
            "H39 user_pool_mode=${userMode.name} userProxies=${userTag.userProxyIds.size} " +
                "userGroups=${userTag.userGroupCount} fallbackUsed=${UserPoolPolicy.simpleModeUserPoolFallbackUsed}",
        )

        if (userMode == UserPoolMode.EXCLUSIVE) {
            if (userTag.userProxyIds.isEmpty()) {
                simpleModeLog("SimpleMode", "H39 user_pool_exclusive_empty")
                return PrepareForConnectResult.NoProfiles
            }
            return runUserPoolPrepare(
                session,
                networkHandoff,
                userTag,
                userMode,
                allProxies,
                groups,
                probeStatesAll,
            )
        }

        if (UserPoolPolicy.shouldRunUserFirstPass(userMode, userTag.userProxyIds)) {
            val userResult = runUserPoolPrepare(
                session,
                networkHandoff,
                userTag,
                userMode,
                allProxies,
                groups,
                probeStatesAll,
            )
            if (userResult !is PrepareForConnectResult.NoProfiles &&
                userResult !is PrepareForConnectResult.AllProbesDead
            ) {
                return userResult
            }
            UserPoolPolicy.simpleModeUserPoolFallbackUsed = true
            simpleModeLog("SimpleMode", "H39 user_pool_fallback_managed")
        }

        return runManagedPoolPrepare(
            session,
            networkHandoff,
            userTag,
            allProxies,
            groups,
            probeStatesAll,
            compactWlSweep,
        )
    }

    private suspend fun runUserPoolPrepare(
        session: PrepareSession,
        networkHandoff: Boolean,
        userTag: UserSubscriptionTag.Resolution,
        userMode: UserPoolMode,
        allProxies: List<ProxyEntity>? = null,
        groups: List<ProxyGroup>? = null,
        probeStatesAll: Map<Long, ProxyProbeState>? = null,
        compactWlSweep: Boolean = false,
    ): PrepareForConnectResult {
        val wlNetRequested = DataStore.simpleModeUseWhitelistBuiltinPoolOnly
        DataStore.simpleModeUseWhitelistBuiltinPoolOnly = false
        val poolMode = resolvePoolBuildMode(wlNetRequested)
        return executePrepareForPool(
            session = session,
            networkHandoff = networkHandoff,
            poolMode = poolMode,
            membershipFilter = ConnectPoolPolicy.PoolMembershipFilter.USER_ONLY,
            userTag = userTag,
            userMode = userMode,
            allProxies = allProxies,
            groups = groups,
            probeStatesAll = probeStatesAll,
            compactWlSweep = compactWlSweep,
        )
    }

    private suspend fun runManagedPoolPrepare(
        session: PrepareSession,
        networkHandoff: Boolean,
        userTag: UserSubscriptionTag.Resolution,
        allProxies: List<ProxyEntity>,
        groups: List<ProxyGroup>,
        probeStatesAll: Map<Long, ProxyProbeState>,
        compactWlSweep: Boolean = false,
    ): PrepareForConnectResult {
        val wlNetRequested = DataStore.simpleModeUseWhitelistBuiltinPoolOnly
        DataStore.simpleModeUseWhitelistBuiltinPoolOnly = false
        val initialMode = resolvePoolBuildMode(wlNetRequested)
        if (initialMode == ConnectPoolPolicy.PoolBuildMode.MERGED &&
            !DataStore.simpleModeAutoselectPoolMerged
        ) {
            DataStore.simpleModeAutoselectPoolMerged = true
            simpleModeLog("SimpleMode", "H4 wl_pool_merged_from_start")
        }
        val userMode = UserPoolPolicy.effectiveMode()
        var result = executePrepareForPool(
            session = session,
            networkHandoff = networkHandoff,
            poolMode = initialMode,
            userTag = userTag,
            userMode = userMode,
            allProxies = allProxies,
            groups = groups,
            probeStatesAll = probeStatesAll,
            compactWlSweep = compactWlSweep,
        )
        if (initialMode == ConnectPoolPolicy.PoolBuildMode.WL_SUBSCRIPTION &&
            (result is PrepareForConnectResult.NoProfiles || result is PrepareForConnectResult.AllProbesDead)
        ) {
            simpleModeLog("SimpleMode", "H4 wl_pool_fallback_open_priority_once")
            result = executePrepareForPool(
                session = session,
                networkHandoff = networkHandoff,
                poolMode = ConnectPoolPolicy.PoolBuildMode.OPEN,
                userTag = userTag,
                userMode = userMode,
                allProxies = allProxies,
                groups = groups,
                probeStatesAll = probeStatesAll,
                compactWlSweep = compactWlSweep,
            )
            if (result is PrepareForConnectResult.Success) {
                DataStore.simpleModeAutoselectPoolMerged = true
                simpleModeLog("SimpleMode", "H4 wl_pool_merged_enabled")
            }
        }
        return result
    }

    private fun resolvePoolBuildMode(wlNetRequested: Boolean): ConnectPoolPolicy.PoolBuildMode {
        if (!wlNetRequested) return ConnectPoolPolicy.PoolBuildMode.OPEN
        if (DataStore.simpleModeAutoselectPoolMerged || DataStore.activeWhitelistRestrictedNetwork) {
            return ConnectPoolPolicy.PoolBuildMode.MERGED
        }
        return ConnectPoolPolicy.PoolBuildMode.WL_SUBSCRIPTION
    }

    private fun poolUsesWlUrlProbes(mode: ConnectPoolPolicy.PoolBuildMode): Boolean =
        AutoServerSelectorProbePolicy.wlUrlProbeForPool(mode, DataStore.activeWhitelistRestrictedNetwork)

    private fun resolveWlUrlProbes(
        poolMode: ConnectPoolPolicy.PoolBuildMode,
        membershipFilter: ConnectPoolPolicy.PoolMembershipFilter,
        orderedProxies: List<ProxyEntity>,
        subscriptionWlIds: Set<Long>,
    ): Boolean {
        if (membershipFilter == ConnectPoolPolicy.PoolMembershipFilter.USER_ONLY) {
            return DataStore.activeWhitelistRestrictedNetwork &&
                orderedProxies.isNotEmpty() &&
                orderedProxies.all { it.id in subscriptionWlIds }
        }
        return poolUsesWlUrlProbes(poolMode)
    }

    private suspend fun executePrepareForPool(
        session: PrepareSession,
        networkHandoff: Boolean,
        poolMode: ConnectPoolPolicy.PoolBuildMode,
        membershipFilter: ConnectPoolPolicy.PoolMembershipFilter = ConnectPoolPolicy.PoolMembershipFilter.NONE,
        userTag: UserSubscriptionTag.Resolution? = null,
        userMode: UserPoolMode = UserPoolPolicy.effectiveMode(),
        allProxies: List<ProxyEntity>? = null,
        groups: List<ProxyGroup>? = null,
        probeStatesAll: Map<Long, ProxyProbeState>? = null,
        compactWlSweep: Boolean = false,
    ): PrepareForConnectResult {
        fullSweepInProgress = false
        val selectedBefore = DataStore.selectedProxy

        val proxiesAll = allProxies ?: SagerDatabase.proxyDao.getAll()
        val groupsAll = groups ?: SagerDatabase.groupDao.allGroups().first()
        val resolvedUserTag = userTag ?: UserSubscriptionTag.resolve(proxiesAll, groupsAll)

        val handoffPriorityIds = if (networkHandoff) {
            buildHandoffPriorityIds(selectedBefore)
        } else {
            emptySet()
        }

        val probeStatesEnabled = DataStore.probe2kPersistenceEnabled || DataStore.probe2kWarmRankingEnabled
        val probeStatesAllResolved = probeStatesAll ?: if (probeStatesEnabled) {
            ProxyProbeStateStore.loadMap(proxiesAll.map { it.id })
        } else {
            emptyMap()
        }
        val poolBuild = ConnectPoolPolicy.build(
            mode = poolMode,
            allProxies = proxiesAll,
            groups = groupsAll,
            handoffIds = handoffPriorityIds,
            probeStates = probeStatesAllResolved,
            membershipFilter = membershipFilter,
            userProxyIds = resolvedUserTag.userProxyIds,
            userPoolMode = userMode,
        )
        val priorityFirstIds = poolBuild.priorityFirstIds
        val proxies = poolBuild.orderedProxies
        val subscriptionWhitelistIds = poolBuild.subscriptionWlIds
        val userProxyIds = poolBuild.userProxyIds
        val wlUrlProbes = resolveWlUrlProbes(poolMode, membershipFilter, proxies, subscriptionWhitelistIds)
        simpleModeLog(
            "SimpleMode",
            "H24 autoselect_pool poolMode=${poolMode.name} userMode=${userMode.name} " +
                "membership=$membershipFilter handoff=$networkHandoff merged=${DataStore.simpleModeAutoselectPoolMerged} " +
                "subsWlMarked=${poolBuild.subsWlMarkedCount} wlGroups=${poolBuild.wlGroupCount} " +
                "userPool=${userProxyIds.size} pool=${proxies.size} priorityFirst=${priorityFirstIds.size}",
        )
        val subscriptionCompactReprobe = AutoServerSelectorProbePolicy.useCompactReprobeForProxySetChange(
            proxies = proxies,
            whitelistBuiltinOnly = wlUrlProbes,
            networkHandoff = networkHandoff,
        )
        val effectiveHandoff = networkHandoff || subscriptionCompactReprobe
        val rawForceFullProbeReason = AutoServerSelectorProbePolicy.forceFullProbeReason(
            proxies = proxies,
            whitelistBuiltinOnly = wlUrlProbes,
            networkHandoff = networkHandoff,
        )
        val wlSweepCacheFingerprint = AutoServerSelectorProbePolicy.wlSweepCacheFingerprint(
            uplinkIdentity = DataStore.networkUplinkIdentity,
            poolHash = AutoServerSelectorProbePolicy.computeProxyIdSetHash(proxies),
        )
        val wlSweepCacheHit = wlUrlProbes && !effectiveHandoff &&
            AutoServerSelectorProbePolicy.wlSweepCacheFresh(wlSweepCacheFingerprint)
        // A fresh WL-sweep cache is stronger evidence than the interval/flag/stale-LKG reasons:
        // repeat WL entries re-probe the cached alive set instead of forcing a full 4096 sweep.
        var forceFullProbeReason = if (wlSweepCacheHit) null else rawForceFullProbeReason
        if (wlSweepCacheHit) {
            simpleModeLog(
                "SimpleMode",
                "H41 wl_sweep_cache_reuse fingerprint=$wlSweepCacheFingerprint " +
                    "ageMs=${System.currentTimeMillis() - DataStore.wlSweepCacheAtMs}",
            )
        }
        if (subscriptionCompactReprobe) {
            simpleModeLog(
                "SimpleMode",
                "H25 compact_reprobe_proxy_set_changed pool=${proxies.size} graceMs=180000",
            )
        }
        val probeStates = if (probeStatesEnabled) {
            probeStatesAllResolved.filterKeys { id -> proxies.any { it.id == id } }
        } else {
            emptyMap()
        }
        val beforeSelectable = ProbePoolEligibility.filterSelectable(proxiesAll, probeStatesAllResolved).size
        var connectPool = ProbePoolEligibility.filterSelectable(proxies, probeStates)
        val wlCompactPrepare = (session.owner == PrepareOwner.ADAPT || compactWlSweep) &&
            wlUrlProbes && !effectiveHandoff
        if (wlCompactPrepare) {
            val compactIds = buildWlCompactPriorityIds(
                connectPool = connectPool,
                priorityFirstIds = priorityFirstIds,
                subscriptionWhitelistIds = subscriptionWhitelistIds,
            )
            if (compactIds.isNotEmpty() && compactIds.size < connectPool.size) {
                val compactPool = connectPool.filter { it.id in compactIds }
                if (compactPool.isNotEmpty()) {
                    simpleModeLog(
                        "SimpleMode",
                        "H41 wl_adapt_compact_pool full=${connectPool.size} compact=${compactPool.size} " +
                            "reason=${if (session.owner == PrepareOwner.ADAPT) "adapt" else "revival_poll"}",
                    )
                    connectPool = compactPool
                }
            }
        }
        if (poolMode == ConnectPoolPolicy.PoolBuildMode.WL_SUBSCRIPTION) {
            simpleModeLog(
                "SimpleMode",
                "H38 wl_pool_built before=$beforeSelectable after=${connectPool.size} " +
                    "cap=${ConnectPoolPolicy.WL_PREPARE_CAP} priority=${priorityFirstIds.size}",
            )
        }
        val jailedCount = ProbePoolEligibility.countJailed(probeStates)
        if (jailedCount > 0) {
            simpleModeLog(
                "SimpleMode",
                "H35 probe_pool_jail count=$jailedCount total=${proxies.size} selectable=${connectPool.size}",
            )
        }
        if (!effectiveHandoff && forceFullProbeReason?.contains("proxy_set_changed") != true &&
            forceFullProbeReason?.contains("wl_to_open") != true &&
            forceFullProbeReason?.contains("open_to_wl") != true
        ) {
            tryLastKnownGoodFastPath(
                proxies = connectPool,
                priorityFirstIds = priorityFirstIds,
                session = session,
                selectedBefore = selectedBefore,
                poolMode = poolMode,
                wlUrlProbes = wlUrlProbes,
                subscriptionWlIds = subscriptionWhitelistIds,
                userProxyIds = userProxyIds,
                userMode = userMode,
                probeStates = probeStates,
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
                message = if (poolMode == ConnectPoolPolicy.PoolBuildMode.WL_SUBSCRIPTION) {
                    "no wl subscription proxies"
                } else {
                    "no proxies in database"
                },
            )
            // #endregion
            simpleModeLog(
                "SimpleMode",
                if (poolMode == ConnectPoolPolicy.PoolBuildMode.WL_SUBSCRIPTION) {
                    "H4 no_proxies_wl_subscription"
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

        if (wlSweepCacheHit) {
            val cacheReprobeResult = runWlSweepCacheReprobe(
                connectPool = connectPool,
                session = session,
                poolMode = poolMode,
                selectedBefore = selectedBefore,
                fingerprint = wlSweepCacheFingerprint,
            )
            if (cacheReprobeResult != null) {
                return cacheReprobeResult
            }
            // 0 url-ok from the cached alive set: fresh negative evidence beats the cache.
            AutoServerSelectorProbePolicy.invalidateWlSweepCache()
            forceFullProbeReason = rawForceFullProbeReason
            simpleModeLog(
                "SimpleMode",
                "H41 wl_sweep_cache_miss pool=${connectPool.size} fullReason=${rawForceFullProbeReason ?: "-"}",
            )
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
            fullSweepInProgress = true
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
        val urlTestCap = when {
            effectiveHandoff && wlUrlProbes -> 20
            effectiveHandoff -> 12
            wlUrlProbes -> (probeConcurrency(true) * 3).coerceIn(20, 36)
            else -> (probeConcurrency(false) * 2).coerceIn(12, 32)
        }
        val extraUrlTestByTcp = if (effectiveHandoff) 4 else 8
        val parallelUrlPoolSize = (urlTestCap + extraUrlTestByTcp).coerceAtMost(connectPool.size)
        val urlSupplementCap = if (effectiveHandoff) 6 else 10
        val compactTcpProbe = wlUrlProbes || effectiveHandoff ||
            connectPool.size > TCP_PROBE_BATCH_CAP
        val tcpBatchCap = TCP_PROBE_BATCH_CAP
        val probePoolOrdered = connectPool
        ensurePrepareCurrent(session)
        val urlConcurrency = probeConcurrency(wlUrlProbes)
        val tcpConcurrency = tcpProbeConcurrency(wlUrlProbes)

        var quickProbePings: Map<Long, Int> = emptyMap()
        var tcpTestedCount = 0
        var urlTestDelays: Map<Long, Int> = emptyMap()
        var urlTestCandidates: List<ProxyEntity> = emptyList()

        if (shouldQuickProbe) {
            var parallelUrlPool: List<ProxyEntity> = emptyList()
            if (!wlUrlProbes) {
                // WL sweep path (progressiveWlSweep) never consumes this stratified pool;
                // building it cost a full DataStore-backed sort per quick-probe on BS.
                val cachedWarmRankingEnabled = DataStore.probe2kWarmRankingEnabled
                parallelUrlPool = ProbeScheduler.filterUrlCandidatesForWarmState(
                    candidates = buildStratifiedUrlPool(
                        proxies = connectPool,
                        cap = parallelUrlPoolSize,
                        priorityFirstIds = priorityFirstIds,
                        probeStates = probeStates,
                        poolMode = poolMode,
                        subscriptionWlIds = subscriptionWhitelistIds,
                        userProxyIds = userProxyIds,
                        userMode = userMode,
                        warmRankingEnabled = cachedWarmRankingEnabled,
                    ),
                    probeStates = probeStates,
                    networkHandoff = effectiveHandoff,
                    whitelistBuiltinOnly = wlUrlProbes,
                )
            }
            simpleModeLog(
                "SimpleMode",
                "H14 quick_probe_started tcp_batch=$tcpBatchCap pool=${connectPool.size} " +
                    "parallel_url_pool=${parallelUrlPool.size} compactTcp=$compactTcpProbe " +
                    "handoff=$effectiveHandoff wlUrlProbe=$wlUrlProbes poolMode=${poolMode.name} " +
                    "tcpConc=$tcpConcurrency urlConc=$urlConcurrency urlCap=$urlTestCap",
            )
            coroutineScope {
                if (wlUrlProbes) {
                    val sweep = progressiveWlSweep(
                        connectPool = connectPool,
                        probePoolOrdered = probePoolOrdered,
                        priorityFirstIds = priorityFirstIds,
                        probeStates = probeStates,
                        tcpBatchCap = tcpBatchCap,
                        urlTestCap = urlTestCap,
                        extraUrlTestByTcp = extraUrlTestByTcp,
                        tcpConcurrency = tcpConcurrency,
                        urlConcurrency = urlConcurrency,
                        session = session,
                        poolMode = poolMode,
                    )
                    quickProbePings = sweep.pings
                    tcpTestedCount = sweep.testedCount
                    urlTestCandidates = sweep.urlCandidates
                    urlTestDelays = sweep.urlDelays
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
                } else {
                    // OPEN: LKG pre-seed first — live candidates (probeState.lastUrlMs > 0,
                    // 48h LKG freshness) get a URL check before any TCP round; a live one
                    // ends the prepare instantly. Does not duplicate tryLastKnownGoodFastPath
                    // (that one is a single LKG skip-full-probe; this is a pool URL pre-seed).
                    val lkgCandidates = connectPool
                        .filter { proxy ->
                            val state = probeStates[proxy.id]
                            state != null && state.lastUrlMs > 0 &&
                                AutoServerSelectorProbePolicy.isLastKnownGoodUrlFresh(proxy.id)
                        }
                        .sortedWith(
                            compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
                                .thenBy { it.id },
                        )
                    val lkgDelays = if (lkgCandidates.isNotEmpty()) {
                        simpleModeLog(
                            "SimpleMode",
                            "H17 urltest_started candidates=${lkgCandidates.size} mode=open_lkg_preseed",
                        )
                        urlTestTopCandidates(
                            lkgCandidates,
                            urlConcurrency,
                            session,
                            poolMode = poolMode,
                        ) { done, total ->
                            setSimpleModeActivity("Testing URL $done/$total")
                            Probe2kProgress.publishScan(done, total)
                        }
                    } else {
                        emptyMap()
                    }
                    if (lkgDelays.isNotEmpty()) {
                        quickProbePings = emptyMap()
                        tcpTestedCount = 0
                        urlTestCandidates = lkgCandidates
                        urlTestDelays = lkgDelays
                        simpleModeLog(
                            "SimpleMode",
                            "H14 quick_probe_done alive=0 tested=0 " +
                                "pool=${connectPool.size} best= lkg_preseed=open",
                        )
                    } else {
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
                        val tcpLabel = if (compactTcpProbe && poolSize > totalInRound) {
                            "Testing TCP $cumulativeTested/$poolSize"
                        } else {
                            "Testing TCP $doneInRound/$totalInRound"
                        }
                        setSimpleModeActivity(tcpLabel)
                        Probe2kProgress.publishScan(cumulativeTested.coerceAtMost(poolSize), poolSize)
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
                        urlTestTopCandidates(
                            parallelUrlPool,
                            urlConcurrency,
                            session,
                            whitelistBuiltinOnly = wlUrlProbes,
                            poolMode = poolMode,
                        ) { done, total ->
                            setSimpleModeActivity("Testing URL $done/$total")
                            Probe2kProgress.publishScan(done, total)
                        }
                    }
                }
                ensurePrepareCurrent(session)
                val urlDelays0 = urlJob.await()
                if (urlDelays0.isNotEmpty()) {
                    // First url-ok(s) decided the connect: cancel the in-flight TCP round and
                    // early-connect instead of waiting for the full TCP enumeration.
                    tcpJob.cancel()
                    quickProbePings = emptyMap()
                    tcpTestedCount = 0
                    urlTestCandidates = parallelUrlPool
                    urlTestDelays = urlDelays0
                    simpleModeLog(
                        "SimpleMode",
                        "H14 quick_probe_done alive=0 tested=0 " +
                            "pool=${connectPool.size} best= url_early_exit=open",
                    )
                } else {
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
                var merged = urlDelays0.toMutableMap()
                val preUrlSorted = connectPool.sortedWith(
                    compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
                        .thenBy { if (quickProbePings.containsKey(it.id)) 0 else 1 }
                        .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .thenBy { statusRank(it.status) }
                        .thenBy { pingRank(it.ping) }
                        .thenByDescending { throughputRank(it) }
                        .thenBy { it.id },
                )
                val baseUrlTest = buildStratifiedUrlPool(
                    proxies = preUrlSorted,
                    cap = urlTestCap,
                    priorityFirstIds = priorityFirstIds,
                    poolMode = poolMode,
                    subscriptionWlIds = subscriptionWhitelistIds,
                    userProxyIds = userProxyIds,
                    userMode = userMode,
                )
                val baseIds = baseUrlTest.map { it.id }.toSet()
                val tcpSurvivors = quickProbePings.size.coerceAtMost(TCP_SURVIVOR_URL_CAP)
                val extraTcpForUrlTest = if (quickProbePings.isNotEmpty()) {
                    connectPool
                        .asSequence()
                        .filter { it.id !in baseIds && quickProbePings.containsKey(it.id) }
                        .sortedBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .take((tcpSurvivors - baseUrlTest.size).coerceAtLeast(0))
                        .toList()
                } else {
                    emptyList()
                }
                urlTestCandidates = (baseUrlTest + extraTcpForUrlTest).distinctBy { it.id }
                val missing = urlTestCandidates
                    .filter { it.id !in merged }
                    .sortedBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                    .take(urlSupplementCap)
                if (missing.isNotEmpty() && (merged.isEmpty() || !wlUrlProbes)) {
                    simpleModeLog(
                        "SimpleMode",
                        "H17 urltest_supplement candidates=${missing.size} cap=$urlSupplementCap " +
                            "url_wave=1 tcp_survivors=$tcpSurvivors",
                    )
                    merged.putAll(
                        urlTestTopCandidates(
                            missing,
                            urlConcurrency,
                            session,
                            whitelistBuiltinOnly = wlUrlProbes,
                            poolMode = poolMode,
                        ) { done, total ->
                            setSimpleModeActivity("Testing URL $done/$total")
                            Probe2kProgress.publishScan(done, total)
                        },
                    )
                }
                var urlWave = 1
                if (merged.isEmpty() && quickProbePings.isNotEmpty()) {
                    val wave2Pool = connectPool
                        .filter { it.id !in merged && quickProbePings.containsKey(it.id) }
                        .sortedBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .drop(urlTestCandidates.size % quickProbePings.size.coerceAtLeast(1))
                        .take(CONFIRM_TCP_TOP_K)
                    if (wave2Pool.isNotEmpty()) {
                        urlWave = 2
                        simpleModeLog(
                            "SimpleMode",
                            "H17 urltest_wave2 candidates=${wave2Pool.size} tier=CONFIRM " +
                                "tcp_survivors=$tcpSurvivors",
                        )
                        merged.putAll(
                            urlTestTopCandidates(
                                wave2Pool,
                                urlConcurrency,
                                session,
                                whitelistBuiltinOnly = wlUrlProbes,
                                poolMode = poolMode,
                                tier = SimpleModeHealthRoute.ProbeTier.CONFIRM,
                            ) { done, total ->
                                setSimpleModeActivity("Testing URL $done/$total")
                                Probe2kProgress.publishScan(done, total)
                            },
                        )
                    }
                }
                val topDelays = merged.entries.sortedBy { it.value }.take(3)
                if (SimpleModeHealthRoute.shouldEscalateToConfirm(
                        SimpleModeHealthRoute.ProbeEscalationContext(
                            phase = "prepare",
                            urlOk = merged.size,
                            tcpAlive = quickProbePings.size,
                            topDelays = topDelays.map { it.key to it.value },
                            whitelistOnly = wlUrlProbes,
                        ),
                    ) && topDelays.size >= 2
                ) {
                    val tieIds = topDelays.map { it.key }.toSet()
                    val tiePool = urlTestCandidates.filter { it.id in tieIds }
                    if (tiePool.isNotEmpty()) {
                        simpleModeLog(
                            "SimpleMode",
                            "H17 urltest_tiebreak candidates=${tiePool.size} tier=CONFIRM url_wave=$urlWave",
                        )
                        merged.putAll(
                            urlTestTopCandidates(
                                tiePool,
                                urlConcurrency,
                                session,
                                whitelistBuiltinOnly = wlUrlProbes,
                                poolMode = poolMode,
                                tier = SimpleModeHealthRoute.ProbeTier.CONFIRM,
                            ),
                        )
                    }
                }
                    urlTestDelays = merged
                }
                }
            }
            }
        } else {
            quickProbePings = emptyMap()
            val preUrlSorted = connectPool.sortedWith(
                compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
                    .thenBy { if (quickProbePings.containsKey(it.id)) 0 else 1 }
                    .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                    .thenBy { statusRank(it.status) }
                    .thenBy { pingRank(it.ping) }
                    .thenByDescending { throughputRank(it) }
                    .thenBy { it.id },
            )
            val baseUrlTest = buildStratifiedUrlPool(
                proxies = preUrlSorted,
                cap = urlTestCap,
                priorityFirstIds = priorityFirstIds,
                poolMode = poolMode,
                subscriptionWlIds = subscriptionWhitelistIds,
                userProxyIds = userProxyIds,
                userMode = userMode,
            )
            urlTestCandidates = baseUrlTest
            urlTestDelays = if (urlTestCandidates.isNotEmpty()) {
                setSimpleModeActivity("Testing URL 0/${urlTestCandidates.size}")
                simpleModeLog(
                    "SimpleMode",
                    "H17 urltest_started candidates=${urlTestCandidates.size} baseCap=$urlTestCap extraTcp=$extraUrlTestByTcp mode=sequential",
                )
                urlTestTopCandidates(
                    urlTestCandidates,
                    urlConcurrency,
                    session,
                    whitelistBuiltinOnly = wlUrlProbes,
                    poolMode = poolMode,
                ) { done, total ->
                    setSimpleModeActivity("Testing URL $done/$total")
                    Probe2kProgress.publishScan(done, total)
                }
            } else {
                emptyMap()
            }
        }
        if (DataStore.probe2kPersistenceEnabled && (quickProbePings.isNotEmpty() || urlTestDelays.isNotEmpty())) {
            ProxyProbeStateStore.persistPrepareResults(
                proxies = connectPool,
                tcpMs = quickProbePings,
                urlMs = urlTestDelays,
            )
            ProxyProbeStateStore.logPoolSnapshot("prepare")
        }
        if (urlTestCandidates.isNotEmpty()) {
            val urlOk = urlTestDelays.size
            val tcpSurvivors = quickProbePings.size
            val urlHead = urlTestDelays.entries
                .sortedBy { it.value }
                .take(5)
                .joinToString(";") { "${it.key}:${it.value}" }
            simpleModeLog(
                "SimpleMode",
                "H17 urltest_done success=$urlOk tested=${urlTestCandidates.size} " +
                    "tcp_survivors=$tcpSurvivors best=$urlHead",
            )
        }

        // Early-connect: first url-ok(s) already decided the connect — skip the full
        // ranking (smoke 754 OPEN: first url-ok at 214ms, but preconnect took 18.5s through
        // ranking; wl: the in-scope return@coroutineScope discarded its value and prepare
        // fell through to full ranking — bs_session4_753.log: early_connect 07:53:32.171 →
        // queue_prepared 07:53:36.507 → preconnect 75.5s).
        // Fallback queue = url-ok + tcp-alive. Best keeps the messenger TCP+URL preference of
        // demoteUrlOnlyBestIfNeeded (URL-only url-ok can be a false positive — smoke 754 best
        // fell post_connect); the ranking path below still applies demoteUrlOnlyBestIfNeeded.
        if (shouldQuickProbe && urlTestDelays.isNotEmpty()) {
            var earlyBest = if (wlUrlProbes) {
                urlTestDelays.minBy { it.value }.key
            } else {
                PrepareConnectSelection.openEarlyConnectBest(urlTestDelays, quickProbePings)
            }
            var urlDelays = urlTestDelays
            if (wlUrlProbes) {
                // BS-S5: a marginal url-ok (near the 6s WL probe cap) is a flap candidate —
                // one reverify before early-connect; a failed reverify drops the profile and
                // re-picks; if nothing is left, fall through to the 0-url-ok dead-end path.
                val wlTimeoutMs = AutoServerSelectorProbePolicy.wlUrlProbeTimeoutMs()
                val firstDelayMs = urlDelays.getValue(earlyBest)
                if (AutoServerSelectorProbePolicy.isMarginalUrlLatency(firstDelayMs, wlTimeoutMs)) {
                    val reverifyMs = DirectProfileUrlProbe.urlTestDelay(
                        connectPool.first { it.id == earlyBest },
                        whitelistOnly = true,
                    )
                    val decision = AutoServerSelectorProbePolicy.decideMarginalUrlReverify(reverifyMs)
                    urlDelays = if (decision.keep) {
                        urlDelays + (earlyBest to decision.delayMs!!)
                    } else {
                        urlDelays - earlyBest
                    }
                    simpleModeLog(
                        "SimpleMode",
                        "H17 url_ok_reverify profile=$earlyBest first=$firstDelayMs " +
                            "second=${reverifyMs ?: "fail"} decision=${if (decision.keep) "keep" else "drop"}",
                    )
                    if (!decision.keep) {
                        earlyBest = urlDelays.minByOrNull { it.value }?.key ?: 0L
                    }
                }
            }
            if (earlyBest > 0L) {
                val urlOkRanked = urlDelays.entries
                    .sortedBy { it.value }
                    .map { it.key }
                val tcpOnlyRanked = quickProbePings.entries
                    .filter { it.key !in urlDelays }
                    .sortedBy { it.value }
                    .map { it.key }
                val earlyFallbackQueue = (urlOkRanked + tcpOnlyRanked).joinToString(",")
                DataStore.autoSelectFallbackQueue = earlyFallbackQueue
                DataStore.autoSelectFallbackIndex = 0
                sessionFallbackSteps.set(0)
                if (selectedBefore != earlyBest) {
                    DataStore.selectedProxy = earlyBest
                }
                if (!effectiveHandoff) {
                    AutoServerSelectorProbePolicy.recordFullProbe(proxies, wlUrlProbes)
                }
                lastPrepareUrlVerifiedIds = urlDelays.keys
                if (wlUrlProbes && !effectiveHandoff) {
                    AutoServerSelectorProbePolicy.recordWlSweepCache(
                        wlSweepCacheFingerprint,
                        urlDelays.keys,
                        quickProbePings.keys,
                    )
                }
                simpleModeLog(
                    "SimpleMode",
                    "H4 early_connect best=$earlyBest urlOk=${urlDelays.size} " +
                        "tcpAlive=${quickProbePings.size} queueSize=${urlOkRanked.size + tcpOnlyRanked.size} " +
                        "path=${if (wlUrlProbes) "wl" else "open"}",
                )
                ProxyProbeStateStore.logPoolSnapshot("prepare")
                fullSweepInProgress = false
                return PrepareForConnectResult.Success(earlyBest)
            }
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
                    "jailed=$jailedCount wlUrlProbe=$wlUrlProbes poolMode=${poolMode.name}",
            )
            if (wlUrlProbes) {
                AutoServerSelectorProbePolicy.invalidateWlSweepCache()
            }
            simpleModeDebugEvent(
                runId = "run1",
                hypothesisId = "H22",
                location = "AutoServerSelector.kt:prepareForConnect",
                message = "all tcp and url probes failed",
                data = mapOf("count" to connectPool.size.toString()),
            )
            fullSweepInProgress = false
            return PrepareForConnectResult.AllProbesDead
        }

        val cachedWarmRankingEnabled = DataStore.probe2kWarmRankingEnabled
        val cachedDegradedProfileId = DataStore.autoSelectLastDegradedProfileId
        val cachedDegradedAt = DataStore.autoSelectLastDegradedAt
        val cachedLastKnownGood = DataStore.autoSelectLastKnownGood
        val cooldownIds = failureCooldownSnapshot(connectPool.map { it.id }, cachedDegradedProfileId, cachedDegradedAt)
        val ranked = connectPool
            .sortedWith(
                if (quickProbePings.isNotEmpty()) {
                    compareBy<ProxyEntity> {
                        if (it.id in cooldownIds && it.id !in urlTestDelays) 1 else 0
                    }.thenBy { if (urlTestDelays.containsKey(it.id)) 0 else 1 }
                        .thenBy {
                            if (wlUrlProbes && it.id !in priorityFirstIds && it.id !in urlTestDelays) {
                                1
                            } else {
                                0
                            }
                        }
                        .thenBy {
                            if (wlUrlProbes && it.id in quickProbePings && it.id !in urlTestDelays) {
                                1
                            } else {
                                0
                            }
                        }
                        .thenBy { if (it.id in quickProbePings) 0 else 1 }
                        .thenBy { warmProbeStateRank(probeStates, it.id, cachedWarmRankingEnabled) }
                        .thenBy {
                            PrepareConnectSelection.openPreparePathTier(
                                it.id,
                                wlUrlProbes,
                                urlTestDelays,
                                quickProbePings,
                            )
                        }
                        .thenBy {
                            statusRank(
                                PrepareConnectSelection.effectiveStatusForRanking(it, urlTestDelays),
                            )
                        }
                        .thenBy {
                            compositeSelectionScore(
                                it,
                                urlTestDelays,
                                quickProbePings,
                                probeStates,
                                wlUrlProbes,
                                cachedWarmRankingEnabled,
                            )
                        }
                        .thenBy { pingRank(it.ping) }
                        .thenByDescending { throughputRank(it) }
                        .thenBy {
                            if (DirectProfileUrlProbe.messengerSecondaryDelay(it.id) != null) 0 else 1
                        }
                        .thenBy { urlTestDelays[it.id] ?: Int.MAX_VALUE }
                        .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .thenByDescending { it.id == cachedLastKnownGood }
                        .thenBy { if (it.id in priorityFirstIds) 0 else 1 }
                        .thenBy {
                            ConnectPoolPolicy.selectionRank(
                                it.id,
                                subscriptionWhitelistIds,
                                poolMode,
                                userProxyIds,
                                userMode,
                            )
                        }
                        .thenBy { it.userOrder }
                        .thenBy { it.id }
                } else {
                    compareBy<ProxyEntity> {
                        if (it.id in cooldownIds && it.id !in urlTestDelays) 1 else 0
                    }.thenBy { warmProbeStateRank(probeStates, it.id, cachedWarmRankingEnabled) }
                        .thenBy { if (urlTestDelays.containsKey(it.id)) 0 else 1 }
                        .thenBy { urlTestDelays[it.id] ?: ProxyProbeStateStore.persistedDelayScore(probeStates[it.id]) }
                        .thenByDescending { throughputRank(it) }
                        .thenBy { if (quickProbePings.containsKey(it.id)) 0 else 1 }
                        .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                        .thenBy { statusRank(it.status) }
                        .thenBy { pingRank(it.ping) }
                        .thenByDescending { it.id == cachedLastKnownGood }
                        .thenBy { if (it.id in priorityFirstIds) 0 else 1 }
                        .thenBy {
                            ConnectPoolPolicy.selectionRank(
                                it.id,
                                subscriptionWhitelistIds,
                                poolMode,
                                userProxyIds,
                                userMode,
                            )
                        }
                        .thenBy { it.userOrder }
                        .thenBy { it.id }
                },
            )
            .map { it.id }
        val rankedFinal = finalizeFallbackQueueOrder(ranked, probeStates, connectPool, userMode)
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
                compositeSelectionScore(
                    proxy,
                    urlTestDelays,
                    quickProbePings,
                    probeStates,
                    wlUrlProbes,
                ).toString()
            } else {
                "-"
            }
            val freshUrl = if (id in urlTestDelays) "y" else "n"
            val freshTcp = if ((quickProbePings[id] ?: 0) > 0) "y" else "n"
            "${proxy.id}|co=$co|url=$ut|tcp=$qp|fu=$freshUrl|ft=$freshTcp|st=${proxy.status}|ping=${proxy.ping}|tp=${throughputRank(proxy)}"
        }

        if (quickProbePings.isNotEmpty()) {
            val h20 = rankedFinal.take(8).joinToString(";") { id ->
                val proxy = connectPool.first { it.id == id }
                val co = compositeSelectionScore(
                    proxy,
                    urlTestDelays,
                    quickProbePings,
                    probeStates,
                    wlUrlProbes,
                    cachedWarmRankingEnabled,
                )
                "${proxy.id}:$co"
            }
            simpleModeLog(
                "SimpleMode",
                "H20 rank_composite_head=$h20 urlCandidates=${urlTestCandidates.size}",
            )
        }

        DataStore.autoSelectFallbackQueue = rankedFinal.joinToString(",")
        DataStore.autoSelectFallbackIndex = 0
        sessionFallbackSteps.set(0)
        val profilesById = connectPool.associateBy { it.id }
        val best = if (networkHandoff &&
            selectedBefore > 0L &&
            selectedBefore in rankedFinal &&
            (!isInFailureCooldown(selectedBefore, cachedDegradedProfileId, cachedDegradedAt) || selectedBefore in urlTestDelays) &&
            PrepareConnectSelection.hasFreshPrepareUrl(selectedBefore, urlTestDelays)
        ) {
            simpleModeLog("SimpleMode", "H33 handoff_prefer_current profileId=$selectedBefore")
            selectedBefore
        } else {
            selectBestProfile(
                rankedFinal = rankedFinal,
                profilesById = profilesById,
                probeStates = probeStates,
                wlUrlProbes = wlUrlProbes,
                urlTestDelays = urlTestDelays,
                quickProbePings = quickProbePings,
            )
        }
        var finalBest = if (!wlUrlProbes && shouldQuickProbe) {
            val demoted = PrepareConnectSelection.demoteUrlOnlyBestIfNeeded(
                best = best,
                rankedFinal = rankedFinal,
                urlTestDelays = urlTestDelays,
                quickProbePings = quickProbePings,
                probeStates = probeStates,
                isInFailureCooldown = { id -> isInFailureCooldown(id, cachedDegradedProfileId, cachedDegradedAt) && id !in urlTestDelays },
            )
            if (demoted != best) {
                simpleModeLog(
                    "SimpleMode",
                    "H22 prepare_select_demoted reason=url_only_no_tcp from=$best to=$demoted",
                )
            }
            demoted
        } else {
            best
        }
        setSimpleModeActivity("Ranking ${rankedFinal.size} servers…")
        if (selectedBefore != finalBest) {
            Logs.d("AutoSelect: switch selected profile $selectedBefore -> $finalBest")
            DataStore.selectedProxy = finalBest
        }
        // #region agent log
        simpleModeDebugEvent(
            runId = "run1",
            hypothesisId = "H4",
            location = "AutoServerSelector.kt:prepareForConnect",
            message = "prepared fallback queue",
            data = mapOf(
                "selectedBefore" to selectedBefore.toString(),
                "best" to finalBest.toString(),
                "queueSize" to rankedFinal.size.toString(),
                "groupCount" to connectPool.map { it.groupId }.toSet().size.toString(),
                "availableCount" to availableCount.toString(),
                "initialCount" to initialCount.toString(),
                "badCount" to badCount.toString(),
                "probeAlive" to quickProbeAlive.toString(),
                "urlTestOk" to urlTestDelays.size.toString(),
                "bestPing" to (connectPool.firstOrNull { it.id == finalBest }?.ping?.toString() ?: "0"),
                "rankedHead" to rankedHead,
            ),
        )
        // #endregion
        simpleModeLog(
            "SimpleMode",
            "H4 queue_prepared before=$selectedBefore best=$finalBest size=${rankedFinal.size} avail=$availableCount initial=$initialCount bad=$badCount probeAlive=$quickProbeAlive urlOk=${urlTestDelays.size} head=$rankedHead",
        )
        if (shouldQuickProbe && !effectiveHandoff) {
            AutoServerSelectorProbePolicy.recordFullProbe(proxies, wlUrlProbes)
        }
        recordPrepareSelectionReason(
            initialCount = initialCount,
            proxyCount = connectPool.size,
            quickProbeAlive = quickProbeAlive,
            urlTestOk = urlTestDelays.size,
            effectiveHandoff = effectiveHandoff,
            forceFullProbeReason = forceFullProbeReason,
            probeStates = probeStates,
        )
        if (AutoServerSelectorProbePolicy.wlNoUrlOkDeadEndsPrepare(
                wlUrlProbes = wlUrlProbes,
                activeWhitelistRestrictedNetwork = DataStore.activeWhitelistRestrictedNetwork,
                urlOk = urlTestDelays.size,
                urlConfirmed = finalBest in urlTestDelays ||
                    (urlTestCandidates.none { it.id == finalBest } &&
                        AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(
                            finalBest,
                            urlTestDelays,
                            probeStates,
                        )),
            )
        ) {
            simpleModeLog(
                "SimpleMode",
                "H22 prepare_wl_no_url_ok best=$finalBest tcpAlive=$quickProbeAlive pool=${connectPool.size} " +
                    "wlUrlProbe=$wlUrlProbes poolMode=${poolMode.name}",
            )
            if (wlUrlProbes) {
                AutoServerSelectorProbePolicy.invalidateWlSweepCache()
            }
            fullSweepInProgress = false
            return PrepareForConnectResult.AllProbesDead
        }
        val openPrepareDecision = AutoServerSelectorProbePolicy.decideOpenPrepare(
            wlUrlProbes = wlUrlProbes,
            shouldQuickProbe = shouldQuickProbe,
            tcpAlive = quickProbeAlive,
            urlOk = urlTestDelays.size,
        )
        if (!wlUrlProbes && shouldQuickProbe) {
            val circuitState = if (openPrepareDecision.circuitOpen) "open" else "closed"
            val ratioPercent = (openPrepareDecision.successRatio * 100).toInt()
            simpleModeLog(
                "SimpleMode",
                "H22 prepare_decision=${openPrepareDecision.decision.name.lowercase()} " +
                    "telegram_target_circuit=$circuitState ratio=${ratioPercent}% samples=${openPrepareDecision.sampleCount} " +
                    "tcpAlive=$quickProbeAlive urlOk=${urlTestDelays.size} pool=${connectPool.size}",
            )
        }
        when (openPrepareDecision.decision) {
            AutoServerSelectorProbePolicy.OpenPrepareDecision.HARD_DEAD -> {
                simpleModeLog(
                    "SimpleMode",
                    "H22 prepare_open_hard_dead best=$finalBest tcpAlive=$quickProbeAlive pool=${connectPool.size}",
                )
                fullSweepInProgress = false
                return PrepareForConnectResult.AllProbesDead
            }
            AutoServerSelectorProbePolicy.OpenPrepareDecision.DEGRADED -> {
                simpleModeLog(
                    "SimpleMode",
                    "H22 prepare_open_degraded_continue best=$finalBest tcpAlive=$quickProbeAlive pool=${connectPool.size}",
                )
            }
            AutoServerSelectorProbePolicy.OpenPrepareDecision.OK -> Unit
        }
        lastPrepareUrlVerifiedIds = urlTestDelays.keys
        if (wlUrlProbes && !effectiveHandoff) {
            AutoServerSelectorProbePolicy.recordWlSweepCache(
                wlSweepCacheFingerprint,
                urlTestDelays.keys,
                quickProbePings.keys,
            )
        }
        val resolvedBest = resolveBestAgainstDb(finalBest, rankedFinal)
        if (resolvedBest != finalBest) {
            simpleModeLog(
                "SimpleMode",
                "H22 prepare_selected_profile_missing best=$finalBest fallbackTo=$resolvedBest",
            )
            DataStore.selectedProxy = resolvedBest
        }
        fullSweepInProgress = false
        return PrepareForConnectResult.Success(resolvedBest)
    }

    /**
     * The concurrent subscription refresh (connect refresh) can delete a profile from the DB
     * after this prepare snapshot picked it as best. Re-resolve against the current DB so the
     * returned id still exists; otherwise fall back to the next ranked candidate.
     */
    internal suspend fun resolveBestAgainstDb(best: Long, rankedFinal: List<Long>): Long {
        if (best <= 0L) return best
        if (SagerDatabase.proxyDao.getById(best) != null) return best
        val existingIds = SagerDatabase.proxyDao.getAll().map { it.id }.toSet()
        val replacement = rankedFinal.firstOrNull { it in existingIds }
        if (replacement == null) {
            simpleModeLog("SimpleMode", "H22 prepare_selected_profile_missing best=$best no_existing_candidates")
            return best
        }
        return replacement
    }

    private fun recordPrepareSelectionReason(
        initialCount: Int,
        proxyCount: Int,
        quickProbeAlive: Int,
        urlTestOk: Int,
        effectiveHandoff: Boolean,
        forceFullProbeReason: String?,
        probeStates: Map<Long, ProxyProbeState>,
    ) {
        val warmViable = if (DataStore.probe2kWarmRankingEnabled) {
            probeStates.values.count {
                it.state == ProbeState.ALIVE || it.state == ProbeState.CANDIDATE
            }
        } else {
            0
        }
        val reason = when {
            initialCount == proxyCount -> "all_initial"
            effectiveHandoff -> "handoff_probe"
            forceFullProbeReason != null -> "full_probe:$forceFullProbeReason"
            quickProbeAlive == 0 && urlTestOk == 0 && DataStore.probe2kWarmRankingEnabled &&
                warmViable < (proxyCount * 0.05).toInt().coerceAtLeast(3) -> "warm_sparse_heuristic"
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

    /**
     * WLZ-S4: priority compact set for WL adapt / revival-poll prepares — cached sweep ids
     * (fresh) + WL-tagged + LKG + warm-reserve live + fallback queue head. Restricts the
     * 4096-proxy MERGED sweep to candidates most likely to be alive, instead of re-probing
     * everything on every session_unhealthy / revival poll.
     */
    private fun buildWlCompactPriorityIds(
        connectPool: List<ProxyEntity>,
        priorityFirstIds: Set<Long>,
        subscriptionWhitelistIds: Set<Long>,
    ): Set<Long> {
        val poolIds = connectPool.mapTo(HashSet()) { it.id }
        val ids = LinkedHashSet<Long>()
        val (cachedUrlVerified, cachedTcpAlive) = AutoServerSelectorProbePolicy.cachedWlSweepIds()
        ids += cachedUrlVerified
        ids += cachedTcpAlive
        ids += priorityFirstIds
        ids += subscriptionWhitelistIds
        val lkg = DataStore.autoSelectLastKnownGood
        if (lkg > 0L) ids += lkg
        WarmReserveSessionCache.liveVerifiedIdsSnapshot().forEach { ids += it }
        DataStore.autoSelectFallbackQueue
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .take(12)
            .forEach { ids += it }
        ids.retainAll(poolIds)
        return ids
    }

    /**
     * WLZ-S4: cache-backed WL re-entry — URL-probe only the cached alive set (url-verified
     * first). A hit re-confirms the connect instantly; a full 0-ok result invalidates the
     * cache (fresh negative evidence) and falls through to the normal sweep.
     */
    private suspend fun runWlSweepCacheReprobe(
        connectPool: List<ProxyEntity>,
        session: PrepareSession,
        poolMode: ConnectPoolPolicy.PoolBuildMode,
        selectedBefore: Long,
        fingerprint: String,
    ): PrepareForConnectResult? {
        val (cachedUrlVerified, cachedTcpAlive) = AutoServerSelectorProbePolicy.cachedWlSweepIds()
        val candidates = connectPool
            .filter { it.id in cachedUrlVerified || it.id in cachedTcpAlive }
            .sortedWith(
                compareBy<ProxyEntity> { if (it.id in cachedUrlVerified) 0 else 1 }
                    .thenBy { it.id },
            )
        if (candidates.isEmpty()) return null
        setSimpleModeActivity("Testing URL 0/${candidates.size}")
        simpleModeLog(
            "SimpleMode",
            "H17 urltest_started candidates=${candidates.size} mode=wl_sweep_cache",
        )
        val delays = urlTestTopCandidates(
            candidates,
            probeConcurrency(true),
            session,
            whitelistBuiltinOnly = true,
            poolMode = poolMode,
            forcePerProfile = true,
        ) { done, total ->
            setSimpleModeActivity("Testing URL $done/$total")
            Probe2kProgress.publishScan(done, total)
        }
        if (delays.isEmpty()) return null
        AutoServerSelectorProbePolicy.touchWlSweepCache()
        val best = delays.minBy { it.value }.key
        val urlOkRanked = delays.entries.sortedBy { it.value }.map { it.key }
        val tcpOnlyRanked = cachedTcpAlive.filter { it !in delays }.sorted()
        DataStore.autoSelectFallbackQueue = (urlOkRanked + tcpOnlyRanked).joinToString(",")
        DataStore.autoSelectFallbackIndex = 0
        sessionFallbackSteps.set(0)
        if (selectedBefore != best) {
            DataStore.selectedProxy = best
        }
        lastPrepareUrlVerifiedIds = delays.keys
        simpleModeLog(
            "SimpleMode",
            "H41 wl_sweep_cache_connect best=$best urlOk=${delays.size} tcpAlive=${tcpOnlyRanked.size} " +
                "fingerprint=$fingerprint",
        )
        ProxyProbeStateStore.logPoolSnapshot("prepare")
        return PrepareForConnectResult.Success(best)
    }

    fun tryMoveToFallback(currentId: Long): Long? {
        if (ignoreSessionFallbackForManualConnect) {
            ignoreSessionFallbackForManualConnect = false
            return null
        }
        if (!DataStore.simpleMode && !DataStore.expertConnectRecoverEnabled) {
            return null
        }
        var walkFromId = currentId
        repeat(8) {
            val picked = pickNextFallbackCandidate(walkFromId) ?: return null
            if (!reprobeFallbackCandidate(picked.nextId)) {
                WarmReserveSessionCache.markWarmFailed(picked.nextId)
                walkFromId = picked.nextId
                return@repeat
            }
            commitFallbackSelection(currentId, picked)
            return picked.nextId
        }
        return null
    }

    private data class FallbackPick(
        val nextId: Long,
        val nextIndex: Int,
        val queueSize: Int,
        val source: String,
        val walkStats: AutoServerSelectorSessionFallback.FallbackWalkResult? = null,
    )

    private fun pickNextFallbackCandidate(currentId: Long): FallbackPick? {
        val maxSteps = WlAutoselectPolicy.maxSessionFallbackSteps(
            DataStore.activeWhitelistRestrictedNetwork,
        )
        if (sessionFallbackSteps.get() >= maxSteps) {
            simpleModeLog(
                "SimpleMode",
                "H1 fallback_session_cap currentId=$currentId steps=$maxSteps " +
                    "wl=${DataStore.activeWhitelistRestrictedNetwork}",
            )
            if (DataStore.activeWhitelistRestrictedNetwork) {
                DataStore.simpleModeActivity = "No server works on restricted network"
            }
            return null
        }
        val queue = parseEffectiveFallbackQueue()
        if (queue.isEmpty()) {
            simpleModeDebugEvent(
                runId = "run1",
                hypothesisId = "H1",
                location = "AutoServerSelector.kt:tryMoveToFallback",
                message = "fallback queue empty",
                data = mapOf("currentId" to currentId.toString()),
            )
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
        val strictFreshFallback = WarmReservePool.isFeatureEnabled()
        val nowMs = System.currentTimeMillis()
        if (strictFreshFallback) {
            val cache = WarmReserveSessionCache
            val liveReserves = WarmReservePool.liveReserveIds(queue, currentId, cache, probeStates)
            for (candidate in liveReserves) {
                if (candidate == currentId || isInFailureCooldown(candidate)) continue
                val state = probeStates[candidate]
                if (state?.state == ProbeState.CEMETERY || state?.state == ProbeState.DEAD) continue
                val nextIndex = queue.indexOf(candidate).takeIf { it >= 0 } ?: startIndex
                return FallbackPick(
                    nextId = candidate,
                    nextIndex = nextIndex,
                    queueSize = queue.size,
                    source = "warm_live",
                )
            }
        }
        val walk = AutoServerSelectorSessionFallback.findNextFallbackCandidate(
            queue = queue,
            startIndex = startIndex,
            probeStates = probeStates,
            inRecentFailureCooldown = ::isInFailureCooldown,
            requireFreshUrlVerified = strictFreshFallback,
            excludeIds = if (strictFreshFallback) {
                WarmReserveSessionCache.warmFailedIdsSnapshot()
            } else {
                emptySet()
            },
            nowMs = nowMs,
        )
        if (walk == null && strictFreshFallback) {
            val queueLive = WarmReservePool.countSessionLiveInQueue(queue, WarmReserveSessionCache)
            if (queueLive <= 1) {
                simpleModeLog(
                    "SimpleMode",
                    "H37 warm_reserve_single_alive_no_fallback currentId=$currentId queueLive=$queueLive",
                )
            }
        }
        if (walk == null) {
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
            simpleModeLog(
                "SimpleMode",
                "H1 fallback_exhausted currentId=$currentId currentIndex=$currentIndex size=${queue.size} " +
                    "dead=${ProbePoolEligibility.countDead(probeStates)} jail=${ProbePoolEligibility.countJailed(probeStates)}",
            )
            return null
        }
        return FallbackPick(
            nextId = walk.nextId,
            nextIndex = walk.nextIndex,
            queueSize = queue.size,
            source = "queue_walk",
            walkStats = walk,
        )
    }

    private fun finalizeFallbackQueueOrder(
        rankedIds: List<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        proxies: List<ProxyEntity>,
        userMode: UserPoolMode,
    ): List<Long> {
        var queue = ProbePoolEligibility.orderFallbackQueue(rankedIds, probeStates)
        if (BuiltinFallbackCapPolicy.shouldApply(userMode)) {
            queue = BuiltinFallbackCapPolicy.applyCap(queue, proxies.associateBy { it.id })
        }
        return queue
    }

    fun clearPersistedFallbackQueueIfNeeded(reason: String) {
        if (!UserPoolPolicy.shouldClearPersistedFallbackQueue()) return
        if (DataStore.autoSelectFallbackQueue.isBlank()) return
        DataStore.autoSelectFallbackQueue = ""
        DataStore.autoSelectFallbackIndex = 0
        simpleModeLog(
            "SimpleMode",
            "H39 user_pool_queue_cleared reason=$reason mode=${UserPoolPolicy.effectiveMode().name}",
        )
    }

    private fun filterFallbackQueueForUserPool(rawQueue: List<Long>): List<Long> {
        if (rawQueue.isEmpty()) return rawQueue
        val mode = UserPoolPolicy.effectiveMode()
        if (mode == UserPoolMode.OFF || mode == UserPoolMode.PRIORITY) return rawQueue
        val userTag = runBlocking {
            UserSubscriptionTag.resolve(
                SagerDatabase.proxyDao.getAll(),
                SagerDatabase.groupDao.allGroups().first(),
            )
        }
        val filtered = UserPoolPolicy.filterProxyIds(mode, rawQueue, userTag.userProxyIds)
        if (filtered.size != rawQueue.size) {
            simpleModeLog(
                "SimpleMode",
                "H39 user_pool_queue_filtered mode=${mode.name} before=${rawQueue.size} after=${filtered.size}",
            )
        }
        return filtered
    }

    private fun commitFallbackSelection(currentId: Long, picked: FallbackPick) {
        sessionFallbackSteps.incrementAndGet()
        DataStore.autoSelectFallbackIndex = picked.nextIndex
        DataStore.selectedProxy = picked.nextId
        AutoServerSelectorSessionFallback.syncIndexForConnected(picked.nextId)
        setSimpleModeActivity("Trying next server ${picked.nextIndex + 1}/${picked.queueSize}")
        Logs.w("AutoSelect fallback: move to profile ${picked.nextId}")
        when (picked.source) {
            "warm_live" -> simpleModeLog(
                "SimpleMode",
                "H37 warm_reserve_fallback_session_live currentId=$currentId nextId=${picked.nextId}",
            )
            else -> {
                val walk = picked.walkStats
                simpleModeDebugEvent(
                    runId = "run1",
                    hypothesisId = "H1",
                    location = "AutoServerSelector.kt:tryMoveToFallback",
                    message = "fallback moved",
                    data = mapOf(
                        "currentId" to currentId.toString(),
                        "nextId" to picked.nextId.toString(),
                        "nextIndex" to picked.nextIndex.toString(),
                        "queueSize" to picked.queueSize.toString(),
                    ),
                )
                simpleModeLog(
                    "SimpleMode",
                    "H1 fallback_moved currentId=$currentId nextId=${picked.nextId} nextIndex=${picked.nextIndex} " +
                        "size=${picked.queueSize} sessionStep=${sessionFallbackSteps.get()} " +
                        if (walk != null) {
                            "skip=jail:${walk.skippedJail} dead:${walk.skippedDead} cooldown:${walk.skippedCooldown} " +
                                "notFresh:${walk.skippedNotFresh} warmFailed:${walk.skippedWarmFailed}"
                        } else {
                            ""
                        },
                )
            }
        }
    }

    private fun reprobeFallbackCandidate(profileId: Long): Boolean {
        val wlOnly = DataStore.activeWhitelistRestrictedNetwork
        val proxy = runBlocking { SagerDatabase.proxyDao.getById(profileId) } ?: return false
        val budgetMs = if (wlOnly) {
            Probe2kDefaults.WL_FALLBACK_REPROBE_BUDGET_MS
        } else {
            Probe2kDefaults.FALLBACK_REPROBE_BUDGET_MS
        }
        val delayMs = runBlocking {
            withTimeoutOrNull(budgetMs) {
                DirectProfileUrlProbe.urlTestDelay(proxy, whitelistOnly = wlOnly)
            }
        }
        val ok = delayMs != null && delayMs > 0
        simpleModeLog(
            "SimpleMode",
            "H30 fallback_reprobe id=$profileId ok=$ok${delayMs?.let { " ms=$it" } ?: ""} wl=$wlOnly",
        )
        return ok
    }

    fun recordHealthProbeFailure(
        profileId: Long,
        error: String?,
        whitelistOnly: Boolean = DataStore.activeWhitelistRestrictedNetwork,
        probeUrl: String? = null,
    ) {
        recordProbeFailure(profileId, SimpleModeHealthRoute.probeFailureSkipReason(error, whitelistOnly, probeUrl))
    }

    fun syncFallbackIndexForConnected(profileId: Long) {
        AutoServerSelectorSessionFallback.syncIndexForConnected(profileId)
    }

    fun applyManualSwitch(profileId: Long) {
        if (profileId <= 0L) return
        val previousId = DataStore.selectedProxy
        DataStore.selectedProxy = profileId
        AutoServerSelectorSessionFallback.syncIndexForConnected(profileId)
        simpleModeLog(
            "SimpleMode",
            "H37 manual_warm_switch from=$previousId to=$profileId",
        )
    }

    fun markConnected(profileId: Long, recordUrlVerified: Boolean = true) {
        sessionFallbackSteps.set(0)
        DataStore.autoSelectLastKnownGood = profileId
        recentProbeFailures.remove(profileId)
        AutoServerSelectorProbePolicy.clearDegradedProfile(profileId)
        if (recordUrlVerified) {
            AutoServerSelectorProbePolicy.recordPostConnectUrlVerified(profileId)
        }
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
        AutoServerSelectorProbePolicy.recordDegradedProfile(profileId)
        simpleModeLog("SimpleMode", "H32 probe_failure_recorded profileId=$profileId")
        if (DataStore.probe2kPersistenceEnabled) {
            runBlocking { ProxyProbeStateStore.recordFailure(profileId) }
        }
    }

    private fun warmProbeStateRank(
        probeStates: Map<Long, ProxyProbeState>,
        profileId: Long,
        warmRankingEnabled: Boolean = DataStore.probe2kWarmRankingEnabled,
    ): Int {
        if (!warmRankingEnabled) return 0
        return ProxyProbeStateStore.probeStateRank(probeStates[profileId])
    }

    private fun failureCooldownSnapshot(
        ids: Collection<Long>,
        cachedDegradedProfileId: Long = DataStore.autoSelectLastDegradedProfileId,
        cachedDegradedAt: Long = DataStore.autoSelectLastDegradedAt,
    ): Set<Long> =
        ids.filterTo(mutableSetOf()) { isInFailureCooldown(it, cachedDegradedProfileId, cachedDegradedAt) }

    private fun isInFailureCooldown(
        profileId: Long,
        cachedDegradedProfileId: Long = DataStore.autoSelectLastDegradedProfileId,
        cachedDegradedAt: Long = DataStore.autoSelectLastDegradedAt,
    ): Boolean {
        if (AutoServerSelectorProbePolicy.isRecentlyDegraded(profileId, cachedDegradedProfileId, cachedDegradedAt)) {
            return true
        }
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
        poolMode: ConnectPoolPolicy.PoolBuildMode,
        wlUrlProbes: Boolean,
        subscriptionWlIds: Set<Long> = emptySet(),
        userProxyIds: Set<Long> = emptySet(),
        userMode: UserPoolMode = UserPoolMode.OFF,
        probeStates: Map<Long, ProxyProbeState>? = null,
    ): Long? {
        val goodId = DataStore.autoSelectLastKnownGood
        if (goodId <= 0L || !AutoServerSelectorProbePolicy.isLastKnownGoodUrlFresh(goodId)) {
            return null
        }
        if (!UserPoolPolicy.lkgAllowed(userMode, goodId, userProxyIds)) {
            return null
        }
        val good = proxies.find { it.id == goodId } ?: return null
        if (isInFailureCooldown(goodId)) return null
        ensurePrepareCurrent(session)
        setSimpleModeActivity("Verifying last server…")
        val lkgTier = when {
            wlUrlProbes -> SimpleModeHealthRoute.ProbeTier.CONFIRM
            SimpleModeHealthRoute.messengerProbeRequired(false) ->
                SimpleModeHealthRoute.ProbeTier.PRIMARY
            else -> SimpleModeHealthRoute.ProbeTier.CONFIRM
        }
        val lkgDelay = DirectProfileUrlProbe.urlTestDelay(
            good,
            whitelistOnly = wlUrlProbes,
            tier = lkgTier,
        ) ?: return null
        if (lkgDelay > 0) {
            ensurePrepareCurrent(session)
            simpleModeLog(
                "SimpleMode",
                "H26 lkg_fast_path_early_return good=$goodId delayMs=$lkgDelay",
            )
            return finalizeRankedSelection(
                proxies = proxies,
                priorityFirstIds = priorityFirstIds,
                selectedBefore = selectedBefore,
                quickProbePings = emptyMap(),
                urlTestDelays = mapOf(goodId to lkgDelay),
                preferId = goodId,
                poolMode = poolMode,
                subscriptionWlIds = subscriptionWlIds,
                userProxyIds = userProxyIds,
                userMode = userMode,
                probeStates = probeStates,
            )
        }
        ensurePrepareCurrent(session)
        val urlPool = buildStratifiedUrlPool(
            proxies = listOf(good) + proxies.filter { it.id != goodId },
            cap = 12,
            priorityFirstIds = priorityFirstIds + goodId,
            poolMode = poolMode,
            subscriptionWlIds = subscriptionWlIds,
            userProxyIds = userProxyIds,
            userMode = userMode,
        )
        val urlDelays = if (urlPool.size <= 1) {
            emptyMap()
        } else {
            urlTestTopCandidates(
                urlPool,
                probeConcurrency(wlUrlProbes),
                session,
                whitelistBuiltinOnly = wlUrlProbes,
                poolMode = poolMode,
            )
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
                poolMode = poolMode,
                subscriptionWlIds = subscriptionWlIds,
                userProxyIds = userProxyIds,
                userMode = userMode,
                probeStates = probeStates,
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
            poolMode = poolMode,
            subscriptionWlIds = subscriptionWlIds,
            userProxyIds = userProxyIds,
            userMode = userMode,
            probeStates = probeStates,
        )
    }

    private fun finalizeRankedSelection(
        proxies: List<ProxyEntity>,
        priorityFirstIds: Set<Long>,
        selectedBefore: Long,
        quickProbePings: Map<Long, Int>,
        urlTestDelays: Map<Long, Int>,
        preferId: Long? = null,
        poolMode: ConnectPoolPolicy.PoolBuildMode = ConnectPoolPolicy.PoolBuildMode.OPEN,
        subscriptionWlIds: Set<Long> = emptySet(),
        userProxyIds: Set<Long> = emptySet(),
        userMode: UserPoolMode = UserPoolMode.OFF,
        probeStates: Map<Long, ProxyProbeState>? = null,
    ): Long {
        val t0 = System.currentTimeMillis()
        val cooldownIds = failureCooldownSnapshot(proxies.map { it.id })
        val tCooldown = System.currentTimeMillis()
        val cachedLastKnownGood = DataStore.autoSelectLastKnownGood
        val cachedWarmRankingEnabled = DataStore.probe2kWarmRankingEnabled
        val ranked = proxies.sortedWith(
            compareBy<ProxyEntity> { if (it.id == preferId) 0 else 1 }
                .thenBy {
                    if (it.id in cooldownIds && it.id !in urlTestDelays) 1 else 0
                }
                .thenBy {
                    compositeSelectionScore(
                        it,
                        urlTestDelays,
                        quickProbePings,
                        emptyMap(),
                        poolUsesWlUrlProbes(poolMode),
                        cachedWarmRankingEnabled,
                    )
                }
                .thenBy { statusRank(it.status) }
                .thenBy { pingRank(it.ping) }
                .thenByDescending { throughputRank(it) }
                .thenByDescending { it.id == cachedLastKnownGood }
                .thenBy { if (it.id in priorityFirstIds) 0 else 1 }
                .thenBy {
                    ConnectPoolPolicy.selectionRank(
                        it.id,
                        subscriptionWlIds,
                        poolMode,
                        userProxyIds,
                        userMode,
                    )
                }
                .thenBy { it.userOrder }
                .thenBy { it.id },
        ).map { it.id }
        val tSort = System.currentTimeMillis()
        val resolvedProbeStates = probeStates ?: if (DataStore.probe2kPersistenceEnabled) {
            runBlocking { ProxyProbeStateStore.loadMap(proxies.map { it.id }) }
        } else {
            emptyMap()
        }
        val tLoadMap = System.currentTimeMillis()
        val ordered = finalizeFallbackQueueOrder(ranked, resolvedProbeStates, proxies, userMode)
        val tQueue = System.currentTimeMillis()
        DataStore.autoSelectFallbackQueue = ordered.joinToString(",")
        DataStore.autoSelectFallbackIndex = 0
        sessionFallbackSteps.set(0)
        val tWrite = System.currentTimeMillis()
        val best = ProbePoolEligibility.firstViableInQueue(
            rankedIds = ordered,
            probeStates = resolvedProbeStates,
            inRecentFailureCooldown = { id -> isInFailureCooldown(id) && id !in urlTestDelays },
        ) ?: ordered.first()
        val tViable = System.currentTimeMillis()
        simpleModeLog(
            "SimpleMode",
            "H26 finalize_cooldown elapsedMs=${tCooldown - t0} sortMs=${tSort - tCooldown} " +
                "probeMapMs=${tLoadMap - tSort} fallbackQueueMs=${tQueue - tLoadMap} " +
                "datastoreWriteMs=${tWrite - tQueue} firstViableMs=${tViable - tWrite} " +
                "totalMs=${tViable - t0} probes=${resolvedProbeStates.size}",
        )
        if (selectedBefore != best) {
            DataStore.selectedProxy = best
        }
        return best
    }

    /**
     * Picks URL-test candidates round-robin across subscription groups so one group
     * does not monopolize the probe budget.
     */
    private fun stratifiedListRotationOffset(
        proxies: List<ProxyEntity>,
        probeStates: Map<Long, ProxyProbeState>,
    ): Int {
        val byGroup = proxies.groupBy { it.groupId }
        if (byGroup.size != 1) return 0
        val list = byGroup.values.first()
        if (list.size <= 80) return 0
        val tick = probeStates.values.sumOf { it.lastCheckedAt }
        return (tick % list.size).toInt()
    }

    private fun buildStratifiedUrlPool(
        proxies: List<ProxyEntity>,
        cap: Int,
        priorityFirstIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState> = emptyMap(),
        poolMode: ConnectPoolPolicy.PoolBuildMode = ConnectPoolPolicy.PoolBuildMode.OPEN,
        subscriptionWlIds: Set<Long> = emptySet(),
        userProxyIds: Set<Long> = emptySet(),
        userMode: UserPoolMode = UserPoolMode.OFF,
        warmRankingEnabled: Boolean = DataStore.probe2kWarmRankingEnabled,
    ): List<ProxyEntity> {
        if (proxies.isEmpty() || cap <= 0) return emptyList()
        val rotation = stratifiedListRotationOffset(proxies, probeStates)
        val byGroup = proxies.groupBy { it.groupId }
        val groupQueues = byGroup.mapValues { (_, list) ->
            val sorted = list.sortedWith(
                heuristicPreTcpOrder(
                    priorityFirstIds = priorityFirstIds,
                    probeStates = probeStates,
                    poolMode = poolMode,
                    subscriptionWlIds = subscriptionWlIds,
                    userProxyIds = userProxyIds,
                    userMode = userMode,
                    warmRankingEnabled = warmRankingEnabled,
                ),
            )
            if (rotation <= 0 || sorted.size <= 1) {
                sorted.toMutableList()
            } else {
                val offset = rotation % sorted.size
                (sorted.drop(offset) + sorted.take(offset)).toMutableList()
            }
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

    /**
     * WL URL-wave pool: strict alive-first order (this-run TCP pings), then priority, then
     * stale heuristics. Unstratified on purpose — stratification rotates groups and pushes the
     * alive candidates out of the early-exit reach; the wave cap covers the whole selectable
     * pool, so diversity matters less than hitting the first working tunnel fast.
     */
    internal fun buildWlUrlWavePool(
        connectPool: List<ProxyEntity>,
        quickProbePings: Map<Long, Int>,
        priorityFirstIds: Set<Long>,
        urlTestCap: Int,
        extraTcp: Int,
    ): List<ProxyEntity> {
        val cap = (urlTestCap + extraTcp).coerceAtMost(connectPool.size)
        if (cap <= 0) return emptyList()
        return connectPool.sortedWith(
            compareBy<ProxyEntity> { if (quickProbePings.containsKey(it.id)) 0 else 1 }
                .thenBy { quickProbePings[it.id] ?: Int.MAX_VALUE }
                .thenBy { if (it.id in priorityFirstIds) 0 else 1 }
                .thenBy { statusRank(it.status) }
                .thenBy { pingRank(it.ping) }
                .thenByDescending { throughputRank(it) }
                .thenBy { it.id },
        ).take(cap)
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
        whitelistBuiltinOnly: Boolean = false,
        warmRankingEnabled: Boolean = DataStore.probe2kWarmRankingEnabled,
    ): Int {
        val tcp = quickProbePings[proxy.id]?.takeIf { it > 0 }
        val url = urlTestDelays[proxy.id]?.takeIf { it > 0 }
        if (whitelistBuiltinOnly) {
            if (url != null) return url
            val persistedUrl = probeStates[proxy.id]?.lastUrlMs?.takeIf { it > 0 }
            if (persistedUrl != null) return persistedUrl
            if (tcp != null) return Int.MAX_VALUE / 2 + tcp
            if (!warmRankingEnabled) return Int.MAX_VALUE / 4
            return ProxyProbeStateStore.persistedDelayScore(probeStates[proxy.id])
        }
        val live = when {
            url != null -> url
            tcp != null -> {
                val syntheticUrl = (tcp * 3).coerceIn(40, 900)
                10 * tcp + syntheticUrl
            }
            else -> null
        }
        if (live != null) return live
        if (!warmRankingEnabled) return Int.MAX_VALUE / 4
        return ProxyProbeStateStore.persistedDelayScore(probeStates[proxy.id])
    }

    /** Order used to start URL tests in parallel with TCP (no TCP results yet). */
    internal fun heuristicPreTcpOrder(
        priorityFirstIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState> = emptyMap(),
        poolMode: ConnectPoolPolicy.PoolBuildMode = ConnectPoolPolicy.PoolBuildMode.OPEN,
        subscriptionWlIds: Set<Long> = emptySet(),
        userProxyIds: Set<Long> = emptySet(),
        userMode: UserPoolMode = UserPoolMode.OFF,
        warmRankingEnabled: Boolean,
    ): Comparator<ProxyEntity> =
        compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
            .thenBy { warmProbeStateRank(probeStates, it.id, warmRankingEnabled) }
            .thenBy { statusRank(it.status) }
            .thenBy { pingRank(it.ping) }
            .thenByDescending { throughputRank(it) }
            .thenBy {
                ConnectPoolPolicy.selectionRank(
                    it.id,
                    subscriptionWlIds,
                    poolMode,
                    userProxyIds,
                    userMode,
                )
            }
            .thenBy { it.userOrder }
            .thenBy { it.id }

    private fun selectBestProfile(
        rankedFinal: List<Long>,
        profilesById: Map<Long, ProxyEntity>,
        probeStates: Map<Long, ProxyProbeState>,
        wlUrlProbes: Boolean,
        urlTestDelays: Map<Long, Int>,
        quickProbePings: Map<Long, Int>,
    ): Long {
        if (wlUrlProbes) {
            return PrepareConnectSelection.selectBestWlProfile(
                rankedFinal = rankedFinal,
                profilesById = profilesById,
                probeStates = probeStates,
                urlTestDelays = urlTestDelays,
                isInFailureCooldown = { id -> isInFailureCooldown(id) && id !in urlTestDelays },
            )
        }
        return PrepareConnectSelection.selectBestOpenProfile(
            rankedFinal = rankedFinal,
            profilesById = profilesById,
            probeStates = probeStates,
            urlTestDelays = urlTestDelays,
            quickProbePings = quickProbePings,
            isInFailureCooldown = { id -> isInFailureCooldown(id) && id !in urlTestDelays },
        )
    }

    internal fun urlTestEarlyExitTarget(poolMode: ConnectPoolPolicy.PoolBuildMode, whitelistBuiltinOnly: Boolean): Int =
        when {
            poolMode == ConnectPoolPolicy.PoolBuildMode.WL_SUBSCRIPTION -> WL_SUBSCRIPTION_URL_PROBE_EARLY_EXIT
            whitelistBuiltinOnly -> WL_URL_PROBE_EARLY_EXIT
            poolMode == ConnectPoolPolicy.PoolBuildMode.MERGED -> 1
            poolMode == ConnectPoolPolicy.PoolBuildMode.OPEN -> OPEN_URL_PROBE_EARLY_EXIT
            else -> Int.MAX_VALUE
        }

    private suspend fun urlTestTopCandidates(
        candidates: List<ProxyEntity>,
        concurrency: Int,
        session: PrepareSession,
        whitelistBuiltinOnly: Boolean = false,
        poolMode: ConnectPoolPolicy.PoolBuildMode = ConnectPoolPolicy.PoolBuildMode.OPEN,
        tier: SimpleModeHealthRoute.ProbeTier = SimpleModeHealthRoute.ProbeTier.PRIMARY,
        forcePerProfile: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<Long, Int> = coroutineScope {
        val total = candidates.size
        if (total == 0) return@coroutineScope emptyMap()
        val messengerRequired = SimpleModeHealthRoute.messengerProbeRequired(whitelistBuiltinOnly)
        val useBatch = !forcePerProfile && (whitelistBuiltinOnly || total > 24 ||
            (messengerRequired && !whitelistBuiltinOnly && total > 12))
        val semaphore = Semaphore(concurrency)
        val result = HashMap<Long, Int>()
        if (useBatch && tier == SimpleModeHealthRoute.ProbeTier.PRIMARY) {
            if (!isPrepareCurrent(session)) return@coroutineScope emptyMap()
            val part = PrepareTestConfigBuilder.partitionForBatch(candidates)
            val batchSlice = part.batchable.take(URL_BATCH_CAP)
            var batchUsable = false
            if (batchSlice.isNotEmpty()) {
                val batch = PrepareGroupUrlProbe.urlTestDelays(
                    batchSlice,
                    whitelistOnly = whitelistBuiltinOnly,
                    tier = tier,
                )
                when {
                    batch == null -> Unit
                    batch.isEmpty() -> Unit
                    else -> {
                        result.putAll(batch)
                        batchUsable = true
                    }
                }
            }
            val perProfile = if (batchUsable) {
                part.pluginRequired + part.batchable.drop(URL_BATCH_CAP)
            } else {
                candidates
            }
            if (perProfile.isEmpty()) {
                onProgress(total, total)
                return@coroutineScope result.toMap()
            }
            urlTestPerProfile(
                perProfile = perProfile,
                total = total,
                concurrency = concurrency,
                session = session,
                whitelistBuiltinOnly = whitelistBuiltinOnly,
                tier = tier,
                onProgress = onProgress,
                result = result,
                semaphore = semaphore,
                earlyExitTarget = urlTestEarlyExitTarget(poolMode, whitelistBuiltinOnly),
            )
            return@coroutineScope result.toMap()
        }
        urlTestPerProfile(
            perProfile = candidates,
            total = total,
            concurrency = concurrency,
            session = session,
            whitelistBuiltinOnly = whitelistBuiltinOnly,
            tier = tier,
            onProgress = onProgress,
            result = result,
            semaphore = semaphore,
            earlyExitTarget = urlTestEarlyExitTarget(poolMode, whitelistBuiltinOnly),
        )
        result.toMap()
    }

    private suspend fun CoroutineScope.urlTestPerProfile(
        perProfile: List<ProxyEntity>,
        total: Int,
        concurrency: Int,
        session: PrepareSession,
        whitelistBuiltinOnly: Boolean,
        tier: SimpleModeHealthRoute.ProbeTier,
        onProgress: (done: Int, total: Int) -> Unit,
        result: HashMap<Long, Int>,
        semaphore: Semaphore,
        earlyExitTarget: Int,
    ) {
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
        val jobs = perProfile.map { proxy ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    if (!isPrepareCurrent(session) || !currentCoroutineContext().isActive) {
                        return@withPermit
                    }
                    if (whitelistBuiltinOnly) {
                        synchronized(result) {
                            if (result.isNotEmpty() && result.size >= earlyExitTarget) {
                                reportProgress()
                                return@withPermit
                            }
                        }
                    } else if (synchronized(result) { result.size >= earlyExitTarget }) {
                        reportProgress()
                        return@withPermit
                    }
                    try {
                        val ms = DirectProfileUrlProbe.urlTestDelay(
                            proxy,
                            whitelistOnly = whitelistBuiltinOnly,
                            tier = tier,
                        )
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
        }
        for (job in jobs) {
            val doneNow = synchronized(result) { result.size }
            if (doneNow >= earlyExitTarget) {
                // WL (whitelistBuiltinOnly) included: after early-exit target is reached, cancel
                // the rest of the wave instead of waiting out dead in-flight probes (up to the
                // per-URL timeout). Report full progress so the UI does not stick mid-wave.
                jobs.filter { it.isActive }.forEach { it.cancel() }
                onProgress(total, total)
                break
            }
            job.join()
        }
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
            val pings = quickTcpProbe(
                probePoolOrdered,
                tcpConcurrency,
                session,
                TCP_PROBE_TIMEOUT_MS_ROUND_1,
            ) { done, total ->
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
                ConnectPoolPolicy.compactTcpBatch(probePoolOrdered, priorityFirstIds, maxTotal = tcpBatchCap)
            } else {
                ProbeScheduler.prioritizeTcpTargets(remaining, probeStates, priorityFirstIds)
                    .take(tcpBatchCap)
            }
            if (batch.isEmpty()) break
            batch.forEach { testedIds.add(it.id) }
            val roundTimeoutMs = if (round == 1) {
                TCP_PROBE_TIMEOUT_MS_ROUND_1
            } else {
                TCP_PROBE_TIMEOUT_MS_LATER_ROUNDS
            }
            simpleModeLog(
                "SimpleMode",
                "H14 tcp_probe_round round=$round batch=${batch.size} cumulative=${testedIds.size} " +
                    "pool=${connectPool.size} aliveSoFar=${merged.size} timeoutMs=$roundTimeoutMs",
            )
            val roundPings = quickTcpProbe(batch, tcpConcurrency, session, roundTimeoutMs) { done, total ->
                onProgress(round, done, total, testedIds.size, connectPool.size)
            }
            merged.putAll(roundPings)
            if (merged.isNotEmpty()) break
            if (testedIds.size >= connectPool.size) break
        }
        return TcpProbeBatchResult(merged, testedIds.size)
    }

    private data class WlProgressiveSweepResult(
        val pings: Map<Long, Int>,
        val testedCount: Int,
        val urlCandidates: List<ProxyEntity>,
        val urlDelays: Map<Long, Int>,
    )

    /**
     * BS progressive sweep: TCP rounds of [tcpBatchCap] (round 1 priority-first, further rounds
     * in warm-state order over the untested remainder); after each round the URL wave tests only
     * that round's TCP-alive candidates (per-profile, no group batch) while the next TCP round
     * already runs in parallel — the URL wave never blocks the network. The first url-ok cancels
     * the in-flight TCP round and ends the sweep; zero url-ok merges the pipelined round's pings
     * and moves on until the whole pool was covered or [TCP_PROBE_MAX_ROUNDS] rounds ran.
     * Before any TCP round, freshly verified LKG candidates (probeState.lastUrlMs with LKG
     * freshness) get a URL pre-seed — a live one returns instantly.
     */
    private suspend fun progressiveWlSweep(
        connectPool: List<ProxyEntity>,
        probePoolOrdered: List<ProxyEntity>,
        priorityFirstIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        tcpBatchCap: Int,
        urlTestCap: Int,
        extraUrlTestByTcp: Int,
        tcpConcurrency: Int,
        urlConcurrency: Int,
        session: PrepareSession,
        poolMode: ConnectPoolPolicy.PoolBuildMode,
    ): WlProgressiveSweepResult {
        val pings = LinkedHashMap<Long, Int>()
        val urlDelays = LinkedHashMap<Long, Int>()
        val urlCandidates = ArrayList<ProxyEntity>()
        val urlTestedIds = HashSet<Long>()
        val testedIds = LinkedHashSet<Long>()
        val maxRounds = ((connectPool.size + tcpBatchCap - 1) / tcpBatchCap)
            .coerceIn(1, TCP_PROBE_MAX_ROUNDS)

        val lkgCandidates = connectPool
            .filter { proxy ->
                val state = probeStates[proxy.id]
                state != null && state.lastUrlMs > 0 &&
                    AutoServerSelectorProbePolicy.isLastKnownGoodUrlFresh(proxy.id)
            }
            .sortedWith(
                compareBy<ProxyEntity> { if (it.id in priorityFirstIds) 0 else 1 }
                    .thenBy { it.id },
            )
        if (lkgCandidates.isNotEmpty()) {
            lkgCandidates.forEach { urlTestedIds.add(it.id) }
            setSimpleModeActivity("Testing URL 0/${lkgCandidates.size}")
            simpleModeLog(
                "SimpleMode",
                "H17 urltest_started candidates=${lkgCandidates.size} mode=wl_lkg_preseed",
            )
            val lkgDelays = urlTestTopCandidates(
                lkgCandidates,
                urlConcurrency,
                session,
                whitelistBuiltinOnly = true,
                poolMode = poolMode,
                forcePerProfile = true,
            ) { done, total ->
                setSimpleModeActivity("Testing URL $done/$total")
                Probe2kProgress.publishScan(done, total)
            }
            if (lkgDelays.isNotEmpty()) {
                return WlProgressiveSweepResult(
                    pings = pings,
                    testedCount = 0,
                    urlCandidates = lkgCandidates,
                    urlDelays = lkgDelays,
                )
            }
        }

        val firstBatch = ConnectPoolPolicy.compactTcpBatch(
            probePoolOrdered,
            priorityFirstIds,
            maxTotal = tcpBatchCap,
        )
        if (firstBatch.isEmpty()) {
            return WlProgressiveSweepResult(pings, testedIds.size, urlCandidates, urlDelays)
        }
        firstBatch.forEach { testedIds.add(it.id) }
        simpleModeLog(
            "SimpleMode",
            "H14 tcp_probe_round round=1 batch=${firstBatch.size} cumulative=${testedIds.size} " +
                "pool=${connectPool.size} aliveSoFar=${pings.size} mode=wl_progressive " +
                "timeoutMs=$TCP_PROBE_TIMEOUT_MS_ROUND_1",
        )
        var currentPings = quickTcpProbe(
            firstBatch,
            tcpConcurrency,
            session,
            TCP_PROBE_TIMEOUT_MS_ROUND_1,
        ) { _, _ ->
            val cumulative = testedIds.size.coerceAtMost(connectPool.size)
            setSimpleModeActivity("Testing TCP $cumulative/${connectPool.size}")
            Probe2kProgress.publishScan(cumulative, connectPool.size)
        }
        pings.putAll(currentPings)

        var round = 1
        var currentBatch = firstBatch
        coroutineScope {
            while (round <= maxRounds) {
            ensurePrepareCurrent(session)
            val wavePool = buildWlUrlWavePool(
                connectPool = currentBatch.filter { it.id in currentPings && it.id !in urlTestedIds },
                quickProbePings = currentPings,
                priorityFirstIds = priorityFirstIds,
                urlTestCap = urlTestCap,
                extraTcp = extraUrlTestByTcp,
            )
            if (wavePool.isNotEmpty()) {
                wavePool.forEach { urlTestedIds.add(it.id) }
                urlCandidates += wavePool
                setSimpleModeActivity("Testing URL 0/${wavePool.size}")
                simpleModeLog(
                    "SimpleMode",
                    "H17 urltest_started candidates=${wavePool.size} mode=wl_progressive_round round=$round " +
                        "tcpAliveRound=${currentPings.size}",
                )
            }

            val nextRound = round + 1
            val remainingNext = connectPool.filter { it.id !in testedIds }
            val nextBatch = if (nextRound <= maxRounds && remainingNext.isNotEmpty()) {
                ProbeScheduler.prioritizeTcpTargets(remainingNext, probeStates, priorityFirstIds)
                    .take(tcpBatchCap)
            } else {
                emptyList()
            }
            if (nextBatch.isNotEmpty()) {
                nextBatch.forEach { testedIds.add(it.id) }
                simpleModeLog(
                    "SimpleMode",
                    "H14 tcp_probe_round round=$nextRound batch=${nextBatch.size} cumulative=${testedIds.size} " +
                        "pool=${connectPool.size} aliveSoFar=${pings.size} mode=wl_progressive " +
                        "timeoutMs=$TCP_PROBE_TIMEOUT_MS_LATER_ROUNDS",
                )
            }

            val urlJob = async(Dispatchers.IO) {
                if (wavePool.isEmpty()) {
                    emptyMap()
                } else {
                    urlTestTopCandidates(
                        wavePool,
                        urlConcurrency,
                        session,
                        whitelistBuiltinOnly = true,
                        poolMode = poolMode,
                        forcePerProfile = true,
                    ) { done, total ->
                        setSimpleModeActivity("Testing URL $done/$total")
                        Probe2kProgress.publishScan(done, total)
                    }
                }
            }
            val nextTcpJob = if (nextBatch.isEmpty()) {
                null
            } else {
                async(Dispatchers.IO) {
                    quickTcpProbe(
                        nextBatch,
                        tcpConcurrency,
                        session,
                        TCP_PROBE_TIMEOUT_MS_LATER_ROUNDS,
                    ) { _, _ ->
                        val cumulative = testedIds.size.coerceAtMost(connectPool.size)
                        setSimpleModeActivity("Testing TCP $cumulative/${connectPool.size}")
                        Probe2kProgress.publishScan(cumulative, connectPool.size)
                    }
                }
            }

            val waveDelays = urlJob.await()
            if (waveDelays.isNotEmpty()) {
                nextTcpJob?.cancel()
                urlDelays.putAll(waveDelays)
                break
            }
            if (nextTcpJob == null) {
                if (testedIds.size >= connectPool.size) break
                break
            }
            val nextPings = nextTcpJob.await()
            pings.putAll(nextPings)
            if (nextPings.isEmpty() && testedIds.size >= connectPool.size) break
            round = nextRound
            currentBatch = nextBatch
            currentPings = nextPings

            }
        }
        return WlProgressiveSweepResult(
            pings = pings,
            testedCount = testedIds.size,
            urlCandidates = urlCandidates,
            urlDelays = urlDelays,
        )
    }

    private suspend fun quickTcpProbe(
        proxies: List<ProxyEntity>,
        concurrency: Int,
        session: PrepareSession,
        timeoutMs: Int = 1200,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<Long, Int> = coroutineScope {
        ProfileTcpProber.probeTcpBatch(
            proxies = proxies,
            concurrency = concurrency,
            timeoutMs = timeoutMs,
            isActive = { isPrepareCurrent(session) },
            onProgress = onProgress,
        )
    }
}
