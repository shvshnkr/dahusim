package fr.husi.subscription.catalog

import fr.husi.database.DataStore
import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SubscriptionCatalogCoordinatorInvariantTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        DataStore.subscriptionCatalogEnabled = true
        DataStore.subscriptionCatalogUrl = "https://example.com/catalog.txt"
        DataStore.subscriptionCatalogCheckIntervalHours = 12
    }

    @Test
    fun `isCatalogAutoSyncDue false inside interval for auto`() {
        val now = 1_000_000L
        assertFalse(
            SubscriptionCatalogCoordinator.isCatalogAutoSyncDue(
                manual = false,
                nowMs = now,
                lastCheckAt = now - 3_600_000L,
                intervalHours = 12,
            ),
        )
    }

    @Test
    fun `isCatalogAutoSyncDue true for manual regardless of interval`() {
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
    fun `auto sync skipped inside interval does not touch lastCheckAt`() = runBlocking {
        val now = System.currentTimeMillis()
        DataStore.subscriptionCatalogLastCheckAt = now

        val result = SubscriptionCatalogCoordinator.syncIfDue(manual = false) { "unused" }

        assertEquals(SubscriptionCatalogSyncResult.Skipped, result)
        assertEquals(now, DataStore.subscriptionCatalogLastCheckAt)
    }

    @Test
    fun `failed fetch does not advance subscriptionCatalogLastCheckAt`() = runBlocking {
        DataStore.subscriptionCatalogLastCheckAt = 0L

        val result = SubscriptionCatalogCoordinator.syncIfDue(manual = false) {
            error("network down")
        }

        assertIs<SubscriptionCatalogSyncResult.Error>(result)
        assertEquals(0L, DataStore.subscriptionCatalogLastCheckAt)
    }

    @Test
    fun `manual sync bypasses interval gate`() = runBlocking {
        DataStore.subscriptionCatalogLastCheckAt = System.currentTimeMillis()

        assertEquals(
            SubscriptionCatalogSyncResult.Skipped,
            SubscriptionCatalogCoordinator.syncIfDue(manual = false) { "unused" },
        )
        assertIs<SubscriptionCatalogSyncResult.Error>(
            SubscriptionCatalogCoordinator.syncIfDue(manual = true) {
                error("network down")
            },
        )
    }
}
