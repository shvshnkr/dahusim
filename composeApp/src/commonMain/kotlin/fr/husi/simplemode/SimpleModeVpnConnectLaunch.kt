package fr.husi.simplemode

/**
 * Starts VPN / permission flow after simple-mode preconnect.
 * @return false when UI is not ready to launch (caller should abort connect gracefully).
 */
internal expect fun launchSimpleModeVpnConnect(host: SimpleModeConnectCoordinator.ConnectHost): Boolean
