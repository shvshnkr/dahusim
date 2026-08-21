package fr.husi.simplemode

import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
import fr.husi.database.PrepareForConnectResult
import fr.husi.database.PrepareOwner
import fr.husi.utils.simpleModeLog

/**
 * Re-runs simple-mode auto-select for the active network class (open vs whitelist-only).
 */
object SimpleModeNetworkAdaptation {

    suspend fun reselectForNetwork(
        whitelistBuiltinOnly: Boolean,
        networkHandoff: Boolean = false,
    ): PrepareForConnectResult {
        DataStore.simpleModeUseWhitelistBuiltinPoolOnly = whitelistBuiltinOnly
        return AutoServerSelector.prepareForConnect(
            networkHandoff = networkHandoff,
            owner = PrepareOwner.ADAPT,
        )
    }

    /**
     * An adapt prepare timed out without a tunnel rebuild: the tunnel stays up, so the stale
     * prepare activity (e.g. "Verifying last server…") must be dropped — otherwise the simple
     * screen sticks in Preparing while the session is healthy (field BS session 2026-08-21,
     * sub_transport_recover).
     */
    fun clearActivityAfterPrepareTimeout(reason: String) {
        simpleModeLog("SimpleMode", "H30 wl_adapt_timeout_activity_clear reason=$reason")
        DataStore.simpleModeActivity = ""
    }
}
