package fr.husi.scenario.journey

import fr.husi.database.DataStore
import fr.husi.repository.FakeRepository
import fr.husi.repository.Repository
import fr.husi.simplemode.SimpleModeConnectCoordinator
import fr.husi.simplemode.SimpleModeConnectCoordinator.ConnectHost
import fr.husi.ui.SimpleModeAllServersDeadChoice
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * All-servers-dead prompt must never freeze the app: an unresolved/lost prompt resolves to
 * WaitForGoogle and stops the service, so the UI returns to Stopped instead of hanging in
 * "Preparing…" (field log 2026-08-18 02:46, BS — prompt deferred never completed).
 */
class SimpleAllServersDeadPromptTimeoutJourneyTest : FeatureJourneyTest() {

    private class CountingRepository : Repository by FakeRepository() {
        var stopServiceCalls = 0
        override fun stopService() {
            stopServiceCalls++
        }
    }

    private class NeverPromptHost : ConnectHost {
        override fun setPermissionPending(pending: Boolean) {}
        override fun requestVpnConnect() {}
        override fun onVpnPermissionDenied() {}
        override fun onNoInternet() {}
        override fun onNoProfile() {}
        override fun onNeedForegroundForPermission() {}
        override fun onNeedUnlockForPermission() {}
        override suspend fun promptAllServersDead(): SimpleModeAllServersDeadChoice =
            awaitCancellation()
    }

    private lateinit var countingRepository: CountingRepository

    override fun testRepository(): Repository = CountingRepository().also { countingRepository = it }

    override suspend fun postStartKoin() {
        super.postStartKoin()
        DataStore.configurationStore.reset()
    }

    @Test
    fun unresolvedAllServersDeadPromptResolvesToWaitForGoogleAndStopsService() = runTest {
        SimpleModeConnectCoordinator.handleAllServersDead(
            host = NeverPromptHost(),
            promptTimeoutMs = 100L,
        )

        assertEquals(1, countingRepository.stopServiceCalls)
        assertTrue(DataStore.autoConnectPausedUntilGoogle)
    }

    @Test
    fun answeredAllServersDeadPromptStopsServiceImmediately() = runTest {
        val answeredHost = object : ConnectHost by NeverPromptHost() {
            override suspend fun promptAllServersDead(): SimpleModeAllServersDeadChoice =
                SimpleModeAllServersDeadChoice.WaitForGoogle
        }

        SimpleModeConnectCoordinator.handleAllServersDead(
            host = answeredHost,
            promptTimeoutMs = 10_000L,
        )

        assertEquals(1, countingRepository.stopServiceCalls)
        assertTrue(DataStore.autoConnectPausedUntilGoogle)
    }
}
