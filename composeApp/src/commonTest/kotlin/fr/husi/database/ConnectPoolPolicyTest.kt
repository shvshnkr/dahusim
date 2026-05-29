package fr.husi.database

import fr.husi.GroupType
import fr.husi.fmt.trojan.TrojanBean
import kotlin.test.Test
import fr.husi.database.CatalogOwnership
import fr.husi.database.ConnectPoolRole
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectPoolPolicyTest {

    @Test
    fun wlStratifiedCapIncludesEachSubscriptionGroup() {
        val groups = listOf(
            group(1L, "White lists A", sourceId = "white-lattice"),
            group(2L, "White lists B", sourceId = "wlrus-blackl"),
            group(3L, "White lists C", sourceId = "black-vless-rus-mobile"),
        )
        val proxies = buildList {
            for (g in 1L..3L) {
                for (i in 1L..100L) {
                    add(proxy(id = g * 1000 + i, groupId = g, order = i))
                }
            }
        }
        val result = ConnectPoolPolicy.build(
            mode = ConnectPoolPolicy.PoolBuildMode.WL_SUBSCRIPTION,
            allProxies = proxies,
            groups = groups,
            handoffIds = emptySet(),
            probeStates = emptyMap(),
        )
        assertTrue(result.orderedProxies.size <= ConnectPoolPolicy.WL_PREPARE_CAP)
        val groupIds = result.orderedProxies.map { it.groupId }.toSet()
        assertTrue(1L in groupIds)
        assertTrue(2L in groupIds)
        assertTrue(3L in groupIds)
    }

    @Test
    fun openNetExcludesSubscriptionWlMarked() {
        val wlGroup = group(10L, "White lists VPN", sourceId = "white-lattice")
        val normalGroup = group(11L, "Foreign pool")
        val wlProxy = proxy(1L, 10L, 1L)
        val openProxy = proxy(2L, 11L, 1L)
        val result = ConnectPoolPolicy.build(
            mode = ConnectPoolPolicy.PoolBuildMode.OPEN,
            allProxies = listOf(wlProxy, openProxy),
            groups = listOf(wlGroup, normalGroup),
            handoffIds = emptySet(),
            probeStates = emptyMap(),
        )
        assertEquals(1, result.orderedProxies.size)
        assertEquals(2L, result.orderedProxies.first().id)
        assertEquals(1, result.subsWlMarkedCount)
    }

    @Test
    fun mergedPoolIncludesWlAndOpen() {
        val wlGroup = group(10L, "White lists VPN", sourceId = "white-lattice")
        val normalGroup = group(11L, "Foreign pool")
        val wlProxy = proxy(1L, 10L, 1L)
        val openProxy = proxy(2L, 11L, 1L)
        val result = ConnectPoolPolicy.build(
            mode = ConnectPoolPolicy.PoolBuildMode.MERGED,
            allProxies = listOf(wlProxy, openProxy),
            groups = listOf(wlGroup, normalGroup),
            handoffIds = emptySet(),
            probeStates = emptyMap(),
        )
        assertEquals(2, result.orderedProxies.size)
        assertTrue(result.orderedProxies.any { it.id == 1L })
        assertTrue(result.orderedProxies.any { it.id == 2L })
    }

    @Test
    fun wlListHeadIncludesLowestUserOrderPerGroup() {
        val groups = listOf(
            group(1L, "White lists A", sourceId = "white-lattice"),
            group(2L, "White lists B", sourceId = "wlrus-blackl"),
        )
        val proxies = buildList {
            for (g in 1L..2L) {
                for (i in 1L..20L) {
                    add(proxy(id = g * 1000 + i, groupId = g, order = i))
                }
            }
        }
        val result = ConnectPoolPolicy.build(
            mode = ConnectPoolPolicy.PoolBuildMode.WL_SUBSCRIPTION,
            allProxies = proxies,
            groups = groups,
            handoffIds = emptySet(),
            probeStates = emptyMap(),
        )
        val ids = result.orderedProxies.map { it.id }
        assertTrue(1001L in ids)
        assertTrue(1002L in ids)
        assertTrue(2001L in ids)
        assertTrue(2002L in ids)
        val head = ids.take(8).toSet()
        assertTrue(1001L in head)
        assertTrue(1002L in head)
        assertTrue(2001L in head)
        assertTrue(2002L in head)
    }

    @Test
    fun urlHintedRisesBeforeStratifiedTailOnWl() {
        val groups = listOf(group(1L, "White lists", sourceId = "white-lattice"))
        val pool = listOf(
            proxy(1L, 1L, 1L),
            proxy(2L, 1L, 2L),
            proxy(3L, 1L, 3L),
            proxy(4L, 1L, 4L),
        )
        val states = mapOf(3L to ProxyProbeState(profileId = 3L, lastUrlMs = 200))
        val result = ConnectPoolPolicy.build(
            mode = ConnectPoolPolicy.PoolBuildMode.WL_SUBSCRIPTION,
            allProxies = pool,
            groups = groups,
            handoffIds = setOf(1L),
            probeStates = states,
        )
        val ids = result.orderedProxies.map { it.id }
        assertEquals(1L, ids.first())
        assertTrue(3L in ids)
        assertTrue(ids.indexOf(3L) < ids.lastIndex)
    }

    @Test
    fun compactTcpBatchKeepsPriorityFirst() {
        val pool = (1L..5L).map { proxy(it, 1L, it) }
        val batch = ConnectPoolPolicy.compactTcpBatch(pool, setOf(1L, 2L), maxTotal = 3)
        assertEquals(listOf(1L, 2L, 3L), batch.map { it.id })
    }

    @Test
    fun wlSubscriptionTagMatchesSourceId() {
        val group = group(5L, "Any name", sourceId = "gh.white-lattice")
        val tag = WlSubscriptionTag.resolve(
            allProxies = listOf(proxy(7L, 5L, 1L)),
            groups = listOf(group),
        )
        assertEquals(1, tag.subsWlMarkedCount)
        assertTrue(5L in tag.wlGroupIds)
    }

    @Test
    fun wlSubscriptionTagUsesConnectPoolRoleWithoutKnownSourceId() {
        val group = group(5L, "Foreign name", sourceId = "gh.tri-228", poolRole = ConnectPoolRole.WL)
        val tag = WlSubscriptionTag.resolve(
            allProxies = listOf(proxy(7L, 5L, 1L)),
            groups = listOf(group),
        )
        assertEquals(1, tag.subsWlMarkedCount)
    }

    @Test
    fun userSubscriptionWithWlNameIsNotWlPool() {
        val group = group(6L, "White lists user", sourceId = "", ownership = CatalogOwnership.USER)
        assertTrue(!WlSubscriptionTag.isWlGroup(group))
    }

    @Test
    fun userOnlyBuildIncludesOnlyUserProxies() {
        val userGroup = group(20L, "User sub", ownership = CatalogOwnership.USER)
        val managedGroup = group(21L, "Managed", sourceId = "gh.tri-228-open")
        val userProxy = proxy(201L, 20L, 1L)
        val managedProxy = proxy(202L, 21L, 1L)
        val userIds = setOf(201L)
        val result = ConnectPoolPolicy.build(
            mode = ConnectPoolPolicy.PoolBuildMode.OPEN,
            allProxies = listOf(userProxy, managedProxy),
            groups = listOf(userGroup, managedGroup),
            handoffIds = emptySet(),
            probeStates = emptyMap(),
            membershipFilter = ConnectPoolPolicy.PoolMembershipFilter.USER_ONLY,
            userProxyIds = userIds,
            userPoolMode = UserPoolMode.EXCLUSIVE,
        )
        assertEquals(1, result.orderedProxies.size)
        assertEquals(201L, result.orderedProxies.first().id)
        assertEquals(userIds, result.userProxyIds)
    }

    private fun proxy(id: Long, groupId: Long, order: Long, profileName: String = "node$id") = ProxyEntity().apply {
        this.id = id
        this.groupId = groupId
        userOrder = order
        type = ProxyEntity.TYPE_TROJAN
        trojanBean = TrojanBean().apply { name = profileName }
    }

    private fun group(
        id: Long,
        name: String,
        sourceId: String = "",
        poolRole: Int = ConnectPoolRole.ANY,
        ownership: Int = CatalogOwnership.GH_MANAGED,
    ) = ProxyGroup().apply {
        this.id = id
        this.name = name
        type = GroupType.SUBSCRIPTION
        subscription = SubscriptionBean().apply {
            if (sourceId.isNotEmpty()) this.sourceId = sourceId
            catalogOwnership = ownership
            connectPoolRole = poolRole
        }
    }
}
