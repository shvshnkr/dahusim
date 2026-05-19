package fr.husi.simplemode

import fr.husi.bg.ServiceState
import fr.husi.bg.SubscriptionAutoUpdateRunner
import fr.husi.bg.SubscriptionUpdateMode
import fr.husi.database.AutoServerSelectorProbePolicy
import fr.husi.database.DataStore
import fr.husi.database.SagerDatabase
import fr.husi.ktx.Logs
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Deferred subscription refresh while the user is already connected (simple mode).
 * Avoids heavy work on flaky whitelist-only networks unless the tunnel looks healthy.
 */
object SimpleModeConnectedMaintenance {

    private const val SETTLE_DELAY_MS = 45_000L
    private const val BACKGROUND_SUB_REFRESH_INTERVAL_MS = 45L * 60 * 1000
    private const val WL_POST_CONNECT_LATENCY_MAX_MS = 800
    private const val OPEN_NET_REFRESH_BUDGET_MS = 25_000L
    private const val WL_REFRESH_BUDGET_MS = 8_000L

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)
    private var job: Job? = null

    fun scheduleAfterHealthyConnect(
        profileId: Long,
        postConnectLatencyMs: Int,
        connectWhitelistOnly: Boolean,
        googleReachable: Boolean,
        whitelistSourceReachable: Boolean,
    ) {
        if (!DataStore.simpleMode) return
        job?.cancel()
        job = scope.launch {
            delay(SETTLE_DELAY_MS)
            if (!DataStore.serviceState.connected) return@launch
            runBackgroundSubscriptionRefresh(
                profileId = profileId,
                postConnectLatencyMs = postConnectLatencyMs,
                connectWhitelistOnly = connectWhitelistOnly,
                googleReachable = googleReachable,
                whitelistSourceReachable = whitelistSourceReachable,
            )
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun runBackgroundSubscriptionRefresh(
        profileId: Long,
        postConnectLatencyMs: Int,
        connectWhitelistOnly: Boolean,
        googleReachable: Boolean,
        whitelistSourceReachable: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val lastRefresh = DataStore.simpleModeLastBackgroundSubRefreshAt
        if (lastRefresh > 0L && now - lastRefresh < BACKGROUND_SUB_REFRESH_INTERVAL_MS) {
            simpleModeLog(
                "SimpleMode",
                "H29 background_sub_refresh_skipped reason=interval profileId=$profileId",
            )
            return
        }

        val whitelistNow = DataStore.activeWhitelistRestrictedNetwork
        if (whitelistNow) {
            if (!whitelistChannelConfident(
                    postConnectLatencyMs = postConnectLatencyMs,
                    googleReachable = googleReachable,
                    whitelistSourceReachable = whitelistSourceReachable,
                )
            ) {
                simpleModeLog(
                    "SimpleMode",
                    "H29 background_sub_refresh_skipped reason=wl_not_confident " +
                        "latencyMs=$postConnectLatencyMs google=$googleReachable wlSrc=$whitelistSourceReachable",
                )
                return
            }
        } else if (connectWhitelistOnly) {
            simpleModeLog(
                "SimpleMode",
                "H29 background_sub_refresh_skipped reason=connected_on_wl_open_now profileId=$profileId",
            )
            return
        }

        val budgetMs = if (whitelistNow) WL_REFRESH_BUDGET_MS else OPEN_NET_REFRESH_BUDGET_MS
        val proxiesBefore = SagerDatabase.proxyDao.getAll()
        val hashBefore = AutoServerSelectorProbePolicy.computeProxyIdSetHash(proxiesBefore)

        simpleModeLog(
            "SimpleMode",
            "H29 background_sub_refresh_start profileId=$profileId wlNow=$whitelistNow budgetMs=$budgetMs",
        )
        val outcome = runCatching {
            SubscriptionAutoUpdateRunner.refreshDueWithBudget(
                mode = SubscriptionUpdateMode.BackgroundEco,
                budgetMs = budgetMs,
            )
        }.getOrElse {
            Logs.w("simple mode background subscription refresh", it)
            null
        }
        if (outcome?.allSucceeded == true) {
            DataStore.simpleModeLastBackgroundSubRefreshAt = System.currentTimeMillis()
        }

        val proxiesAfter = SagerDatabase.proxyDao.getAll()
        val hashAfter = AutoServerSelectorProbePolicy.computeProxyIdSetHash(proxiesAfter)
        simpleModeLog(
            "SimpleMode",
            "H29 background_sub_refresh_done outcome=$outcome hashChanged=${hashBefore != hashAfter} " +
                "poolBefore=${proxiesBefore.size} poolAfter=${proxiesAfter.size}",
        )
    }

    private fun whitelistChannelConfident(
        postConnectLatencyMs: Int,
        googleReachable: Boolean,
        whitelistSourceReachable: Boolean,
    ): Boolean {
        if (postConnectLatencyMs <= 0) return false
        if (postConnectLatencyMs > WL_POST_CONNECT_LATENCY_MAX_MS) return false
        return googleReachable || whitelistSourceReachable
    }
}
