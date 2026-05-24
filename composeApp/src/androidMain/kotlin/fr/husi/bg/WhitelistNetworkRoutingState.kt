package fr.husi.bg

import fr.husi.database.DataStore
import fr.husi.ktx.runOnDefaultDispatcher
import fr.husi.repository.resolveRepository
import fr.husi.simplemode.SimpleModeVpnCoordinator
import fr.husi.utils.simpleModeLog

/**
 * Tracks whitelist-restricted vs normal Internet and reloads the tunnel when it flips
 * (Wi‑Fi/cellular change, leaving BS coverage, etc.) so RU geo routing is rebuilt.
 */
internal object WhitelistNetworkRoutingState {

    private const val RELOAD_DEBOUNCE_MS = 3_000L

    @Volatile
    private var lastReloadRequestAt = 0L

    fun applyReachability(reachability: NetworkReachability, requestReloadOnChange: Boolean) {
        val nowWl = reachability.whitelistOnly
        val prevWl = DataStore.activeWhitelistRestrictedNetwork
        if (nowWl == prevWl) return
        DataStore.activeWhitelistRestrictedNetwork = nowWl
        if (!nowWl) {
            DataStore.simpleModeAutoselectPoolMerged = false
        }
        fr.husi.routing.VpnExitProbe.clearCache()
        simpleModeLog(
            "SimpleMode",
            "H26 wl_network_routing prev=$prevWl now=$nowWl google=${reachability.googleReachable} " +
                "dzen=${reachability.dzenReachable} ya=${reachability.yaReachable} " +
                "wlSrc=${reachability.whitelistSourceReachable} connected=${DataStore.serviceState.connected}",
        )
        if (requestReloadOnChange) {
            if (DataStore.simpleMode) {
                SimpleModeVpnCoordinator.scheduleAdaptation("reachability_flip")
            } else {
                requestReloadIfConnected("reachability_flip")
            }
        }
    }

    fun reset() {
        DataStore.activeWhitelistRestrictedNetwork = false
        DataStore.simpleModeAutoselectPoolMerged = false
        fr.husi.routing.VpnExitProbe.clearCache()
    }

    fun onUnderlyingInterfaceHandoff(iface: String?) {
        if (!DataStore.serviceState.connected) return
        simpleModeLog(
            "SimpleMode",
            "H27 network_handoff iface=${iface ?: "unknown"}",
        )
        if (DataStore.simpleMode) {
            DataStore.simpleModeActivity = "Network changed, reconnecting…"
            SimpleModeVpnCoordinator.scheduleAdaptation("network_handoff")
            return
        }
        ServiceRegistry.baseService?.data?.resetNetwork()
        runOnDefaultDispatcher {
            if (!DataStore.serviceState.connected) return@runOnDefaultDispatcher
            applyReachability(
                NetworkReachabilityProbe.probe(),
                requestReloadOnChange = true,
            )
        }
    }

    fun requestReloadIfConnected(reason: String) {
        if (!DataStore.serviceState.connected) return
        val now = System.currentTimeMillis()
        if (now - lastReloadRequestAt < RELOAD_DEBOUNCE_MS) return
        lastReloadRequestAt = now
        runOnDefaultDispatcher {
            if (!DataStore.serviceState.connected) return@runOnDefaultDispatcher
            simpleModeLog("SimpleMode", "H26 wl_network_reload reason=$reason")
            resolveRepository().reloadService()
        }
    }
}
