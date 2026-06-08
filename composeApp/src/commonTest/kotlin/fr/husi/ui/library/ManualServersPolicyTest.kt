package fr.husi.ui.library

import fr.husi.GroupType
import fr.husi.database.BuiltinRelayDefaults
import fr.husi.database.GroupOrigin
import fr.husi.database.ProxyEntity
import fr.husi.database.ProxyGroup
import fr.husi.fmt.trojan.TrojanBean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualServersPolicyTest {

    @Test
    fun buildRowsAggregatesUserBasicProfiles() {
        val ungrouped = group(1L, "Ungrouped", ungrouped = true)
        val folder = group(2L, "Work")
        val managed = group(3L, "Built-in", origin = GroupOrigin.BUILTIN)
        val profiles = mapOf(
            1L to listOf(proxy(10L, 1L)),
            2L to listOf(proxy(20L, 2L), proxy(21L, 2L)),
            3L to listOf(proxy(99L, 3L)),
        )
        val rows = ManualServersPolicy.buildRows(
            groups = listOf(ungrouped, folder, managed),
            profilesByGroup = profiles,
            ungroupedLabel = "No folder",
        )
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.profile.id in setOf(10L, 20L, 21L) })
    }

    @Test
    fun filterRowsByChipGroup() {
        val rowA = ManualServersPolicy.ProfileRow(proxy(1L, 1L), 1L, "A")
        val rowB = ManualServersPolicy.ProfileRow(proxy(2L, 2L), 2L, "B")
        val filtered = ManualServersPolicy.filterRows(listOf(rowA, rowB), chipGroupId = 2L)
        assertEquals(listOf(rowB), filtered)
    }

    @Test
    fun buildChipsSkipsEmptyGroups() {
        val groups = listOf(group(1L, "Empty"), group(2L, "Has"))
        val chips = ManualServersPolicy.buildChips(
            groups = groups,
            profilesByGroup = mapOf(2L to listOf(proxy(5L, 2L))),
            ungroupedLabel = "No folder",
        )
        assertEquals(1, chips.size)
        assertEquals(2L, chips.first().groupId)
    }

    private fun group(
        id: Long,
        name: String,
        ungrouped: Boolean = false,
        origin: Int = GroupOrigin.USER,
    ): ProxyGroup = ProxyGroup(name = name, type = GroupType.BASIC).apply {
        this.id = id
        this.ungrouped = ungrouped
        this.origin = origin
    }

    private fun proxy(id: Long, groupId: Long): ProxyEntity =
        ProxyEntity(groupId = groupId, type = ProxyEntity.TYPE_TROJAN).apply {
            this.id = id
            trojanBean = TrojanBean()
        }
}
