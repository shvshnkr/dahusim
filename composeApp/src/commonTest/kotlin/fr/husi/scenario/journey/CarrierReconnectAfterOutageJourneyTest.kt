package fr.husi.scenario.journey

import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.bg.UnderlyingCarrierState
import fr.husi.database.DataStore
import fr.husi.repository.FakeRepository
import fr.husi.repository.Repository
import fr.husi.simplemode.SimpleModeCarrierReconnect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CarrierReconnectAfterOutageJourneyTest : FeatureJourneyTest() {

    private class CountingRepository : Repository by FakeRepository() {
        var startServiceCalls = 0
        override fun startService() {
            startServiceCalls++
        }
    }

    private lateinit var countingRepository: CountingRepository

    override fun testRepository(): Repository = CountingRepository().also { countingRepository = it }

    override suspend fun postStartKoin() {
        super.postStartKoin()
        DataStore.configurationStore.reset()
        BackendState.reset()
        UnderlyingCarrierState.clear()
    }

    @Test
    fun pendingReconnectResumesWithoutManualConnectTap() {
        DataStore.simpleMode = true
        DataStore.selectedProxy = 4836L
        UnderlyingCarrierState.markAwaitingRestoreForTest()
        SimpleModeCarrierReconnect.markPending("manual_profile_probe_carrier_outage")
        BackendState.updateState(ServiceState.Stopped)

        SimpleModeCarrierReconnect.tryResumeIfDue("watchdog")

        assertEquals(1, countingRepository.startServiceCalls)
        assertFalse(SimpleModeCarrierReconnect.isPendingValid())
    }
}
