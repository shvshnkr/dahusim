package fr.husi.simplemode

import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
import fr.husi.database.PrepareForConnectResult
import fr.husi.test.HusiKoinTest
import fr.husi.ui.SimpleModeAllServersDeadChoice
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeConnectCoordinatorTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        SimpleModeConnectCoordinator.clearPrepareConnectMarkers()
    }

    @Test
    fun shouldSkipManualProfileProbeWhenPrepareVerifiedIdMatches() {
        SimpleModeConnectCoordinator.markPrepareVerifiedForConnect(5597L)
        assertTrue(SimpleModeConnectCoordinator.shouldSkipManualProfileProbe(5597L))
        assertEquals(0L, DataStore.simpleModePrepareVerifiedProfileId)
        assertFalse(SimpleModeConnectCoordinator.shouldSkipManualProfileProbe(5597L))
    }

    @Test
    fun shouldSkipManualProfileProbeWhenVolatilePrepareFlagConsumed() {
        SimpleModeConnectCoordinator.markPrepareVerifiedForConnect(5463L)
        assertTrue(SimpleModeConnectCoordinator.shouldSkipManualProfileProbe(5463L))
        assertEquals(0L, DataStore.simpleModePrepareVerifiedProfileId)
        assertFalse(SimpleModeConnectCoordinator.consumeAutoselectPrepareProbe())
    }

    @Test
    fun shouldNotSkipManualProfileProbeWhenVerifiedIdDiffers() {
        SimpleModeConnectCoordinator.markPrepareVerifiedForConnect(5597L)
        assertFalse(SimpleModeConnectCoordinator.shouldSkipManualProfileProbe(5463L))
        assertEquals(5597L, DataStore.simpleModePrepareVerifiedProfileId)
        SimpleModeConnectCoordinator.clearPrepareConnectMarkers()
    }

    @Test
    fun shouldSkipManualProfileProbeWhenInPrepareQueueWithUrlVerified() {
        DataStore.autoSelectFallbackQueue = "6209,4836,75"
        AutoServerSelector.setLastPrepareUrlVerifiedIdsForTest(setOf(6209L, 4836L))
        assertTrue(SimpleModeConnectCoordinator.shouldSkipManualProfileProbe(4836L))
        assertFalse(SimpleModeConnectCoordinator.shouldSkipManualProfileProbe(75L))
        SimpleModeConnectCoordinator.clearPrepareConnectMarkers()
        DataStore.autoSelectFallbackQueue = ""
        AutoServerSelector.setLastPrepareUrlVerifiedIdsForTest(emptySet())
    }

    @Test
    fun allServersDeadPromptResolvesToWaitForGoogleAfterTimeout() = runTest {
        val choice = SimpleModeConnectCoordinator.resolveAllServersDeadChoice(
            prompt = { awaitCancellation() },
            timeoutMs = 100L,
        )
        assertEquals(SimpleModeAllServersDeadChoice.WaitForGoogle, choice)
    }

    @Test
    fun allServersDeadPromptReturnsUserChoiceWhenAnswered() = runTest {
        val choice = SimpleModeConnectCoordinator.resolveAllServersDeadChoice(
            prompt = { SimpleModeAllServersDeadChoice.ExitApp },
            timeoutMs = 100L,
        )
        assertEquals(SimpleModeAllServersDeadChoice.ExitApp, choice)
    }

    @Test
    fun allServersDeadPromptShortTimeoutDoesNotFireWhenAnsweredFirst() = runTest {
        val choice = SimpleModeConnectCoordinator.resolveAllServersDeadChoice(
            prompt = { SimpleModeAllServersDeadChoice.WaitForGoogle },
            timeoutMs = 0L,
        )
        assertEquals(SimpleModeAllServersDeadChoice.WaitForGoogle, choice)
    }

    @Test
    fun wlServerRevivalWatchAutoConnectsWhenCandidateRecovers() = runTest {
        var calls = 0
        val result = SimpleModeConnectCoordinator.awaitWlServerRevival(
            initial = PrepareForConnectResult.AllProbesDead,
            refreshBudgetMs = 1000L,
            whitelistOnly = true,
            watchMs = 500L,
            pollIntervalMs = 50L,
            prepare = {
                calls++
                if (calls >= 2) PrepareForConnectResult.Success(340L) else PrepareForConnectResult.AllProbesDead
            },
        )
        assertEquals(PrepareForConnectResult.Success(340L), result)
        assertEquals(2, calls)
    }

    @Test
    fun wlServerRevivalWatchExhaustsWindowAndStaysDead() = runTest {
        var calls = 0
        val result = SimpleModeConnectCoordinator.awaitWlServerRevival(
            initial = PrepareForConnectResult.AllProbesDead,
            refreshBudgetMs = 1000L,
            whitelistOnly = true,
            watchMs = 120L,
            pollIntervalMs = 50L,
            prepare = {
                calls++
                PrepareForConnectResult.AllProbesDead
            },
        )
        assertEquals(PrepareForConnectResult.AllProbesDead, result)
        assertTrue(calls >= 1)
    }

    @Test
    fun wlServerRevivalWatchSkipsWhenInitialSucceeded() = runTest {
        var calls = 0
        val result = SimpleModeConnectCoordinator.awaitWlServerRevival(
            initial = PrepareForConnectResult.Success(99L),
            refreshBudgetMs = 1000L,
            whitelistOnly = true,
            watchMs = 200L,
            pollIntervalMs = 50L,
            prepare = {
                calls++
                PrepareForConnectResult.AllProbesDead
            },
        )
        assertEquals(PrepareForConnectResult.Success(99L), result)
        assertEquals(0, calls)
    }
}
