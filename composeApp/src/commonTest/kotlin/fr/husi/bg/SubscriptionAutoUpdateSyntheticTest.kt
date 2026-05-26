package fr.husi.bg

import fr.husi.GroupType
import fr.husi.SubscriptionType
import fr.husi.database.CatalogOwnership
import fr.husi.database.DataStore
import fr.husi.database.GroupManager
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.ktx.applyDefaultValues
import fr.husi.subscription.catalog.SubscriptionCatalogApplier
import fr.husi.subscription.catalog.SubscriptionCatalogParser
import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end synthetic checks: DB → planner → runner tick (no WorkManager / desktop loop).
 */
class SubscriptionAutoUpdateSyntheticTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        SagerDatabase.groupDao.reset()
        SagerDatabase.proxyDao.reset()
        SagerDatabase.subscriptionUpdateStateDao.deleteAll()
        DataStore.subscriptionCatalogLastAppliedGeneration = 0L
        DataStore.serviceState = ServiceState.Idle
    }

    @Test
    fun `loadAutoUpdateSubscriptions only returns autoUpdate enabled groups`() = runBlocking {
        insertSubscription(name = "enabled", autoUpdate = true, lastUpdated = 0)
        insertSubscription(name = "disabled", autoUpdate = false, lastUpdated = 0)

        val loaded = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions()

        assertEquals(listOf("enabled"), loaded.map { it.name })
    }

    @Test
    fun `synthetic pipeline from database lists overdue subscription for worker`() = runBlocking {
        insertSubscription(
            name = "due-synthetic",
            autoUpdate = true,
            delayMinutes = 15,
            lastUpdated = 0,
        )
        val loaded = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions()
        assertEquals(1, loaded.size)
        assertTrue(loaded.single().subscription!!.autoUpdate)

        val nowSeconds = 20 * 60L
        val due = SubscriptionAutoUpdateRunner.dueSubscriptions(
            subscriptions = loaded,
            nowSeconds = nowSeconds,
            connected = false,
        )
        assertEquals(listOf("due-synthetic"), due.map { it.name })
    }

    @Test
    fun `synthetic worker tick skips subscription that is not yet due`() = runBlocking {
        insertSubscription(
            name = "fresh",
            autoUpdate = true,
            delayMinutes = 60,
            lastUpdated = 10_000,
        )
        val touched = mutableListOf<String>()

        SubscriptionAutoUpdateRunner.runWithResult(
            subscriptions = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions(),
            nowSeconds = 10_000L,
            onBeforeUpdate = { touched += it.name.orEmpty() },
        )

        assertTrue(touched.isEmpty())
    }

    @Test
    fun `planner and runner see nothing when every subscription has autoUpdate off`() = runBlocking {
        insertSubscription(name = "manual-only", autoUpdate = false, lastUpdated = 0)

        assertNull(SubscriptionAutoUpdatePlanner.plan())
        val touched = mutableListOf<String>()
        SubscriptionAutoUpdateRunner.run(
            subscriptions = SubscriptionAutoUpdatePlanner.loadAutoUpdateSubscriptions(),
            nowSeconds = 100_000L,
            onBeforeUpdate = { touched += it.name.orEmpty() },
        )
        assertTrue(touched.isEmpty())
    }

    @Test
    fun `catalog upsert on existing group enables managed autoUpdate`() = runBlocking {
        val group = insertSubscription(
            name = "legacy-gh",
            autoUpdate = false,
            delayMinutes = 1440,
            lastUpdated = 0,
        ).apply {
            subscription!!.apply {
                sourceId = "gh.legacy-feed"
                catalogOwnership = CatalogOwnership.GH_MANAGED
                managedByRemote = true
                link = "https://example.com/legacy.txt"
            }
            GroupManager.updateGroup(this)
        }
        val document = SubscriptionCatalogParser.parse(
            """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=1
            UPSERT|legacy-feed|Legacy GH|https://example.com/legacy-v2.txt|RAW|default|open
            """.trimIndent(),
        )
        SubscriptionCatalogApplier.apply(document, "hash-legacy")

        val reloaded = SagerDatabase.groupDao.getById(group.id).first()
        assertNotNull(reloaded)
        assertTrue(reloaded.subscription!!.autoUpdate)
        assertEquals(
            SubscriptionCatalogApplier.MANAGED_AUTO_UPDATE_DELAY_MINUTES,
            reloaded.subscription!!.autoUpdateDelay,
        )
        assertNotNull(SubscriptionAutoUpdatePlanner.plan())
    }

    @Test
    fun `repair enables autoUpdate on legacy gh managed without catalog upsert`() = runBlocking {
        insertSubscription(
            name = "legacy-only",
            autoUpdate = false,
            delayMinutes = 1440,
            lastUpdated = 0,
        ).apply {
            subscription!!.apply {
                sourceId = "gh.legacy-only"
                catalogOwnership = CatalogOwnership.GH_MANAGED
                managedByRemote = true
            }
            GroupManager.updateGroup(this)
        }
        assertNull(SubscriptionAutoUpdatePlanner.plan())

        val repaired = SubscriptionCatalogApplier.repairManagedAutoUpdateFlags()
        assertEquals(1, repaired)
        assertNotNull(SubscriptionAutoUpdatePlanner.plan())
    }

    @Test
    fun `android periodic interval never schedules below fifteen minutes`() {
        assertEquals(15L, androidSubscriptionPeriodicIntervalMinutes(1))
        assertEquals(15L, androidSubscriptionPeriodicIntervalMinutes(14))
        assertEquals(30L, androidSubscriptionPeriodicIntervalMinutes(30))
    }

    @Test
    fun `synthetic plan from database matches overdue delay`() = runBlocking {
        insertSubscription(
            name = "plan-me",
            autoUpdate = true,
            delayMinutes = 20,
            lastUpdated = 0,
        )
        val nowSeconds = 25 * 60L
        val plan = SubscriptionAutoUpdatePlanner.plan()
        assertNotNull(plan)
        assertEquals(20, plan.repeatIntervalMinutes)
        assertEquals(0L, plan.initialDelaySeconds)
    }

    @Test
    fun `stale transport errors classify for worker transport bucket`() {
        assertEquals(
            fr.husi.database.SubscriptionUpdateErrorClass.TRANSPORT,
            classifySubscriptionUpdateError("read tcp: connection reset by peer"),
        )
    }

    private suspend fun insertSubscription(
        name: String,
        autoUpdate: Boolean,
        delayMinutes: Int = 60,
        lastUpdated: Int = 0,
        link: String = "https://example.com/$name.txt",
    ): ProxyGroup {
        val group = ProxyGroup(name = name, type = GroupType.SUBSCRIPTION).apply {
            subscription = SubscriptionBean().apply {
                type = SubscriptionType.RAW
                this.link = link
                this.autoUpdate = autoUpdate
                autoUpdateDelay = delayMinutes
                this.lastUpdated = lastUpdated
            }.applyDefaultValues()
        }
        return GroupManager.createGroup(group, notifySubscriptionScheduler = false)
    }
}
