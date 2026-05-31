package fr.husi.simplemode

import fr.husi.compose.VpnServiceLaunchRegistry
import fr.husi.compose.VpnServiceLaunchResult
import fr.husi.utils.simpleModeLog

internal actual fun launchSimpleModeVpnConnect(host: SimpleModeConnectCoordinator.ConnectHost): Boolean {
    val result = VpnServiceLaunchRegistry.launch(onDenied = host::onVpnPermissionDenied)
    simpleModeLog("SimpleMode", "H21 permission_launch result=$result")
    return when (result) {
        VpnServiceLaunchResult.Started,
        VpnServiceLaunchResult.RequestedPermission,
        -> true

        VpnServiceLaunchResult.NoActivity,
        VpnServiceLaunchResult.Failed,
        -> {
            host.onNeedForegroundForPermission()
            false
        }
    }
}
