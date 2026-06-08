package fr.husi.scenario.journey

import fr.husi.GroupType
import fr.husi.SubscriptionType
import fr.husi.database.CatalogOwnership
import fr.husi.database.DataStore
import fr.husi.database.GroupOrigin
import fr.husi.database.ProxyEntity
import fr.husi.database.GroupManager
import fr.husi.database.ProxyGroup
import fr.husi.database.SagerDatabase
import fr.husi.database.SubscriptionBean
import fr.husi.database.UserPoolMode
import fr.husi.database.UserPoolPolicy
import fr.husi.database.UserSubscriptionTag
import fr.husi.fmt.FmtTestConstant
import fr.husi.fmt.trojan.TrojanBean
import fr.husi.subscription.UserSubscriptionAddCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserPoolConnectJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        super.postStartKoin()
        DataStore.configurationStore.reset()
        DataStore.userPoolMode = UserPoolMode.PRIORITY.wire
        UserPoolPolicy.simpleModeUserPoolFallbackUsed = false
        FeatureJourneyHarness.clear()
    }

    @Test
    fun priorityModeBoostsUserProxiesAboveManaged() = runBlocking {
        val userLink = "https://example.com/user-pool.txt"
        FeatureJourneyHarness.installSubscriptionBody(userLink, FmtTestConstant.VLESS_GRPC_URL)
        val userGroup = UserSubscriptionAddCoordinator.add(
            parsed = ProxyGroup(name = "User pool", type = GroupType.SUBSCRIPTION).apply {
                subscription = SubscriptionBean().apply {
                    type = SubscriptionType.RAW
                    link = userLink
                }
            },
            byUser = true,
            updateImmediately = true,
        )

        val managedGroup = ProxyGroup(name = "Managed pool", type = GroupType.SUBSCRIPTION).apply {
            origin = GroupOrigin.GH_MANAGED
            subscription = SubscriptionBean().apply {
                type = SubscriptionType.RAW
                link = "https://example.com/managed.txt"
                catalogOwnership = CatalogOwnership.GH_MANAGED
                managedByRemote = true
                sourceId = "gh:test"
            }
        }
        val managedCreated = GroupManager.createGroup(managedGroup, notifySubscriptionScheduler = false)
        val managedProxy = proxy(id = 0L, groupId = managedCreated.id)
        managedProxy.id = SagerDatabase.proxyDao.addProxy(managedProxy)

        val userProxies = SagerDatabase.proxyDao.getByGroup(userGroup.id).first()
        assertTrue(userProxies.isNotEmpty())
        val userProxyId = userProxies.first().id

        val groups = SagerDatabase.groupDao.allGroups().first()
        val allProxies = userProxies + listOf(managedProxy)
        val resolution = UserSubscriptionTag.resolve(allProxies, groups)
        assertTrue(userProxyId in resolution.userProxyIds)
        assertTrue(managedProxy.id !in resolution.userProxyIds)

        val mode = UserPoolMode.PRIORITY
        val boosted = UserPoolPolicy.priorityBoostIds(mode, resolution.userProxyIds, handoffIds = emptySet())
        assertEquals(resolution.userProxyIds, boosted)

        val userRank = UserPoolPolicy.userSelectionRank(mode, userProxyId, resolution.userProxyIds)
        val managedRank = UserPoolPolicy.userSelectionRank(mode, managedProxy.id, resolution.userProxyIds)
        assertTrue(userRank < managedRank)
    }

    private fun proxy(id: Long, groupId: Long): ProxyEntity =
        ProxyEntity(groupId = groupId, type = ProxyEntity.TYPE_TROJAN).apply {
            this.id = id
            userOrder = id
            trojanBean = TrojanBean()
        }
}
