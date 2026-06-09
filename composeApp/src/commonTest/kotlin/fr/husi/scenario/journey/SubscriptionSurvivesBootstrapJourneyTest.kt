package fr.husi.scenario.journey

import fr.husi.GroupType
import fr.husi.SubscriptionType
import fr.husi.bootstrap.DefaultUserBootstrap
import fr.husi.database.CatalogOwnership
import fr.husi.database.DataStore
import fr.husi.database.GroupOrigin
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.isSystemLibraryItem
import fr.husi.database.SubscriptionBean
import fr.husi.fmt.FmtTestConstant
import fr.husi.subscription.UserSubscriptionAddCoordinator
import fr.husi.subscription.catalog.SubscriptionCatalogDefaults
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SubscriptionSurvivesBootstrapJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        super.postStartKoin()
        DataStore.configurationStore.reset()
        DataStore.subscriptionCatalogEnabled = false
        DataStore.firstLaunchSubscriptionUiRefreshDone = true
        FeatureJourneyHarness.clear()
        installBootstrapFetchBodies()
    }

    override suspend fun postStopKoin() {
        FeatureJourneyHarness.clear()
        super.postStopKoin()
    }

    @Test
    fun userSubscriptionKeepsOwnershipAfterBootstrap() = runBlocking {
        val link = "https://example.com/user-survives-bootstrap.txt"
        FeatureJourneyHarness.installSubscriptionBody(link, FmtTestConstant.VLESS_GRPC_URL)

        val parsed = ProxyGroup(name = "User journey sub", type = GroupType.SUBSCRIPTION).apply {
            subscription = SubscriptionBean().apply {
                type = SubscriptionType.RAW
                this.link = link
            }
        }
        UserSubscriptionAddCoordinator.add(parsed, byUser = true, updateImmediately = true)

        val before = SagerDatabase.groupDao.allGroups().first()
            .single { it.subscription?.link == link }
        assertEquals(CatalogOwnership.USER, before.subscription?.catalogOwnership)

        DefaultUserBootstrap.bootstrapAll()

        val after = SagerDatabase.groupDao.getById(before.id).first()
            ?: error("user subscription missing after bootstrap")
        assertEquals(link, after.subscription?.link)
        assertEquals(CatalogOwnership.USER, after.subscription?.catalogOwnership)
        assertNotEquals(GroupOrigin.GH_MANAGED, after.origin)
        FeatureJourneyHarness.assertUserOwnedSubscription(after.id)
    }

    @Test
    fun bootstrapSeedStaysGhManaged() = runBlocking {
        installBootstrapFetchBodies()
        DefaultUserBootstrap.bootstrapAll()

        val wlSeed = SubscriptionCatalogDefaults.STARTER_SEEDS
            .single { it.sourceKey == "wl-standalone" }
        val group = SagerDatabase.groupDao.allGroups().first()
            .single { it.subscription?.link == wlSeed.link }

        assertEquals(CatalogOwnership.GH_MANAGED, group.subscription?.catalogOwnership)
        assertEquals(GroupOrigin.GH_MANAGED, group.origin)
        assertTrue(group.isSystemLibraryItem())
    }

    private fun installBootstrapFetchBodies() {
        SubscriptionCatalogDefaults.STARTER_SEEDS.forEach { seed ->
            FeatureJourneyHarness.installSubscriptionBody(seed.link, FmtTestConstant.TROJAN_URL)
        }
    }
}
