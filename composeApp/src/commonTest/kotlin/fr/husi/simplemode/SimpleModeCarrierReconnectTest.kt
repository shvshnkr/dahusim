package fr.husi.simplemode

import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.bg.UnderlyingCarrierState
import fr.husi.database.DataStore
import fr.husi.repository.FakeRepository
import fr.husi.repository.Repository
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleModeCarrierReconnectTest : HusiKoinTest() {

    private class CountingRepository : Repository by FakeRepository() {
        var startServiceCalls = 0
        override fun startService() {
            startServiceCalls++
        }
    }

    private lateinit var countingRepository: CountingRepository

    override fun testRepository(): Repository = CountingRepository().also { countingRepository = it }

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        BackendState.reset()
        UnderlyingCarrierState.clear()
    }

    @Test
    fun pendingTtlMatchesHeartbeatStale() {
        assertTrue(SimpleModeVpnSessionMarker.HEARTBEAT_STALE_MS > 0L)
    }

    @Test
    fun deferGracefulStopDuringCarrierOutage() {
        UnderlyingCarrierState.clear()
        try {
            assertFalse(SimpleModeCarrierReconnect.shouldDeferGracefulStop())
            UnderlyingCarrierState.markAwaitingRestoreForTest()
            assertTrue(SimpleModeCarrierReconnect.shouldDeferGracefulStop())
        } finally {
            UnderlyingCarrierState.clear()
        }
    }

    @Test
    fun canResumeNowRequiresStoppedPendingProfileAndRecover() {
        assertTrue(
            SimpleModeCarrierReconnect.canResumeNow(
                pendingValid = true,
                recoverAllowed = true,
                serviceState = ServiceState.Stopped,
                profileId = 42L,
            ),
        )
        assertFalse(
            SimpleModeCarrierReconnect.canResumeNow(
                pendingValid = false,
                recoverAllowed = true,
                serviceState = ServiceState.Stopped,
                profileId = 42L,
            ),
        )
        assertFalse(
            SimpleModeCarrierReconnect.canResumeNow(
                pendingValid = true,
                recoverAllowed = false,
                serviceState = ServiceState.Stopped,
                profileId = 42L,
            ),
        )
        assertFalse(
            SimpleModeCarrierReconnect.canResumeNow(
                pendingValid = true,
                recoverAllowed = true,
                serviceState = ServiceState.Connected,
                profileId = 42L,
            ),
        )
        assertFalse(
            SimpleModeCarrierReconnect.canResumeNow(
                pendingValid = true,
                recoverAllowed = true,
                serviceState = ServiceState.Stopped,
                profileId = 0L,
            ),
        )
    }

    @Test
    fun tryResumeIfDueStartsServiceInSimpleMode() {
        DataStore.simpleMode = true
        DataStore.selectedProxy = 5597L
        DataStore.simpleModePendingCarrierReconnectAt = System.currentTimeMillis()
        BackendState.updateState(ServiceState.Stopped)

        SimpleModeCarrierReconnect.tryResumeIfDue("ui_resume")

        assertEquals(1, countingRepository.startServiceCalls)
        assertFalse(SimpleModeCarrierReconnect.isPendingValid())
    }

    @Test
    fun tryResumeIfDueHonorsExpertRecoverInFullMode() {
        DataStore.simpleMode = false
        DataStore.expertConnectRecoverEnabled = true
        DataStore.selectedProxy = 5597L
        DataStore.simpleModePendingCarrierReconnectAt = System.currentTimeMillis()
        BackendState.updateState(ServiceState.Stopped)

        SimpleModeCarrierReconnect.tryResumeIfDue("ui_resume")

        assertEquals(1, countingRepository.startServiceCalls)
    }

    @Test
    fun tryResumeIfDueSkippedWhenExpertRecoverOffInFullMode() {
        DataStore.simpleMode = false
        DataStore.expertConnectRecoverEnabled = false
        DataStore.selectedProxy = 5597L
        DataStore.simpleModePendingCarrierReconnectAt = System.currentTimeMillis()
        BackendState.updateState(ServiceState.Stopped)

        SimpleModeCarrierReconnect.tryResumeIfDue("ui_resume")

        assertEquals(0, countingRepository.startServiceCalls)
        assertTrue(SimpleModeCarrierReconnect.isPendingValid())
    }
}
