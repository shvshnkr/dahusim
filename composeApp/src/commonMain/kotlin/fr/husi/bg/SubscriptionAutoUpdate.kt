package fr.husi.bg

import fr.husi.database.DataStore
import fr.husi.simplemode.probeSimpleModeNetwork
import fr.husi.utils.simpleModeLog
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubUpdateState
import fr.husi.database.SubscriptionUpdateEligibility
import fr.husi.database.SubscriptionUpdateErrorClass
import fr.husi.database.SubscriptionUpdateStateStore
import fr.husi.group.GroupUpdateResult
import fr.husi.group.GroupUpdater
import fr.husi.ktx.Logs
import fr.husi.ktx.readableMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class SubscriptionAutoUpdatePlan(
    val repeatIntervalMinutes: Int,
    val initialDelaySeconds: Long,
)

data class SubscriptionAutoUpdateOutcome(
    val allSucceeded: Boolean,
    val transportFailuresWhileVpnConnected: Int,
    val shouldRequestUpstreamReset: Boolean,
)

enum class SubscriptionUpdateMode {
    ForegroundInteractive,
    BackgroundEco,
}

private data class AutoUpdateCandidate(
    val group: ProxyGroup,
    val repeatIntervalMinutes: Int,
    val secondsUntilDue: Long,
    val secondsUntilNextAttempt: Long,
    val updateWhenConnectedOnly: Boolean,
)

object SubscriptionAutoUpdatePlanner {

    suspend fun plan(): SubscriptionAutoUpdatePlan? {
        val subscriptions = loadAutoUpdateSubscriptions()
        val states = SubscriptionUpdateStateStore.loadMap(subscriptions.map { it.id })
        return plan(subscriptions, currentEpochSeconds(), states)
    }

    fun plan(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
    ): SubscriptionAutoUpdatePlan? {
        return plan(subscriptions, nowSeconds, emptyMap())
    }

    fun plan(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
        updateStates: Map<Long, fr.husi.database.SubscriptionUpdateState>,
    ): SubscriptionAutoUpdatePlan? {
        val candidates = autoUpdateCandidates(subscriptions, nowSeconds, updateStates)
        if (candidates.isEmpty()) return null

        return SubscriptionAutoUpdatePlan(
            repeatIntervalMinutes = candidates.minOf(AutoUpdateCandidate::repeatIntervalMinutes),
            initialDelaySeconds = candidates.minOf { candidate ->
                val backoff = candidate.secondsUntilNextAttempt
                if (backoff <= 0L) candidate.secondsUntilDue else minOf(candidate.secondsUntilDue, backoff)
            },
        )
    }

    suspend fun loadAutoUpdateSubscriptions(): List<ProxyGroup> {
        return SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription!!.autoUpdate }
    }
}

object SubscriptionAutoUpdateRunner {

    private data class SingleUpdateSummary(
        val success: Boolean,
        val staleTransportFailure: Boolean,
        val groupId: Long,
        val attempted: Boolean,
    )

    suspend fun runWithResult(
        nowSeconds: Long = currentEpochSeconds(),
        mode: SubscriptionUpdateMode = SubscriptionUpdateMode.BackgroundEco,
        connectRefresh: Boolean = false,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ): SubscriptionAutoUpdateOutcome {
        return runWithResult(
            subscriptions = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions(),
            nowSeconds = nowSeconds,
            mode = mode,
            connectRefresh = connectRefresh,
            onBeforeUpdate = onBeforeUpdate,
        )
    }

    suspend fun runWithResult(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
        mode: SubscriptionUpdateMode = SubscriptionUpdateMode.BackgroundEco,
        connectRefresh: Boolean = false,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ): SubscriptionAutoUpdateOutcome {
        val due = dueSubscriptionsResolved(
            subscriptions = subscriptions,
            nowSeconds = nowSeconds,
            connectRefresh = connectRefresh,
            mode = mode,
        )
        val summaries = executeDueUpdates(due, mode, onBeforeUpdate)
        val statesAfter = SubscriptionUpdateStateStore.loadMap(summaries.map { it.groupId })
        val allSucceeded = summaries.all { summary ->
            SubscriptionUpdateEligibility.countsAsSuccessForWorker(
                attempted = summary.attempted,
                updateSucceeded = summary.success,
                groupId = summary.groupId,
                statesAfter = statesAfter,
            )
        }
        val transportFailuresWhileVpnConnected = summaries.count { it.staleTransportFailure }
        return SubscriptionAutoUpdateOutcome(
            allSucceeded = allSucceeded,
            transportFailuresWhileVpnConnected = transportFailuresWhileVpnConnected,
            shouldRequestUpstreamReset = DataStore.serviceState.connected &&
                transportFailuresWhileVpnConnected >= 2,
        )
    }

