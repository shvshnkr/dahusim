package fr.husi.simplemode

import fr.husi.compose.VpnServiceLaunchRegistry
import fr.husi.ui.UiActivityTracker
import kotlinx.coroutines.delay

internal actual suspend fun awaitSimpleModeVpnPermissionUi(timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (UiActivityTracker.isResumedForVpnPermission() && VpnServiceLaunchRegistry.canLaunch()) {
            return true
        }
        delay(100)
    }
    return UiActivityTracker.isResumedForVpnPermission() && VpnServiceLaunchRegistry.canLaunch()
}
