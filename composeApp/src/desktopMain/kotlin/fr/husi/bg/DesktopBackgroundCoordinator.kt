package fr.husi.bg

import fr.husi.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import fr.husi.database.ProbeScheduler
import fr.husi.subscription.catalog.SubscriptionCatalogCoordinator
import fr.husi.update.AppUpdateAutoUpdatePlanner
import fr.husi.update.AppUpdateCoordinator

/**
 * Periodic subscription and route-asset updates while the desktop JVM is alive.
 * No OS scheduler (schtasks/systemd/launchd).
 */
internal object DesktopBackgroundCoordinator {

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.Default)
    private var subscriptionLoopJob: Job? = null
    private var routeAssetLoopJob: Job? = null
    private var appUpdateLoopJob: Job? = null

    fun start() {
        reconfigureSubscriptions()
        reconfigureRouteAssets()
        reconfigureAppUpdates()
    }

    fun stop() {
        subscriptionLoopJob?.cancel()
        routeAssetLoopJob?.cancel()
        appUpdateLoopJob?.cancel()
        subscriptionLoopJob = null
        routeAssetLoopJob = null
        appUpdateLoopJob = null
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

    fun reconfigureAppUpdates() {
        appUpdateLoopJob?.cancel()
        appUpdateLoopJob = scope.launch {
            appUpdateLoop()
        }
    }

    private suspend fun subscriptionLoop() {
        while (currentCoroutineContext().isActive) {
            val plan = SubscriptionAutoUpdatePlanner.plan() ?: return
            delay(plan.initialDelaySeconds * 1000L)
            if (!currentCoroutineContext().isActive) return
            runCatching {
                SubscriptionCatalogCoordinator.syncIfDue(manual = false)
                ProbeScheduler.runBackgroundMaintenanceIfDue()
                SubscriptionAutoUpdateRunner.run(mode = SubscriptionUpdateMode.BackgroundEco)
            }.onFailure {
                if (it.isCoroutineCancellation()) return@onFailure
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
                if (it.isCoroutineCancellation()) return@onFailure
                Logs.e("desktop route asset auto update", it)
            }
            delay(plan.repeatIntervalMinutes * 60_000L)
        }
    }

    private suspend fun appUpdateLoop() {
        while (currentCoroutineContext().isActive) {
            val plan = AppUpdateAutoUpdatePlanner.plan() ?: return
            delay(plan.initialDelaySeconds * 1000L)
            if (!currentCoroutineContext().isActive) return
            runCatching {
                AppUpdateCoordinator.checkForUpdate(manual = false)
            }.onFailure {
                if (it.isCoroutineCancellation()) return@onFailure
                Logs.e("desktop app update check", it)
            }
            delay(plan.repeatIntervalMinutes * 60_000L)
        }
    }

    private fun Throwable.isCoroutineCancellation(): Boolean {
        var t: Throwable? = this
        while (t != null) {
            if (t is CancellationException) return true
            t = t.cause
        }
        return false
    }
}
