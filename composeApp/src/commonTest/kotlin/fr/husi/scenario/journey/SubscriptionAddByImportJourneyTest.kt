package fr.husi.scenario.journey

import fr.husi.GroupType
import fr.husi.database.DataStore
import fr.husi.database.SagerDatabase
import fr.husi.fmt.FmtTestConstant
import fr.husi.ui.ImportLinkInteractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SubscriptionAddByImportJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        super.postStartKoin()
        DataStore.configurationStore.reset()
        DataStore.firstLaunchSubscriptionUiRefreshDone = true
        FeatureJourneyHarness.clear()
    }

    override suspend fun postStopKoin() {
        FeatureJourneyHarness.clear()
        super.postStopKoin()
    }

    @Test
    fun importUrlCreatesUserOwnedSubscriptionWithProxies() = runBlocking {
        val link = "https://example.com/journey-sub.txt"
        FeatureJourneyHarness.installSubscriptionBody(link, FmtTestConstant.VLESS_GRPC_URL)

        val interactor = ImportLinkInteractor()
        val parsed = assertNotNull(interactor.parseSubscription(link))
        interactor.importSubscription(parsed)

        val groups = SagerDatabase.groupDao.allGroups().first()
            .filter { it.type == GroupType.SUBSCRIPTION }
        assertEquals(1, groups.size)

        val group = groups.single()
        FeatureJourneyHarness.assertUserOwnedSubscription(group.id)
        FeatureJourneyHarness.assertProxyCount(group.id, min = 1)
    }
}
