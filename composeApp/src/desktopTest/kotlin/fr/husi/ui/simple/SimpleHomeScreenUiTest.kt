package fr.husi.ui.simple

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.database.DataStore
import fr.husi.repository.FakeRepository
import fr.husi.scenario.journey.FeatureJourneyHarness
import fr.husi.scenario.journey.FeatureJourneyTest
import fr.husi.simplemode.SimpleModeConnectCoordinator
import fr.husi.simplemode.SimpleModeNetworkAdaptation
import fr.husi.simplemode.SimpleModeNetworkProbeHooks
import fr.husi.simplemode.SimpleModeNetworkState
import fr.husi.test.SimpleModeUiTestStrings
import fr.husi.ui.MainViewModel
import kotlin.test.Test

/**
 * Render-level tests of [SimpleHomeScreen] on the desktop Compose test harness (offscreen
 * raster scene). They cover what the logic journey tests cannot: the actual status label,
 * ring tone, banner priority chain and power-button semantics that the user sees.
 *
 * Regression anchor (field BS session 2026-08-21): a stale "Verifying last server…" activity
 * left in DataStore while the tunnel was already Connected made the screen render "Preparing…"
 * forever. The fix ([SimpleModeNetworkAdaptation.clearActivityAfterPrepareTimeout]) only clears
 * the activity; the renderer must then fall back to the Connected tone — that transition is
 * asserted here, not the clearing itself.
 */
