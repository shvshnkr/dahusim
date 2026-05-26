package fr.husi.update

import fr.husi.bg.DesktopBackgroundCoordinator

actual object AppUpdateUpdater {
    actual suspend fun reconfigureUpdater() {
        DesktopBackgroundCoordinator.reconfigureAppUpdates()
    }
}
