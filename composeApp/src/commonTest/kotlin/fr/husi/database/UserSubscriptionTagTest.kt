package fr.husi.database

import fr.husi.GroupType
import fr.husi.SubscriptionType
import fr.husi.fmt.trojan.TrojanBean
import fr.husi.ui.ImportTargetResolver.applyUserImportOwnership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserSubscriptionTagTest {

    @Test
    fun userSubscriptionIsUserOwned() {
        val group = subscriptionGroup(1L, "My sub", CatalogOwnership.USER)
        assertTrue(UserSubscriptionTag.isUserOwnedGroup(group))
    }

    @Test
    fun managedSubscriptionIsNotUserOwned() {
        val group = subscriptionGroup(2L, "Catalog", CatalogOwnership.GH_MANAGED).apply {
            origin = GroupOrigin.GH_MANAGED
        }
        assertFalse(UserSubscriptionTag.isUserOwnedGroup(group))
    }

    @Test
    fun manualBasicGroupIsUserOwned() {
        val group = ProxyGroup(name = "My servers", type = GroupType.BASIC).apply { id = 3L }
        assertTrue(UserSubscriptionTag.isUserOwnedGroup(group))
    }

    @Test
    fun builtinStandaloneIsNotUserOwned() {
        val group = ProxyGroup(name = BuiltinRelayDefaults.GROUP_NAME, type = GroupType.BASIC).apply {
            id = 4L
            origin = GroupOrigin.BUILTIN
            originSourceId = BuiltinRelayDefaults.groupSourceId()
        }
        assertFalse(UserSubscriptionTag.isUserOwnedGroup(group))
        assertTrue(UserSubscriptionTag.isBuiltinStandaloneGroup(group))
    }

    @Test
    fun resolveCollectsUserProxiesOnly() {
        val userGroup = subscriptionGroup(10L, "User", CatalogOwnership.USER)
        val managedGroup = subscriptionGroup(11L, "Managed", CatalogOwnership.GH_MANAGED).apply {
            origin = GroupOrigin.GH_MANAGED
        }
        val basicGroup = ProxyGroup(name = "Manual", type = GroupType.BASIC).apply { id = 12L }
        val proxies = listOf(
            proxy(1L, 10L),
            proxy(2L, 11L),
            proxy(3L, 12L),
        )
        val resolution = UserSubscriptionTag.resolve(
            proxies,
            listOf(userGroup, managedGroup, basicGroup),
        )
        assertEquals(setOf(10L, 12L), resolution.userGroupIds)
        assertEquals(setOf(1L, 3L), resolution.userProxyIds)
    }

    @Test
    fun importedSubscriptionOwnershipEntersUserPool() {
        val group = ProxyGroup(name = "Imported", type = GroupType.SUBSCRIPTION).apply {
            id = 20L
            origin = GroupOrigin.GH_MANAGED
            subscription = SubscriptionBean().apply {
                type = SubscriptionType.RAW
                link = "https://example.com/sub"
                catalogOwnership = CatalogOwnership.GH_MANAGED
                managedByRemote = true
                sourceId = "gh:seed"
            }
        }
        group.applyUserImportOwnership()
        assertTrue(UserSubscriptionTag.isUserOwnedGroup(group))
        val resolution = UserSubscriptionTag.resolve(
            listOf(proxy(50L, 20L)),
            listOf(group),
        )
        assertEquals(setOf(50L), resolution.userProxyIds)
    }

    @Test
    fun builtinProfileDetectedByOriginSourceId() {
        val profile = proxy(99L, 4L).apply {
            originSourceId = BuiltinRelayDefaults.profileSourceId()
        }
        assertTrue(UserSubscriptionTag.isBuiltinStandaloneProfile(profile))
    }

    private fun subscriptionGroup(id: Long, name: String, ownership: Int): ProxyGroup =
        ProxyGroup(name = name, type = GroupType.SUBSCRIPTION).apply {
            this.id = id
            subscription = SubscriptionBean().apply {
                type = SubscriptionType.RAW
                catalogOwnership = ownership
            }
        }

    private fun proxy(id: Long, groupId: Long): ProxyEntity =
        ProxyEntity().apply {
            this.id = id
            this.groupId = groupId
            userOrder = id
            type = ProxyEntity.TYPE_TROJAN
            trojanBean = TrojanBean()
        }
}
