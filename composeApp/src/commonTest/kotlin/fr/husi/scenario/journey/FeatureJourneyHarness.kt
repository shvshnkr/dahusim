package fr.husi.scenario.journey

import fr.husi.database.CatalogOwnership
import fr.husi.database.SagerDatabase
import fr.husi.database.UserSubscriptionTag
import fr.husi.group.SubscriptionFetchTestHooks
import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.flow.first
import kotlin.test.assertEquals
import kotlin.test.assertTrue

object FeatureJourneyHarness {

    fun installSubscriptionBody(link: String, body: String) {
        val current = SubscriptionFetchTestHooks.bodyByLink.orEmpty()
        SubscriptionFetchTestHooks.install(current + (link to body))
    }

    fun clear() {
        SubscriptionFetchTestHooks.clear()
    }

    suspend fun assertUserOwnedSubscription(groupId: Long) {
        val group = SagerDatabase.groupDao.getById(groupId).first()
            ?: error("group id=$groupId not found")
        assertEquals(CatalogOwnership.USER, group.subscription?.catalogOwnership)
        assertTrue(UserSubscriptionTag.isUserOwnedGroup(group))
    }

    suspend fun assertProxyCount(groupId: Long, min: Int) {
        val count = SagerDatabase.proxyDao.getByGroup(groupId).first().size
        assertTrue(count >= min, "expected at least $min proxies in group $groupId, got $count")
    }
}

abstract class FeatureJourneyTest : HusiKoinTest()
