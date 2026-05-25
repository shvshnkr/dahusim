package fr.husi.subscription.catalog

import fr.husi.GroupType
import fr.husi.database.CatalogOwnership
import fr.husi.database.ConnectPoolRole
import fr.husi.database.DataStore
import fr.husi.database.GroupManager
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.ktx.applyDefaultValues
import fr.husi.test.HusiKoinTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionCatalogApplierTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
        SagerDatabase.groupDao.reset()
        SagerDatabase.proxyDao.reset()
        DataStore.subscriptionCatalogLastAppliedGeneration = 0L
    }

    @Test
    fun `upsert with same link as user creates new gh group`() = runBlocking {
        val sharedLink = "https://example.com/shared.txt"
        val userGroup = ProxyGroup(name = "User sub", type = GroupType.SUBSCRIPTION).apply {
            subscription = SubscriptionBean().apply {
                link = sharedLink
                catalogOwnership = CatalogOwnership.USER
                managedByRemote = false
            }.applyDefaultValues()
        }.applyDefaultValues()
        userGroup.id = SagerDatabase.groupDao.createGroup(userGroup)
        val document = SubscriptionCatalogParser.parse(
            """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=1
            UPSERT|wl-feed|WL|${sharedLink}|RAW|default|wl
            """.trimIndent(),
        )
        val result = SubscriptionCatalogApplier.apply(document, "hash1")
        assertTrue(result is SubscriptionCatalogSyncResult.Success)
        val subs = SagerDatabase.groupDao.subscriptions()
        assertEquals(2, subs.size)
        assertEquals(1, subs.count { it.subscription?.catalogOwnership == CatalogOwnership.USER })
        assertEquals(1, subs.count { it.subscription?.catalogOwnership == CatalogOwnership.GH_MANAGED })
    }

    @Test
    fun `omission stages gh managed removal not protected reserved`() = runBlocking {
        GroupManager.createGroup(
            ProxyGroup(name = SubscriptionCatalogDefaults.RESERVED_BUILTIN_GROUP_NAME, type = GroupType.SUBSCRIPTION)
                .apply {
                    subscription = SubscriptionBean().apply {
                        sourceId = SubscriptionCatalogDefaults.reservedBuiltinSourceId()
                        catalogOwnership = CatalogOwnership.PROTECTED_RESERVED
                        managedByRemote = true
                    }.applyDefaultValues()
                },
            notifySubscriptionScheduler = false,
        )
        GroupManager.createGroup(
            ProxyGroup(name = "Gh wl", type = GroupType.SUBSCRIPTION).apply {
                subscription = SubscriptionBean().apply {
                    sourceId = "gh.wl-feed"
                    link = "https://example.com/wl.txt"
                    managedByRemote = true
                    catalogOwnership = CatalogOwnership.GH_MANAGED
                    connectPoolRole = ConnectPoolRole.WL
                }.applyDefaultValues()
            },
            notifySubscriptionScheduler = false,
        )
        GroupManager.createGroup(
            ProxyGroup(name = "Gh keep", type = GroupType.SUBSCRIPTION).apply {
                subscription = SubscriptionBean().apply {
                    sourceId = "gh.keep-open"
                    link = "https://example.com/keep.txt"
                    managedByRemote = true
                    catalogOwnership = CatalogOwnership.GH_MANAGED
                    connectPoolRole = ConnectPoolRole.OPEN
                }.applyDefaultValues()
            },
            notifySubscriptionScheduler = false,
        )
        val document = SubscriptionCatalogParser.parse(
            """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=2
            allow_empty=false
            UPSERT|keep-open|Keep|https://example.com/keep.txt|RAW|default|open
            """.trimIndent(),
        )
        val result = SubscriptionCatalogApplier.apply(document, "hash2")
        assertTrue(result is SubscriptionCatalogSyncResult.Success)
        val subs = SagerDatabase.groupDao.subscriptions()
        assertEquals(3, subs.size)
        assertTrue(subs.any { it.subscription?.catalogOwnership == CatalogOwnership.PROTECTED_RESERVED })
        val staged = subs.single { it.subscription?.sourceId == "gh.wl-feed" }
        assertTrue(staged.subscription!!.pendingRemoveAt > 0L)
        val kept = subs.single { it.subscription?.sourceId == "gh.keep-open" }
        assertEquals(0L, kept.subscription!!.pendingRemoveAt)
    }

    @Test
    fun `explicit remove sets connect pool role on upsert`() = runBlocking {
        val document = SubscriptionCatalogParser.parse(
            """
            HUSI_SUBSCRIPTION_CATALOG_V1
            generation=1
            UPSERT|tri-228|tri|https://example.com/tri.txt|RAW|default|open
            """.trimIndent(),
        )
        SubscriptionCatalogApplier.apply(document, "hash3")
        val group = SagerDatabase.groupDao.subscriptions().single()
        assertEquals(ConnectPoolRole.OPEN, group.subscription!!.connectPoolRole)
        assertEquals(CatalogOwnership.GH_MANAGED, group.subscription!!.catalogOwnership)
    }
}
