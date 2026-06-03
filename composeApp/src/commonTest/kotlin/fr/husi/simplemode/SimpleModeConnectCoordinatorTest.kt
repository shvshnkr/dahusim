package fr.husi.simplemode

import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
import fr.husi.test.HusiKoinTest
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
}