    suspend fun run(
        nowSeconds: Long = currentEpochSeconds(),
        mode: SubscriptionUpdateMode = SubscriptionUpdateMode.BackgroundEco,
        connectRefresh: Boolean = false,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ) {
        run(
            subscriptions = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions(),
            nowSeconds = nowSeconds,
            mode = mode,
            connectRefresh = connectRefresh,
            onBeforeUpdate = onBeforeUpdate,
        )
    }

    suspend fun run(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
        mode: SubscriptionUpdateMode = SubscriptionUpdateMode.BackgroundEco,
        connectRefresh: Boolean = false,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ) {
        executeDueUpdates(
            dueSubscriptionsResolved(
                subscriptions = subscriptions,
                nowSeconds = nowSeconds,
                connectRefresh = connectRefresh,
                mode = mode,
            ),
            mode,
            onBeforeUpdate,
        )
    }

    suspend fun refreshDueWithBudget(
        mode: SubscriptionUpdateMode,
        budgetMs: Long,
        nowSeconds: Long = currentEpochSeconds(),
        connectRefresh: Boolean = false,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ): SubscriptionAutoUpdateOutcome? {
        val effectiveBudgetMs = budgetMs.coerceAtLeast(0L)
        return withTimeoutOrNull(effectiveBudgetMs) {
            runWithResult(
                nowSeconds = nowSeconds,
                mode = mode,
                connectRefresh = connectRefresh,
                onBeforeUpdate = onBeforeUpdate,
            )
        }
    }

    suspend fun dueSubscriptions(
        nowSeconds: Long = currentEpochSeconds(),
        connected: Boolean = DataStore.serviceState.connected,
        connectRefresh: Boolean = false,
        mode: SubscriptionUpdateMode = SubscriptionUpdateMode.BackgroundEco,
    ): List<ProxyGroup> {
        return dueSubscriptions(
            subscriptions = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions(),
            nowSeconds = nowSeconds,
            connected = connected,
            connectRefresh = connectRefresh,
            mode = mode,
        )
    }

    fun dueSubscriptions(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
        connected: Boolean = DataStore.serviceState.connected,
        connectRefresh: Boolean = false,
        mode: SubscriptionUpdateMode = SubscriptionUpdateMode.BackgroundEco,
        updateStates: Map<Long, fr.husi.database.SubscriptionUpdateState> = emptyMap(),
    ): List<ProxyGroup> {
        val nowMs = nowSeconds * 1000L
        val states = updateStates
        val unjailGroupId = if (mode == SubscriptionUpdateMode.BackgroundEco) {
            SubscriptionUpdateEligibility.pickUnjailGroupId(states, nowMs)
        } else {
            null
        }
        val due = autoUpdateCandidates(subscriptions, nowSeconds, states).filter { candidate ->
            if (!connected && candidate.updateWhenConnectedOnly) {
                return@filter false
            }
            if (!SubscriptionUpdateEligibility.isDueForScheduledUpdate(
                    groupId = candidate.group.id,
                    secondsUntilDue = candidate.secondsUntilDue,
                    state = states[candidate.group.id],
                    nowMs = nowMs,
                    unjailGroupId = unjailGroupId,
                )
            ) {
                return@filter false
            }
            true
        }.map(AutoUpdateCandidate::group)
        val sorted = SubscriptionUpdateEligibility.sortDueQueue(due, states)
        if (connectRefresh && mode == SubscriptionUpdateMode.ForegroundInteractive) {
            return SubscriptionUpdateEligibility.sortConnectRefreshQueue(
                SubscriptionUpdateEligibility.filterConnectRefreshCandidates(sorted, states, nowMs),
                states,
            )
        }
        return sorted
    }

