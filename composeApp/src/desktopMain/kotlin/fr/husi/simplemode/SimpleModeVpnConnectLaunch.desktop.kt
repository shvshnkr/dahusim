package fr.husi.simplemode

internal actual fun launchSimpleModeVpnConnect(host: SimpleModeConnectCoordinator.ConnectHost): Boolean {
    host.requestVpnConnect()
    return true
}
