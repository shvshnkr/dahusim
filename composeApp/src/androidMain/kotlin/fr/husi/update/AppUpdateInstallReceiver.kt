package fr.husi.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import fr.husi.ktx.Logs

class AppUpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_SESSION_ID = "app_update_session_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Logs.d("app update install success")
                if (sessionId >= 0) AppUpdateInstallAwaiter.complete(sessionId, AppUpdateInstallResult.Success)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { context.startActivity(it) }
                if (sessionId >= 0) {
                    AppUpdateInstallAwaiter.complete(sessionId, AppUpdateInstallResult.PendingUserAction)
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                if (sessionId >= 0) AppUpdateInstallAwaiter.complete(sessionId, AppUpdateInstallResult.Cancelled)
            }
            else -> {
                Logs.w("app update install failed: $status $message")
                if (sessionId >= 0) {
                    AppUpdateInstallAwaiter.complete(
                        sessionId,
                        AppUpdateInstallResult.Failed(message ?: "Install failed ($status)"),
                    )
                }
            }
        }
    }
}
