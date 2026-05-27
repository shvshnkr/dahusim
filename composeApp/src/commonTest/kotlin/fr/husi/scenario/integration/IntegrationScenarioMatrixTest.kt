package fr.husi.scenario.integration

import fr.husi.RouteQuickProfile
import fr.husi.database.DataStore
import fr.husi.database.ProfileManager
import fr.husi.database.SagerDatabase
import fr.husi.subscription.catalog.SubscriptionCatalogCoordinator
import fr.husi.subscription.catalog.SubscriptionCatalogSyncResult
import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntegrationScenarioMatrixTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.subscriptionCatalogEnabled = true
        DataStore.subscriptionCatalogUrl = "https://example.com/catalog.txt"
        DataStore.subscriptionCatalogCheckIntervalHours = 12
    }

    @Test
    fun catalogSyncSkippedInsideIntervalDoesNotAdvanceLastCheckAt() = runBlocking {
        val now = System.currentTimeMillis()
        DataStore.subscriptionCatalogLastCheckAt = now
        val result = SubscriptionCatalogCoordinator.syncIfDue(manual = false) { "unused" }
        assertEquals(SubscriptionCatalogSyncResult.Skipped, result)
        assertEquals(now, DataStore.subscriptionCatalogLastCheckAt)
    }

    @Test
    fun catalogManualSyncDueRegardlessOfInterval() {
        val now = 1_000_000L
        assertTrue(
            SubscriptionCatalogCoordinator.isCatalogAutoSyncDue(
                manual = true,
                nowMs = now,
                lastCheckAt = now,
                intervalHours = 12,
            ),
        )
    }

    @Test
    fun routeQuickProfileChangesRuleCount() = runBlocking {
        ProfileManager.applyRouteQuickProfile(RouteQuickProfile.RU_DIRECT_ONLY)
        val directOnlyCount = SagerDatabase.rulesDao.allRules().first().size

        ProfileManager.applyRouteQuickProfile(RouteQuickProfile.RU_DIRECT_WITH_BLOCKED_AND_AI_PROXY)
        val withProxyCount = SagerDatabase.rulesDao.allRules().first().size

        assertTrue(withProxyCount >= directOnlyCount)
    }
}
