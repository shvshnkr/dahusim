package fr.husi.bg

import fr.husi.database.DataStore
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.group.GroupUpdateResult
import fr.husi.group.GroupUpdater
import fr.husi.ktx.Logs
import fr.husi.ktx.readableMessage

data class SubscriptionAutoUpdatePlan(
    val repeatIntervalMinutes: Int,
    val initialDelaySeconds: Long,
)

data class SubscriptionAutoUpdateOutcome(
    val allSucceeded: Boolean,
    val transportFailuresWhileVpnConnected: Int,
    val shouldRequestUpstreamReset: Boolean,
)

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

    suspend fun runWithResult(
        nowSeconds: Long = currentEpochSeconds(),
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ): SubscriptionAutoUpdateOutcome {
        return runWithResult(
            subscriptions = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions(),
            nowSeconds = nowSeconds,
            onBeforeUpdate = onBeforeUpdate,
        )
    }

    suspend fun runWithResult(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ): SubscriptionAutoUpdateOutcome {
        var allSucceeded = true
        var transportFailuresWhileVpnConnected = 0
        for (profile in dueSubscriptions(subscriptions, nowSeconds)) {
            Logs.d("auto update: updating ${profile.displayName()}")
            onBeforeUpdate(profile)
            runCatching {
                when (val r = GroupUpdater.executeUpdate(profile, false)) {
                    is GroupUpdateResult.Success -> Unit
                    is GroupUpdateResult.Failure -> {
                        allSucceeded = false
                        if (DataStore.serviceState.connected &&
                            subscriptionMessageLooksLikeStaleTransport(r.message)
                        ) {
                            transportFailuresWhileVpnConnected++
                        }
                    }
                    else -> {
                        allSucceeded = false
                    }
                }
            }.onFailure { e ->
                Logs.w("auto update: update failed for ${profile.displayName()}", e)
                allSucceeded = false
                if (DataStore.serviceState.connected &&
                    subscriptionMessageLooksLikeStaleTransport(e.readableMessage)
                ) {
                    transportFailuresWhileVpnConnected++
                }
            }
        }
        return SubscriptionAutoUpdateOutcome(
            allSucceeded = allSucceeded,
            transportFailuresWhileVpnConnected = transportFailuresWhileVpnConnected,
            shouldRequestUpstreamReset = DataStore.serviceState.connected &&
                transportFailuresWhileVpnConnected >= 2,
        )
    }

    suspend fun run(
        nowSeconds: Long = currentEpochSeconds(),
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ) {
        run(
            subscriptions = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions(),
            nowSeconds = nowSeconds,
            onBeforeUpdate = onBeforeUpdate,
        )
    }

    suspend fun run(
        subscriptions: List<ProxyGroup>,
        nowSeconds: Long,
        onBeforeUpdate: suspend (ProxyGroup) -> Unit = {},
    ) {
        for (profile in dueSubscriptions(subscriptions, nowSeconds)) {
            Logs.d("auto update: updating ${profile.displayName()}")
            onBeforeUpdate(profile)
            GroupUpdater.executeUpdate(profile, false)
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
