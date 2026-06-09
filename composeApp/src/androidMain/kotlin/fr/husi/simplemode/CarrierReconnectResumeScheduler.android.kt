package fr.husi.simplemode

import fr.husi.bg.DefaultNetworkMonitor

internal actual fun scheduleCarrierReconnectResume() {
    DefaultNetworkMonitor.schedulePendingReconnectWatchdog()
}
