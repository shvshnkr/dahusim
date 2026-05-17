package fr.husi.simplemode

internal actual fun cancelSimpleModeNetworkAdaptation() {
    SimpleModeVpnCoordinator.cancelAdaptation()
}
