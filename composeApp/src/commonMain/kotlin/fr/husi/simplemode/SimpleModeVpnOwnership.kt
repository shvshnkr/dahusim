package fr.husi.simplemode

import fr.husi.database.DataStore

/**
 * Releases simple-mode VPN ownership (watchdog, adaptation, maintenance).
 * Call only on explicit simple disconnect or full UI connect — not when opening full UI.
 */
fun releaseSimpleModeVpnSession(reason: String) {
    DataStore.simpleMode = false
    SimpleModeConnectCoordinator.cancel(reason)
    cancelSimpleModeNetworkAdaptation()
    releaseSimpleModeVpnSessionPlatform(reason)
}

internal expect fun releaseSimpleModeVpnSessionPlatform(reason: String)
