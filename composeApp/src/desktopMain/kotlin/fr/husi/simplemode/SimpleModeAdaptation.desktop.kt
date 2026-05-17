package fr.husi.simplemode

import fr.husi.database.AutoServerSelector

internal actual fun cancelSimpleModeNetworkAdaptation() {
    AutoServerSelector.cancelAdaptPrepare("adapt")
}