    suspend fun dueSubscriptionsResolved(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
        connected: Boolean = DataStore.serviceState.connected,
        connectRefresh: Boolean = false,
        mode: SubscriptionUpdateMode = SubscriptionUpdateMode.BackgroundEco,
    ): List<ProxyGroup> {
        val states = SubscriptionUpdateStateStore.loadMap(subscriptions.map { it.id })
        return dueSubscriptions(
            subscriptions = subscriptions,
            nowSeconds = nowSeconds,
            connected = connected,
            connectRefresh = connectRefresh,
            mode = mode,
            updateStates = states,
        )
    }

    fun previewParallelism(
        mode: SubscriptionUpdateMode,
        foregroundParallelism: Int,
        backgroundParallelism: Int,
    ): Int {
        return when (mode) {
            SubscriptionUpdateMode.ForegroundInteractive -> foregroundParallelism.coerceIn(1, 6)
            SubscriptionUpdateMode.BackgroundEco -> backgroundParallelism.coerceIn(1, 2)
        }
    }

    private suspend fun executeDueUpdates(
        due: List<ProxyGroup>,
        mode: SubscriptionUpdateMode,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit,
    ): List<SingleUpdateSummary> = coroutineScope {
        if (due.isEmpty()) return@coroutineScope emptyList()
        val parallelism = parallelismForMode(mode).coerceIn(1, due.size)
        val semaphore = Semaphore(parallelism)
        due.mapIndexed { index, profile ->
            async {
                semaphore.withPermit {
                    Logs.d(
                        "auto update: updating ${profile.displayName()} " +
                            "mode=$mode index=${index + 1}/${due.size}",
                    )
                    onBeforeUpdate(profile)
                    runSingleUpdate(profile)
                }
            }
        }.awaitAll()
    }

    private fun parallelismForMode(mode: SubscriptionUpdateMode): Int {
        return previewParallelism(
            mode = mode,
            foregroundParallelism = DataStore.subscriptionUpdateParallelismForeground,
            backgroundParallelism = DataStore.subscriptionUpdateParallelismBackground,
        )
    }

    private suspend fun runSingleUpdate(profile: ProxyGroup): SingleUpdateSummary {
        val first = runSingleUpdateOnce(profile, bypassVpn = false)
        if (first.staleTransportFailure && DataStore.serviceState.connected) {
            val uplink = probeSimpleModeNetwork()
            if (uplink.hasAnyInternet) {
                simpleModeLog(
                    "SimpleMode",
                    "H19 subscription_fetch_bypass_tunnel group=${profile.displayName()} " +
                        "wlOnly=${uplink.whitelistOnly}",
                )
                return runSingleUpdateOnce(profile, bypassVpn = true)
            }
        }
        return first
    }

    private suspend fun runSingleUpdateOnce(
        profile: ProxyGroup,
        bypassVpn: Boolean,
    ): SingleUpdateSummary {
        val previousBypass = SubscriptionUpdateFetchOverrides.bypassVpn
        SubscriptionUpdateFetchOverrides.bypassVpn = bypassVpn
        return try {
            runCatching {
                when (val r = GroupUpdater.executeUpdate(profile, false)) {
                    is GroupUpdateResult.Success -> {
                        SingleUpdateSummary(
                            success = true,
                            staleTransportFailure = false,
                            groupId = profile.id,
                            attempted = true,
                        )
                    }
                    is GroupUpdateResult.Failure -> {
                        SingleUpdateSummary(
                            success = false,
                            staleTransportFailure = DataStore.serviceState.connected &&
                                subscriptionMessageLooksLikeStaleTransport(r.message),
                            groupId = profile.id,
                            attempted = true,
                        )
                    }
                    else -> {
                        SingleUpdateSummary(
                            success = false,
                            staleTransportFailure = false,
                            groupId = profile.id,
                            attempted = false,
                        )
                    }
                }
            }.getOrElse { e ->
                Logs.w("auto update: update failed for ${profile.displayName()}", e)
                SingleUpdateSummary(
                    success = false,
                    staleTransportFailure = DataStore.serviceState.connected &&
                        subscriptionMessageLooksLikeStaleTransport(e.readableMessage),
                    groupId = profile.id,
                    attempted = true,
                )
            }
        } finally {
            SubscriptionUpdateFetchOverrides.bypassVpn = previousBypass
        }
    }
}

