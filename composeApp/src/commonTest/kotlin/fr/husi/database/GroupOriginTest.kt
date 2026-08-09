package fr.husi.database

import fr.husi.GroupType
import fr.husi.SubscriptionType
import fr.husi.subscription.catalog.SubscriptionCatalogDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupOriginTest {

    @Test
    fun userBasicGroupIsDeletableAndManual() {
        val group = ProxyGroup(name = "Mine", type = GroupType.BASIC)
        assertEquals(GroupOrigin.USER, group.resolvedOrigin())
        assertTrue(group.isGroupDeletable())
        assertTrue(group.isUserOwnedLibraryItem())
        assertFalse(group.isSystemLibraryItem())
    }

    @Test
    fun builtinRelayGroupIsSystemAndProtected() {
        val group = ProxyGroup(name = BuiltinRelayDefaults.GROUP_NAME, type = GroupType.BASIC).apply {
            origin = GroupOrigin.BUILTIN
            originSourceId = BuiltinRelayDefaults.groupSourceId()
        }
        assertEquals(GroupOrigin.BUILTIN, group.resolvedOrigin())
        assertFalse(group.isGroupDeletable())
        assertFalse(group.isUserOwnedLibraryItem())
        assertTrue(group.isSystemLibraryItem())
        assertTrue(UserSubscriptionTag.isBuiltinStandaloneGroup(group))
    }

    @Test
    fun managedBootstrapSubscriptionResolvesAsGhManagedBeforeCatalogPromotion() {
        val group = subscriptionGroup(
            sourceId = SubscriptionCatalogDefaults.builtinSourceId("swordware-main"),
            ownership = CatalogOwnership.USER,
            managedByRemote = true,
        )
        assertEquals(GroupOrigin.GH_MANAGED, group.resolvedOrigin())
        assertFalse(group.isUserOwnedLibraryItem())
    }

    @Test
    fun protectedBuiltinSlotIsNotDeletable() {
        val group = subscriptionGroup(
            sourceId = SubscriptionCatalogDefaults.reservedBuiltinSourceId(),
            ownership = CatalogOwnership.PROTECTED_RESERVED,
            managedByRemote = true,
        ).apply {
            origin = GroupOrigin.PROTECTED_BUILTIN
        }
        assertFalse(group.isGroupDeletable())
        assertTrue(group.isSystemLibraryItem())
    }

    @Test
    fun legacyStandaloneGroupNameReconcilesToBuiltin() {
        val group = ProxyGroup(name = BuiltinRelayDefaults.LEGACY_GROUP_NAME, type = GroupType.BASIC)
        assertTrue(GroupOriginSync.reconcileGroup(group))
        assertEquals(GroupOrigin.BUILTIN, group.origin)
        assertEquals(BuiltinRelayDefaults.groupSourceId(), group.originSourceId)
        assertEquals(BuiltinRelayDefaults.GROUP_NAME, group.name)
    }

    private fun subscriptionGroup(
        sourceId: String,
        ownership: Int,
        managedByRemote: Boolean,
    ): ProxyGroup = ProxyGroup(name = "Sub", type = GroupType.SUBSCRIPTION).apply {
        subscription = SubscriptionBean().apply {
            type = SubscriptionType.RAW
            this.sourceId = sourceId
            catalogOwnership = ownership
            this.managedByRemote = managedByRemote
        }
    }
}
