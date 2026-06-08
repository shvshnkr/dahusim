package fr.husi.scenario.journey

import fr.husi.GroupType
import fr.husi.SubscriptionType
import fr.husi.database.DataStore
import fr.husi.database.SagerDatabase
import fr.husi.fmt.FmtTestConstant
import fr.husi.ui.GroupSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionAddBySettingsJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        DataStore.configurationStore.reset()
        DataStore.subscriptionCatalogEnabled = false
        DataStore.firstLaunchSubscriptionUiRefreshDone = true
        FeatureJourneyHarness.clear()
    }

    override suspend fun postStopKoin() {
        FeatureJourneyHarness.clear()
        Dispatchers.resetMain()
        super.postStopKoin()
    }

    @Test
    fun manualSubscriptionSaveCreatesUserOwnedGroupWithProxies() = runBlocking {
        val link = "https://example.com/settings-sub.txt"
        FeatureJourneyHarness.installSubscriptionBody(link, FmtTestConstant.VMESS_DUCKSOFT_URL)

        val viewModel = GroupSettingsViewModel(0L)
        viewModel.setName("Journey settings sub")
        viewModel.setType(GroupType.SUBSCRIPTION)
        viewModel.setSubscriptionType(SubscriptionType.RAW)
        viewModel.setSubscriptionLink(link)
        viewModel.saveAndAwait()

        val groups = SagerDatabase.groupDao.allGroups().first()
            .filter { it.type == GroupType.SUBSCRIPTION }
        assertEquals(1, groups.size)

        val group = groups.single()
        FeatureJourneyHarness.assertUserOwnedSubscription(group.id)
        FeatureJourneyHarness.assertProxyCount(group.id, min = 1)
    }
}
