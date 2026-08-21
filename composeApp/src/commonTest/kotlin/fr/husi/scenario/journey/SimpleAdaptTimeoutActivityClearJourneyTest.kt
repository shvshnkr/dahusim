package fr.husi.scenario.journey

import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.database.DataStore
import fr.husi.simplemode.SimpleModeNetworkAdaptation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * After an adapt prepare timeout with a non-rebuild trigger (e.g. sub_transport_recover) the
 * tunnel stays up, so the stale prepare activity must be cleared — otherwise the simple screen
 * sticks in "Preparing" while the session is healthy (field BS session 2026-08-21: last write
 * was "Verifying last server…", then wl_adapt_prepare_timeout, then no activity write at all).
 */
class SimpleAdaptTimeoutActivityClearJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        super.postStartKoin()
        DataStore.configurationStore.reset()
        BackendState.reset()
    }

    @Test
    fun adaptPrepareTimeoutWithoutRebuildClearsStalePreparingActivity() = runTest {
        BackendState.updateState(ServiceState.Connected)
        DataStore.simpleModeActivity = "Verifying last server…"
        assertNotEquals("", DataStore.simpleModeActivity)

        SimpleModeNetworkAdaptation.clearActivityAfterPrepareTimeout("sub_transport_recover")

        assertEquals("", DataStore.simpleModeActivity)
        // The tunnel was not rebuilt: session stays Connected, UI falls back to Connected tone.
        assertEquals(ServiceState.Connected, BackendState.status.value.state)
    }
}
