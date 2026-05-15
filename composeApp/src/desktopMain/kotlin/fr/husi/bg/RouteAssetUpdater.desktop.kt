package fr.husi.bg

actual object RouteAssetUpdater {
    actual suspend fun reconfigureUpdater() {
        DesktopBackgroundCoordinator.reconfigureRouteAssets()
    }
}
