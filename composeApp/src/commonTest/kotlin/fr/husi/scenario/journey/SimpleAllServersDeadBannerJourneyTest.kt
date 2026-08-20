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
 * After the revival watch exhausts (BS night, all probes dead) the UI must show a persistent
 * "no working servers" banner instead of silently returning to Stopped — the 30s prompt alone
 * can time out or be dismissed, and a silent Stopped state reads as "Connect is broken"
 * (field 2026-08-21: user tapped Connect on BS, all servers were dead, nothing explained why).
 */
class SimpleAllServersDeadBannerJourneyTest : FeatureJourneyTest() {

    private class CountingRepository : Repository by FakeRepository() {
        var stopServiceCalls = 0
        override fun stopService() {
            stopServiceCalls++
        }
    }

    private open class CountingHost : ConnectHost {
        var allServersDeadCalls = 0
        override fun setPermissionPending(pending: Boolean) {}
        override fun requestVpnConnect() {}
        override fun onVpnPermissionDenied() {}
        override fun onNoInternet() {}
        override fun onAllServersDead() {
            allServersDeadCalls++
        }
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
    fun unresolvedAllServersDeadPromptShowsBannerAndStopsService() = runTest {
        val host = CountingHost()
        SimpleModeConnectCoordinator.handleAllServersDead(
            host = host,
            promptTimeoutMs = 100L,
        )

        // Lost/timed-out prompt must still surface the persistent banner (the prompt alone
        // is gone after 30s — the UI would otherwise sit in silent Stopped).
        assertEquals(1, host.allServersDeadCalls)
        assertEquals(1, countingRepository.stopServiceCalls)
        assertTrue(DataStore.autoConnectPausedUntilGoogle)
    }

    @Test
    fun answeredWaitForGoogleShowsBannerAndStopsService() = runTest {
        val host = object : CountingHost() {
            override suspend fun promptAllServersDead(): SimpleModeAllServersDeadChoice =
                SimpleModeAllServersDeadChoice.WaitForGoogle
        }

        SimpleModeConnectCoordinator.handleAllServersDead(
            host = host,
            promptTimeoutMs = 10_000L,
        )

        assertEquals(1, host.allServersDeadCalls)
        assertEquals(1, countingRepository.stopServiceCalls)
        assertTrue(DataStore.autoConnectPausedUntilGoogle)
    }
}
