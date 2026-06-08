package fr.husi.scenario.journey

import fr.husi.GroupType
import fr.husi.SubscriptionType
import fr.husi.database.DataStore
import fr.husi.database.SagerDatabase
import fr.husi.fmt.FmtTestConstant
import fr.husi.test.HusiKoinMainDispatcherTest
import fr.husi.ui.GroupSettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionAddBySettingsJourneyTest : HusiKoinMainDispatcherTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.firstLaunchSubscriptionUiRefreshDone = true
        FeatureJourneyHarness.clear()
    }

    override suspend fun postStopKoinWithMainDispatcher() {
        FeatureJourneyHarness.clear()
    }

    @Test
    fun manualSubscriptionSaveCreatesUserOwnedGroupWithProxies() = runTest(dispatcher.scheduler) {
        val link = "https://example.com/settings-sub.txt"
        FeatureJourneyHarness.installSubscriptionBody(link, FmtTestConstant.VMESS_DUCKSOFT_URL)

        val viewModel = GroupSettingsViewModel(0L)
        advanceUntilIdle()

        viewModel.setName("Journey settings sub")
        viewModel.setType(GroupType.SUBSCRIPTION)
        viewModel.setSubscriptionType(SubscriptionType.RAW)
        viewModel.setSubscriptionLink(link)
        advanceUntilIdle()

        viewModel.save()
        advanceUntilIdle()

        val groups = SagerDatabase.groupDao.allGroups().first()
            .filter { it.type == GroupType.SUBSCRIPTION }
        assertEquals(1, groups.size)

        val group = groups.single()
        FeatureJourneyHarness.assertUserOwnedSubscription(group.id)
        FeatureJourneyHarness.assertProxyCount(group.id, min = 1)
    }
}
