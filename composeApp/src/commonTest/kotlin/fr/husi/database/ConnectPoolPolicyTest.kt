package fr.husi.database

import fr.husi.GroupType
import fr.husi.fmt.trojan.TrojanBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectPoolPolicyTest {

    @Test
    fun wlStratifiedCapIncludesEachSubscriptionGroup() {
        val groups = listOf(
            group(1L, "Sub A"),
            group(2L, "Sub B"),
            group(3L, "Sub C"),
        )
        val proxies = buildList {
            for (g in 1L..3L) {
                for (i in 1L..100L) {
                    add(proxy(id = g * 1000 + i, groupId = g, order = i))
                }
            }
        }
        val builtin = listOf(proxy(9L, 99L, 1L))
        val result = ConnectPoolPolicy.build(
            allProxies = proxies + builtin,
            groups = groups,
            builtinProxies = builtin,
            builtinIds = setOf(9L),
            handoffIds = emptySet(),
            whitelistBuiltinOnly = true,
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
            allProxies = listOf(wlProxy, openProxy),
            groups = listOf(wlGroup, normalGroup),
            builtinProxies = emptyList(),
            builtinIds = emptySet(),
            handoffIds = emptySet(),
            whitelistBuiltinOnly = false,
            probeStates = emptyMap(),
        )
        assertEquals(1, result.orderedProxies.size)
        assertEquals(2L, result.orderedProxies.first().id)
        assertEquals(1, result.subsWlMarkedCount)
    }

    @Test
    fun urlHintedRisesBeforeStratifiedTailOnWl() {
        val groups = listOf(group(1L, "Other"))
        val pool = listOf(
            proxy(1L, 1L, 1L),
            proxy(2L, 1L, 2L),
            proxy(3L, 1L, 3L),
            proxy(4L, 1L, 4L),
        )
        val states = mapOf(3L to ProxyProbeState(profileId = 3L, lastUrlMs = 200))
        val result = ConnectPoolPolicy.build(
            allProxies = pool,
            groups = groups,
            builtinProxies = emptyList(),
            builtinIds = emptySet(),
            handoffIds = setOf(1L),
            whitelistBuiltinOnly = true,
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

    private fun proxy(id: Long, groupId: Long, order: Long, profileName: String = "node$id") = ProxyEntity().apply {
        this.id = id
        this.groupId = groupId
        userOrder = order
        type = ProxyEntity.TYPE_TROJAN
        trojanBean = TrojanBean().apply { name = profileName }
    }

    private fun group(id: Long, name: String, sourceId: String = "") = ProxyGroup().apply {
        this.id = id
        this.name = name
        type = GroupType.SUBSCRIPTION
        if (sourceId.isNotEmpty()) {
            subscription = SubscriptionBean().apply {
                this.sourceId = sourceId
            }
        }
    }
}
