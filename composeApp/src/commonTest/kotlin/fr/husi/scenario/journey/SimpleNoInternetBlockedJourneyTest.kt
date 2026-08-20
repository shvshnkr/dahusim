package fr.husi.scenario.journey

import fr.husi.database.DataStore
import fr.husi.simplemode.SimpleModeConnectCoordinator
import fr.husi.simplemode.SimpleModeConnectCoordinator.ConnectHost
import fr.husi.simplemode.SimpleModeNetworkProbeHooks
import fr.husi.simplemode.SimpleModeNetworkState
import fr.husi.ui.SimpleModeAllServersDeadChoice
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A dead link must block connect fast and loudly: the coordinator gates before any prepare and
 * calls onNoInternet, so the UI shows the persistent no-internet banner instead of a silent
 * Stopped state (user feedback "app is broken" when the data tariff ran out, field 2026-08-21).
 */
class SimpleNoInternetBlockedJourneyTest : FeatureJourneyTest() {

    private class CountingHost : ConnectHost {
        var noInternetCalls = 0
        override fun setPermissionPending(pending: Boolean) {}
        override fun requestVpnConnect() {}
        override fun onVpnPermissionDenied() {}
        override fun onNoInternet() {
            noInternetCalls++
        }
        override fun onNoProfile() {}
        override fun onNeedForegroundForPermission() {}
        override fun onNeedUnlockForPermission() {}
        override suspend fun promptAllServersDead(): SimpleModeAllServersDeadChoice =
            SimpleModeAllServersDeadChoice.WaitForGoogle
    }

    @Test
    fun deadLinkBlocksConnectBeforeAnyPrepare() = runBlocking {
        SimpleModeNetworkProbeHooks.scenarioOverride =
            SimpleModeNetworkState(hasAnyInternet = false, googleOk = false, whitelistOnly = false)
        try {
            val host = CountingHost()
            SimpleModeConnectCoordinator.start(host)
            withTimeout(5_000) {
                while (host.noInternetCalls == 0) {
                    delay(50)
                }
            }

            assertEquals(1, host.noInternetCalls)
            // No prepare/permission stage ran and the activity is cleared, so the UI returns
            // to Stopped with the no-internet banner explaining why Connect did nothing.
            assertTrue(DataStore.simpleModeActivity.isBlank())
            SimpleModeConnectCoordinator.cancel("test_end")
        } finally {
            SimpleModeNetworkProbeHooks.scenarioOverride = null
        }
    }
}
