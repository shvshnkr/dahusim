package fr.husi.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import fr.husi.bg.androidSubscriptionPeriodicIntervalMinutes
import fr.husi.repository.resolveAndroidRepository
import java.util.concurrent.TimeUnit

actual object AppUpdateUpdater {

    private const val WORK_NAME = "AppUpdateUpdater"

    actual suspend fun reconfigureUpdater() {
        val repository = resolveAndroidRepository()
        RemoteWorkManager.getInstance(repository.context).cancelUniqueWork(WORK_NAME)

        val plan = AppUpdateAutoUpdatePlanner.plan() ?: return
        val repeatIntervalMinutes = androidSubscriptionPeriodicIntervalMinutes(plan.repeatIntervalMinutes)

        RemoteWorkManager.getInstance(repository.context).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, repeatIntervalMinutes, TimeUnit.MINUTES)
                .apply {
                    if (plan.initialDelaySeconds > 0) {
                        setInitialDelay(plan.initialDelaySeconds, TimeUnit.SECONDS)
                    }
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
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {

        override suspend fun doWork(): Result {
            AppUpdateCoordinator.checkForUpdate(manual = false)
            return Result.success()
        }
    }
}
