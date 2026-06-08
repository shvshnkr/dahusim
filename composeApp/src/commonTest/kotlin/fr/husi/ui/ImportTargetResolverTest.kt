package fr.husi.ui

import fr.husi.GroupType
import fr.husi.database.CatalogOwnership
import fr.husi.database.GroupOrigin
import fr.husi.database.ProxyGroup
import fr.husi.database.SubscriptionBean
import fr.husi.ui.ImportTargetResolver.applyUserImportOwnership
import fr.husi.ui.ImportTargetResolver.isUserImportTarget
import fr.husi.subscription.catalog.SubscriptionCatalogDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportTargetResolverTest {

    @Test
    fun userBasicGroupIsImportTarget() {
        val group = ProxyGroup(name = "Mine", type = GroupType.BASIC)
        assertTrue(group.isUserImportTarget())
    }

    @Test
    fun builtinRelayIsNotImportTarget() {
        val group = ProxyGroup(name = "Built-in", type = GroupType.BASIC).apply {
            origin = GroupOrigin.BUILTIN
        }
        assertFalse(group.isUserImportTarget())
    }

    @Test
    fun applyUserImportOwnershipForSubscription() {
        val group = ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
            origin = GroupOrigin.GH_MANAGED
            subscription = SubscriptionBean().apply {
                link = "https://example.com/sub"
                catalogOwnership = CatalogOwnership.GH_MANAGED
                managedByRemote = true
                sourceId = "gh:test"
            }
        }
        group.applyUserImportOwnership()
        assertEquals(GroupOrigin.USER, group.origin)
        assertEquals(CatalogOwnership.USER, group.subscription?.catalogOwnership)
        assertFalse(group.subscription?.managedByRemote == true)
        assertEquals("", group.subscription?.sourceId)
    }
}

class EnsureBuiltinManagedMarkersTest {

    @Test
    fun userOwnedSeedLinkShouldNotBePromoted() {
        val group = ProxyGroup(type = GroupType.SUBSCRIPTION).apply {
            subscription = SubscriptionBean().apply {
                link = SubscriptionCatalogDefaults.STARTER_SEEDS.first().link
                catalogOwnership = CatalogOwnership.USER
                managedByRemote = false
            }
        }
        assertEquals(CatalogOwnership.USER, group.subscription?.catalogOwnership)
        assertFalse(group.subscription?.managedByRemote == true)
    }
}
