package fr.husi.simplemode

import fr.husi.utils.simpleModeLog

internal actual fun releaseSimpleModeVpnSessionPlatform(reason: String) {
    SimpleModeSessionHealth.cancel()
    WarmReserveMaintainer.cancel()
    SimpleModeConnectedMaintenance.cancel()
    SimpleModeVpnCoordinator.cancelAdaptation()
    simpleModeLog("SimpleMode", "H40 simple_vpn_ownership_released reason=$reason")
}