@OptIn(ExperimentalTestApi::class)
@Suppress("DEPRECATION")
class SimpleHomeScreenUiTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        super.postStartKoin()
        DataStore.configurationStore.reset()
        DataStore.firstLaunchSubscriptionUiRefreshDone = true
        DataStore.activeWhitelistRestrictedNetwork = false
        BackendState.reset()
        FeatureJourneyHarness.clear()
        SimpleModeNetworkProbeHooks.scenarioOverride = null
    }

    override suspend fun postStopKoin() {
        SimpleModeNetworkProbeHooks.scenarioOverride = null
        SimpleModeConnectCoordinator.cancel("ui_test_end")
        super.postStopKoin()
    }

    private fun ComposeUiTest.setSimpleHome(viewModel: MainViewModel) {
        setContent {
            MaterialTheme {
                SimpleHomeScreen(mainViewModel = viewModel, onOpenFullMode = {})
            }
        }
    }

    @Test
    fun connectedWithBlankActivityRendersConnectedStatusAndDisconnectButton() = runComposeUiTest {
        BackendState.updateState(ServiceState.Connected)
        setSimpleHome(MainViewModel(FakeRepository()))

        val connected = SimpleModeUiTestStrings.simpleModeConnected()
        val disconnect = SimpleModeUiTestStrings.simpleModeDisconnect()
        waitUntilAtLeastOneExists(hasText(connected), timeoutMillis = 5_000)
        onNodeWithText(connected).assertIsDisplayed()
        onNodeWithContentDescription(disconnect).assertIsDisplayed()
        onNodeWithContentDescription(disconnect).assertHasClickAction()
    }

    @Test
    fun connectedWithStalePrepareActivitySticksInPreparingUntilActivityCleared() = runComposeUiTest {
        BackendState.updateState(ServiceState.Connected)
        DataStore.simpleModeActivity = "Verifying last server…"
        setSimpleHome(MainViewModel(FakeRepository()))

        val preparing = SimpleModeUiTestStrings.simpleModePreparing()
        val connected = SimpleModeUiTestStrings.simpleModeConnected()
        val verifying = SimpleModeUiTestStrings.simpleModeActivityVerifyingLast()
        waitUntilAtLeastOneExists(hasText(preparing), timeoutMillis = 5_000)
        onNodeWithText(preparing).assertIsDisplayed()
        onNodeWithText(verifying).assertIsDisplayed()

        // The adapt-timeout fix: once the stale activity is cleared, the screen must fall back
        // to Connected — it must NOT stick in Preparing (field BS session 2026-08-21).
        SimpleModeNetworkAdaptation.clearActivityAfterPrepareTimeout("sub_transport_recover")

        waitUntilAtLeastOneExists(hasText(connected), timeoutMillis = 5_000)
        onNodeWithText(connected).assertIsDisplayed()
        onNodeWithText(preparing).assertDoesNotExist()
    }

    @Test
    fun connectingStateRendersConnectingStatus() = runComposeUiTest {
        BackendState.updateState(ServiceState.Connecting)
        setSimpleHome(MainViewModel(FakeRepository()))

        val connecting = SimpleModeUiTestStrings.simpleModeConnecting()
        waitUntilAtLeastOneExists(hasText(connecting), timeoutMillis = 5_000)
        onNodeWithText(connecting).assertIsDisplayed()
    }

    @Test
    fun stoppedStateRendersStoppedStatusAndClickableConnectButton() = runComposeUiTest {
        setSimpleHome(MainViewModel(FakeRepository()))

        val stopped = SimpleModeUiTestStrings.simpleModeStopped()
        val connect = SimpleModeUiTestStrings.simpleModeConnect()
        waitUntilAtLeastOneExists(hasText(stopped), timeoutMillis = 5_000)
        onNodeWithText(stopped).assertIsDisplayed()
        onNodeWithContentDescription(connect).assertIsDisplayed()
        onNodeWithContentDescription(connect).assertHasClickAction()
    }

    @Test
    fun whitelistOnlyNetworkRendersBannerAndClearsWhenNetworkChanges() = runComposeUiTest {
        DataStore.activeWhitelistRestrictedNetwork = true
        setSimpleHome(MainViewModel(FakeRepository()))

        val wlTitle = SimpleModeUiTestStrings.simpleModeWlBannerTitle()
        waitUntilAtLeastOneExists(hasText(wlTitle), timeoutMillis = 5_000)
        onNodeWithText(wlTitle).assertIsDisplayed()

        // The next non-blank activity re-reads the uplink class; on an open uplink the
        // whitelist banner must go away.
        DataStore.activeWhitelistRestrictedNetwork = false
        DataStore.simpleModeActivity = "Checking network…"

        waitUntilDoesNotExist(hasText(wlTitle), timeoutMillis = 5_000)
    }

    @Test
    fun deadUplinkClickRendersNoInternetBannerOverWhitelistAndNextAttemptClearsIt() = runComposeUiTest {
        DataStore.activeWhitelistRestrictedNetwork = true
        SimpleModeNetworkProbeHooks.scenarioOverride =
            SimpleModeNetworkState(hasAnyInternet = false, googleOk = false, whitelistOnly = false)
        setSimpleHome(MainViewModel(FakeRepository()))

        val wlTitle = SimpleModeUiTestStrings.simpleModeWlBannerTitle()
        val noInternetTitle = SimpleModeUiTestStrings.simpleModeNoInternetBannerTitle()
        val connect = SimpleModeUiTestStrings.simpleModeConnect()

        waitUntilAtLeastOneExists(hasText(wlTitle), timeoutMillis = 5_000)

        // Dead uplink: the coordinator gates before any prepare and calls onNoInternet —
        // the persistent banner must render, with priority over the whitelist banner.
        onNodeWithContentDescription(connect).performClick()
        waitUntilAtLeastOneExists(hasText(noInternetTitle), timeoutMillis = 15_000)
        onNodeWithText(noInternetTitle).assertIsDisplayed()
        onNodeWithText(wlTitle).assertDoesNotExist()

        // A new attempt on a healthy uplink clears the persistent banner.
        SimpleModeNetworkProbeHooks.scenarioOverride =
            SimpleModeNetworkState(hasAnyInternet = true, googleOk = true, whitelistOnly = false)
        onNodeWithContentDescription(connect).performClick()

        waitUntilDoesNotExist(hasText(noInternetTitle), timeoutMillis = 15_000)
    }
}
