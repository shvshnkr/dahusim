package fr.husi.bg

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import fr.husi.Action
import fr.husi.database.DataStore
import fr.husi.lib.R
import fr.husi.repository.resolveAndroidRepository
import fr.husi.repository.resolveRepository
import fr.husi.resources.*
import fr.husi.database.ProbeScheduler
import fr.husi.subscription.catalog.SubscriptionCatalogCoordinator
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import kotlin.random.Random

actual object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"
    private const val CHANNEL_ID = "service-subscription-silent"

    actual suspend fun reconfigureUpdater() {
        val repo = resolveAndroidRepository()
        RemoteWorkManager.getInstance(repo.context).cancelUniqueWork(WORK_NAME)

        val plan = SubscriptionAutoUpdatePlanner.plan() ?: return
        val repeatIntervalMinutes = plan.repeatIntervalMinutes.coerceAtLeast(15).toLong()

        // main process
        RemoteWorkManager.getInstance(repo.context).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, repeatIntervalMinutes, TimeUnit.MINUTES)
                .apply {
                    if (plan.initialDelaySeconds > 0) {
                        setInitialDelay(plan.initialDelaySeconds + Random.nextLong(0, 180), TimeUnit.SECONDS)
                    }
                    setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                }
                .build(),
        )
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = runBlocking {
            val repo = resolveAndroidRepository()
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setWhen(0)
                .setTicker(repo.getString(Res.string.forward_success))
                .setContentTitle(repo.getString(Res.string.subscription_update))
                .setSmallIcon(R.drawable.ic_service_active)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
        }

        override suspend fun doWork(): Result {
            if (!DataStore.serviceState.connected) {
                val reachability = NetworkReachabilityProbe.probe()
                if (!reachability.hasInternet) {
                    simpleModeLog(
                        "SimpleMode",
                        "H21 subscription_update_skipped_no_internet google=${reachability.googleReachable} " +
                            "dzen=${reachability.dzenReachable} ya=${reachability.yaReachable} " +
                            "wl=${reachability.whitelistSourceReachable}",
                    )
                    return Result.success()
                }
            }
            SubscriptionCatalogCoordinator.syncIfDue(manual = false)
            runCatching { ProbeScheduler.runBackgroundMaintenanceIfDue() }

            val outcome = SubscriptionAutoUpdateRunner.runWithResult(
                mode = SubscriptionUpdateMode.BackgroundEco,
            ) { profile ->
                notification.setContentText(
                    resolveRepository().getString(
                        Res.string.subscription_update_message,
                        profile.displayName(),
                    ),
                )
                // #region agent log
                simpleModeLog("SimpleMode", "H12 subscription_notification profile=${profile.displayName()} channel=$CHANNEL_ID")
                // #endregion
                nm.notify(2, notification.build())
            }

            nm.cancel(2)
            // #region agent log
            simpleModeLog("SimpleMode", "H12 subscription_notification_cancelled")
            // #endregion

            if (outcome.shouldRequestUpstreamReset) {
                // #region agent log
                simpleModeLog(
                    "SimpleMode",
                    "H19 stale_transport_batch_reset_upstream " +
                        "transportFails=${outcome.transportFailuresWhileVpnConnected} " +
                        "allSucceeded=${outcome.allSucceeded}",
                )
                simpleModeDebugEvent(
                    runId = "sub-update",
                    hypothesisId = "H-STALE",
                    location = "SubscriptionUpdater.UpdateTask.doWork",
                    message = "Broadcast RESET_UPSTREAM after transport-like subscription failures",
                    data = mapOf(
                        "transportFails" to outcome.transportFailuresWhileVpnConnected.toString(),
                        "allSucceeded" to outcome.allSucceeded.toString(),
                    ),
                )
                // #endregion
                applicationContext.sendBroadcast(
                    Intent(Action.RESET_UPSTREAM_CONNECTIONS).setPackage(applicationContext.packageName),
                )
            }

            return if (outcome.allSucceeded) Result.success() else Result.retry()
        }
    }

}
