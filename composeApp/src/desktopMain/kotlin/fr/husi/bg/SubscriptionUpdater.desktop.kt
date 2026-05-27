package fr.husi.bg

import fr.husi.test.isHusiUnitTest

actual object SubscriptionUpdater {
    actual suspend fun reconfigureUpdater() {
        if (isHusiUnitTest) return
        DesktopBackgroundCoordinator.reconfigureSubscriptions()
    }
}
