package fr.husi.bg

import fr.husi.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Periodic subscription and route-asset updates while the desktop JVM is alive.
 * No OS scheduler (schtasks/systemd/launchd).
 */
internal object DesktopBackgroundCoordinator {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)
    private var subscriptionLoopJob: Job? = null
    private var routeAssetLoopJob: Job? = null

    fun start() {
        reconfigureSubscriptions()
        reconfigureRouteAssets()
    }

    fun stop() {
        subscriptionLoopJob?.cancel()
        routeAssetLoopJob?.cancel()
        subscriptionLoopJob = null
        routeAssetLoopJob = null
        scope.cancel()
    }

    fun reconfigureSubscriptions() {
        subscriptionLoopJob?.cancel()
        subscriptionLoopJob = scope.launch {
            subscriptionLoop()
        }
    }

    fun reconfigureRouteAssets() {
        routeAssetLoopJob?.cancel()
        routeAssetLoopJob = scope.launch {
            routeAssetLoop()
        }
    }

    private suspend fun subscriptionLoop() {
        while (currentCoroutineContext().isActive) {
            val plan = SubscriptionAutoUpdatePlanner.plan() ?: return
            delay(plan.initialDelaySeconds * 1000L)
            if (!currentCoroutineContext().isActive) return
            runCatching {
                SubscriptionAutoUpdateRunner.run(mode = SubscriptionUpdateMode.BackgroundEco)
            }.onFailure {
                Logs.e("desktop subscription auto update", it)
            }
            delay(plan.repeatIntervalMinutes * 60_000L)
        }
    }

    private suspend fun routeAssetLoop() {
        while (currentCoroutineContext().isActive) {
            val plan = RouteAssetAutoUpdatePlanner.plan() ?: return
            delay(plan.initialDelaySeconds * 1000L)
            if (!currentCoroutineContext().isActive) return
            runCatching {
                RouteAssetAutoUpdateRunner.run()
            }.onFailure {
                Logs.e("desktop route asset auto update", it)
            }
            delay(plan.repeatIntervalMinutes * 60_000L)
        }
    }
}