internal fun classifySubscriptionUpdateError(message: String): String {
    val m = message.lowercase()
    if (m.isBlank()) return SubscriptionUpdateErrorClass.OTHER
    if (m.contains("404") || m.contains("410") || m.contains("not found") ||
        m.contains("no such") || (m.contains("empty") && m.contains("link"))
    ) {
        return SubscriptionUpdateErrorClass.HTTP_PERMANENT
    }
    if (subscriptionMessageLooksLikeStaleTransport(message)) {
        return SubscriptionUpdateErrorClass.TRANSPORT
    }
    if (m.contains("500") || m.contains("502") || m.contains("503") || m.contains("504") ||
        m.contains("429") || m.contains("rate limit")
    ) {
        return SubscriptionUpdateErrorClass.HTTP_TRANSIENT
    }
    return SubscriptionUpdateErrorClass.OTHER
}

private fun subscriptionMessageLooksLikeStaleTransport(message: String): Boolean {
    val m = message.lowercase()
    if (m.isBlank()) return false
    return m.contains("eof") ||
        m.contains("deadline exceeded") ||
        m.contains("client.timeout exceeded") ||
        m.contains("broken pipe") ||
        m.contains("connection reset") ||
        m.contains("i/o timeout") ||
        m.contains("connection timed out") ||
        m.contains("tls handshake timeout") ||
        m.contains("unexpected eof")
}

private suspend fun autoUpdateCandidates(
    subscriptions: List<ProxyGroup>,
    nowSeconds: Long,
): List<AutoUpdateCandidate> {
    val states = SubscriptionUpdateStateStore.loadMap(subscriptions.map { it.id })
    return autoUpdateCandidates(subscriptions, nowSeconds, states)
}

private fun autoUpdateCandidates(
    subscriptions: List<ProxyGroup>,
    nowSeconds: Long,
    updateStates: Map<Long, fr.husi.database.SubscriptionUpdateState>,
): List<AutoUpdateCandidate> {
    val nowMs = nowSeconds * 1000L
    return subscriptions.map { subscription ->
        autoUpdateCandidate(subscription, nowSeconds, nowMs, updateStates[subscription.id])
    }
}

private fun autoUpdateCandidate(
    group: ProxyGroup,
    nowSeconds: Long,
    nowMs: Long,
    updateState: fr.husi.database.SubscriptionUpdateState?,
): AutoUpdateCandidate {
    val subscription = group.subscription!!
    val repeatIntervalMinutes = effectiveDelayMinutes(subscription.autoUpdateDelay)
    val secondsUntilDue = secondsUntilDue(
        lastUpdatedSeconds = subscription.lastUpdated.toLong(),
        repeatIntervalMinutes = repeatIntervalMinutes,
        nowSeconds = nowSeconds,
    )
    val nextAttemptAtMs = updateState?.nextAttemptAtMs ?: 0L
    val secondsUntilNextAttempt = if (nextAttemptAtMs <= nowMs) {
        0L
    } else {
        ((nextAttemptAtMs - nowMs) + 999L) / 1000L
    }
    return AutoUpdateCandidate(
        group = group,
        repeatIntervalMinutes = repeatIntervalMinutes,
        secondsUntilDue = secondsUntilDue,
        secondsUntilNextAttempt = secondsUntilNextAttempt,
        updateWhenConnectedOnly = subscription.updateWhenConnectedOnly,
    )
}

private fun effectiveDelayMinutes(autoUpdateDelayMinutes: Int): Int {
    return autoUpdateDelayMinutes.coerceAtLeast(1)
}

internal fun secondsUntilDue(
    lastUpdatedSeconds: Long,
    repeatIntervalMinutes: Int,
    nowSeconds: Long,
): Long {
    val elapsedSeconds = nowSeconds - lastUpdatedSeconds
    val delaySeconds = repeatIntervalMinutes.toLong() * 60L
    return (delaySeconds - elapsedSeconds).coerceAtLeast(0L)
}

internal fun currentEpochSeconds(): Long = System.currentTimeMillis() / 1000L

/** WorkManager periodic interval floor (see [SubscriptionUpdater.reconfigureUpdater]). */
internal fun androidSubscriptionPeriodicIntervalMinutes(repeatIntervalMinutes: Int): Long {
    return repeatIntervalMinutes.coerceAtLeast(15).toLong()
}
