package fr.husi.simplemode

import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
import fr.husi.database.PrepareForConnectResult

/**
 * Re-runs simple-mode auto-select for the active network class (open vs whitelist-only).
 */
object SimpleModeNetworkAdaptation {

    suspend fun reselectForNetwork(
        whitelistBuiltinOnly: Boolean,
        networkHandoff: Boolean = false,
    ): PrepareForConnectResult {
        DataStore.simpleModeUseWhitelistBuiltinPoolOnly = whitelistBuiltinOnly
        return AutoServerSelector.prepareForConnect(networkHandoff = networkHandoff)
    }
}
