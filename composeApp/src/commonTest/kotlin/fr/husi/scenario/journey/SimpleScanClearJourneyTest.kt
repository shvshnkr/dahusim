package fr.husi.scenario.journey

import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
import fr.husi.database.PrepareForConnectResult
import fr.husi.database.Probe2kProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The simple screen shows "Scanning N/N" only while prepare is alive. Once the prepare
 * pipeline finishes (selected, failed, or superseded), AutoServerSelector clears the scan
 * state — otherwise the last published "1/1" line sticks forever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimpleScanClearJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        super.postStartKoin()
        Dispatchers.setMain(Dispatchers.Unconfined)
        DataStore.configurationStore.reset()
        DataStore.firstLaunchSubscriptionUiRefreshDone = true
    }

    override suspend fun postStopKoin() {
        Dispatchers.resetMain()
        super.postStopKoin()
    }

    @Test
    fun prepareFinishesWithEmptyLibraryClearsPublishedScan() = runBlocking {
        // A stale scan line was published earlier (e.g. "Scanning 1/1").
        Probe2kProgress.publishScan(1, 1)
        assertEquals(1, DataStore.probe2kScanChecked)
        assertEquals(1, DataStore.probe2kScanTotal)

        val result = AutoServerSelector.prepareForConnect()

        assertTrue(result is PrepareForConnectResult.NoProfiles)
        assertEquals(0, DataStore.probe2kScanChecked)
        assertEquals(0, DataStore.probe2kScanTotal)
    }
}
