package fr.husi.simplemode

internal actual fun cancelSimpleModeNetworkAdaptation() {
    AutoServerSelector.cancelAdaptPrepare("adapt")
}
