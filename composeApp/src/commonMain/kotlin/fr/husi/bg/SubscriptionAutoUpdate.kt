package fr.husi.bg

import fr.husi.database.DataStore
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
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
    val updateWhenConnectedOnly: Boolean,
)

object SubscriptionAutoUpdatePlanner {

    suspend fun plan(): SubscriptionAutoUpdatePlan? {
        return plan(
            subscriptions = loadAutoUpdateSubscriptions(),
            nowSeconds = currentEpochSeconds(),
        )
    }

    fun plan(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
    ): SubscriptionAutoUpdatePlan? {
        val candidates = autoUpdateCandidates(subscriptions, nowSeconds)
        if (candidates.isEmpty()) return null

        return SubscriptionAutoUpdatePlan(
            repeatIntervalMinutes = candidates.minOf(AutoUpdateCandidate::repeatIntervalMinutes),
            initialDelaySeconds = candidates.minOf(AutoUpdateCandidate::secondsUntilDue),
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
    )

    suspend fun runWithResult(
        nowSeconds: Long = currentEpochSeconds(),
        mode: SubscriptionUpdateMode = SubscriptionUpdateMode.BackgroundEco,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ): SubscriptionAutoUpdateOutcome {
        return runWithResult(
            subscriptions = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions(),
            nowSeconds = nowSeconds,
            mode = mode,
            onBeforeUpdate = onBeforeUpdate,
        )
    }

    suspend fun runWithResult(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
        mode: SubscriptionUpdateMode = SubscriptionUpdateMode.BackgroundEco,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ): SubscriptionAutoUpdateOutcome {
        val due = dueSubscriptions(subscriptions, nowSeconds)
        val summaries = executeDueUpdates(due, mode, onBeforeUpdate)
        val allSucceeded = summaries.all { it.success }
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
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ) {
        run(
            subscriptions = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions(),
            nowSeconds = nowSeconds,
            mode = mode,
            onBeforeUpdate = onBeforeUpdate,
        )
    }

    suspend fun run(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
        mode: SubscriptionUpdateMode = SubscriptionUpdateMode.BackgroundEco,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ) {
        executeDueUpdates(dueSubscriptions(subscriptions, nowSeconds), mode, onBeforeUpdate)
    }

    suspend fun refreshDueWithBudget(
        mode: SubscriptionUpdateMode,
        budgetMs: Long,
        nowSeconds: Long = currentEpochSeconds(),
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ): SubscriptionAutoUpdateOutcome? {
        val effectiveBudgetMs = budgetMs.coerceAtLeast(0L)
        return withTimeoutOrNull(effectiveBudgetMs) {
            runWithResult(
                nowSeconds = nowSeconds,
                mode = mode,
                onBeforeUpdate = onBeforeUpdate,
            )
        }
    }

    suspend fun dueSubscriptions(
        nowSeconds: Long = currentEpochSeconds(),
        connected: Boolean = DataStore.serviceState.connected,
    ): List<ProxyGroup> {
        return dueSubscriptions(
            subscriptions = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions(),
            nowSeconds = nowSeconds,
            connected = connected,
        )
    }

    fun dueSubscriptions(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
        connected: Boolean = DataStore.serviceState.connected,
    ): List<ProxyGroup> {
        return autoUpdateCandidates(subscriptions, nowSeconds).filter { candidate ->
            if (!connected && candidate.updateWhenConnectedOnly) {
                return@filter false
            }
            if (candidate.secondsUntilDue > 0L) {
                Logs.d("auto update: not updating ${candidate.group.displayName()}")
                false
            } else {
                true
            }
        }.map(AutoUpdateCandidate::group)
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
        return runCatching {
            when (val r = GroupUpdater.executeUpdate(profile, false)) {
                is GroupUpdateResult.Success -> {
                    SingleUpdateSummary(success = true, staleTransportFailure = false)
                }
                is GroupUpdateResult.Failure -> {
                    SingleUpdateSummary(
                        success = false,
                        staleTransportFailure = DataStore.serviceState.connected &&
                            subscriptionMessageLooksLikeStaleTransport(r.message),
                    )
                }
                else -> {
                    SingleUpdateSummary(success = false, staleTransportFailure = false)
                }
            }
        }.getOrElse { e ->
            Logs.w("auto update: update failed for ${profile.displayName()}", e)
            SingleUpdateSummary(
                success = false,
                staleTransportFailure = DataStore.serviceState.connected &&
                    subscriptionMessageLooksLikeStaleTransport(e.readableMessage),
            )
        }
    }
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

private fun autoUpdateCandidates(
    subscriptions: List<ProxyGroup>,
    nowSeconds: Long,
): List<AutoUpdateCandidate> {
    return subscriptions.map { subscription ->
        autoUpdateCandidate(subscription, nowSeconds)
    }
}

private fun autoUpdateCandidate(
    group: ProxyGroup,
    nowSeconds: Long,
): AutoUpdateCandidate {
    val subscription = group.subscription!!
    val repeatIntervalMinutes = effectiveDelayMinutes(subscription.autoUpdateDelay)
    return AutoUpdateCandidate(
        group = group,
        repeatIntervalMinutes = repeatIntervalMinutes,
        secondsUntilDue = secondsUntilDue(
            lastUpdatedSeconds = subscription.lastUpdated.toLong(),
            repeatIntervalMinutes = repeatIntervalMinutes,
            nowSeconds = nowSeconds,
        ),
        updateWhenConnectedOnly = subscription.updateWhenConnectedOnly,
    )
}

private fun effectiveDelayMinutes(autoUpdateDelayMinutes: Int): Int {
    return autoUpdateDelayMinutes.coerceAtLeast(1)
}

private fun secondsUntilDue(
    lastUpdatedSeconds: Long,
    repeatIntervalMinutes: Int,
    nowSeconds: Long,
): Long {
    val elapsedSeconds = nowSeconds - lastUpdatedSeconds
    val delaySeconds = repeatIntervalMinutes.toLong() * 60L
    return (delaySeconds - elapsedSeconds).coerceAtLeast(0L)
}

internal fun currentEpochSeconds(): Long = System.currentTimeMillis() / 1000L
