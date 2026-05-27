package fr.husi.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import fr.husi.simplemode.SimpleModeSessionHealth
import fr.husi.utils.simpleModeDebugEvent
import fr.husi.utils.simpleModeLog
import java.lang.ref.WeakReference

/**
 * Tracks the foreground [Activity] for graceful app exit ([Activity.finishAffinity]).
 */
object UiActivityTracker {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    @Volatile
    private var resumed = false

    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
        simpleModeLog("SimpleMode", "H23 ui_activity_attach name=${activity.javaClass.simpleName}")
        SimpleModeSessionHealth.triggerQuickCheck("ui_attach")
    }

    fun detach(activity: Activity) {
        simpleModeLog("SimpleMode", "H23 ui_activity_detach name=${activity.javaClass.simpleName}")
        if (activityRef?.get() === activity) {
            activityRef = null
            resumed = false
        }
    }

    fun onResume(activity: Activity) {
        activityRef = WeakReference(activity)
        resumed = true
        SimpleModeSessionHealth.triggerQuickCheck("ui_resume")
        // #region agent log
        simpleModeDebugEvent(
            runId = "handoff-reconnect",
            hypothesisId = "H4_UI_FOREGROUND_GATE",
            location = "UiActivityTracker.onResume",
            message = "UI resumed",
            data = mapOf("activity" to activity.javaClass.simpleName),
        )
        // #endregion
    }

    fun onPause(activity: Activity) {
        if (activityRef?.get() === activity) {
            resumed = false
            // #region agent log
            simpleModeDebugEvent(
                runId = "handoff-reconnect",
                hypothesisId = "H4_UI_FOREGROUND_GATE",
                location = "UiActivityTracker.onPause",
                message = "UI paused",
                data = mapOf("activity" to activity.javaClass.simpleName),
            )
            // #endregion
        }
    }

    fun isResumedForVpnPermission(): Boolean {
        val activity = activityRef?.get() ?: return false
        return resumed && !activity.isFinishing
    }

    /**
     * @return true if [Activity.finishAffinity] was posted on the main thread for a live activity.
     */
    fun finishAffinityOnMainThread(): Boolean {
        val activity = activityRef?.get() ?: return false
        if (activity.isFinishing) return false
        simpleModeLog("SimpleMode", "H23 ui_finish_affinity_posted name=${activity.javaClass.simpleName}")
        mainHandler.post {
            runCatching { activity.finishAffinity() }
                .onFailure { runCatching { activity.finish() } }
        }
        return true
    }
}
