package fr.husi.compose

import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import fr.husi.ui.UiActivityTracker
import fr.husi.ui.VpnRequestActivity

internal object VpnServiceLaunchRegistry {
    @Volatile
    private var registeredLauncher: ManagedActivityResultLauncher<Void?, Boolean>? = null

    fun register(launcher: ManagedActivityResultLauncher<Void?, Boolean>) {
        registeredLauncher = launcher
    }

    fun unregister(launcher: ManagedActivityResultLauncher<Void?, Boolean>) {
        if (registeredLauncher === launcher) {
            registeredLauncher = null
        }
    }

    fun isRegistered(): Boolean = registeredLauncher != null

    fun canLaunch(): Boolean = VpnServiceLaunchStrategy.canLaunch(
        isLauncherRegistered = isRegistered(),
        hasActivity = UiActivityTracker.currentActivity() != null,
    )

    fun launch(onDenied: () -> Unit): VpnServiceLaunchResult {
        if (!UiActivityTracker.isResumedForVpnPermission()) {
            return VpnServiceLaunchResult.NoActivity
        }
        when (VpnServiceLaunchStrategy.choosePrimaryPath(isRegistered())) {
            VpnServiceLaunchStrategy.PrimaryPath.ComposeLauncher -> {
                val launcher = registeredLauncher
                if (launcher != null) {
                    val composeResult = runCatching { launcher.launch(null) }
                    if (composeResult.isSuccess) {
                        return VpnServiceLaunchResult.RequestedPermission
                    }
                    if (composeResult.exceptionOrNull() !is IllegalStateException) {
                        composeResult.exceptionOrNull()?.let { throw it }
                    }
                }
            }
            VpnServiceLaunchStrategy.PrimaryPath.ActivityFallback -> Unit
        }
        return launchViaActivity(onDenied)
    }

    private fun launchViaActivity(onDenied: () -> Unit): VpnServiceLaunchResult {
        val activity = UiActivityTracker.currentActivity() ?: return VpnServiceLaunchResult.NoActivity
        val contract = VpnRequestActivity.StartService()
        val sync = contract.getSynchronousResult(activity, null)
        if (sync != null) {
            if (sync.value) {
                onDenied()
                return VpnServiceLaunchResult.Failed
            }
            return VpnServiceLaunchResult.Started
        }
        activity.startActivity(Intent(activity, VpnRequestActivity::class.java))
        return VpnServiceLaunchResult.RequestedPermission
    }
}
