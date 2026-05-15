package fr.husi.bg

actual object SubscriptionUpdater {
    actual suspend fun reconfigureUpdater() {
        DesktopBackgroundCoordinator.reconfigureSubscriptions()
    }
}
