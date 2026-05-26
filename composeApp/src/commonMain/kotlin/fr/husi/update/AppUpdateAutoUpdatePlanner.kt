package fr.husi.update

import fr.husi.bg.currentEpochSeconds
import fr.husi.bg.secondsUntilDue
import fr.husi.database.DataStore

data class AppUpdateAutoUpdatePlan(
    val repeatIntervalMinutes: Int,
    val initialDelaySeconds: Long,
)

object AppUpdateAutoUpdatePlanner {

    fun plan(nowSeconds: Long = currentEpochSeconds()): AppUpdateAutoUpdatePlan? {
        if (!DataStore.appUpdateCheckEnabled) return null
        val intervalHours = DataStore.appUpdateCheckIntervalHours.coerceAtLeast(1)
        val repeatIntervalMinutes = intervalHours * 60
        val initialDelaySeconds = if (DataStore.appUpdateLastCheckAt <= 0L) {
            0L
        } else {
            secondsUntilDue(
                lastUpdatedSeconds = DataStore.appUpdateLastCheckAt,
                repeatIntervalMinutes = repeatIntervalMinutes,
                nowSeconds = nowSeconds,
            )
        }
        return AppUpdateAutoUpdatePlan(
            repeatIntervalMinutes = repeatIntervalMinutes,
            initialDelaySeconds = initialDelaySeconds,
        )
    }

    fun isCheckDue(nowSeconds: Long = currentEpochSeconds()): Boolean {
        val last = DataStore.appUpdateLastCheckAt
        if (last <= 0L) return true
        val intervalSeconds = DataStore.appUpdateCheckIntervalHours.coerceAtLeast(1) * 3600L
        return nowSeconds - last >= intervalSeconds
    }
}
