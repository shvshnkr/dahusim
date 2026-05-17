package fr.husi.simplemode

import fr.husi.ui.UiActivityTracker
import kotlinx.coroutines.delay

internal actual suspend fun awaitSimpleModeVpnPermissionUi(timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (UiActivityTracker.isResumedForVpnPermission()) return true
        delay(100)
    }
    return UiActivityTracker.isResumedForVpnPermission()
}
