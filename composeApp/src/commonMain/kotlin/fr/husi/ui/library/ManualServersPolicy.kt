package fr.husi.ui.library

import fr.husi.GroupType
import fr.husi.database.ProxyEntity
import fr.husi.database.ProxyGroup
import fr.husi.database.displayType
import fr.husi.database.isUserOwnedLibraryItem
import fr.husi.fmt.AbstractBean

/** Flat manual-server list: user-owned BASIC groups only (Library → Manual). */
object ManualServersPolicy {

    const val ALL_GROUPS: Long = -1L

    data class GroupChip(
        val groupId: Long,
        val label: String,
        val profileCount: Int,
    )

    data class ProfileRow(
        val profile: ProxyEntity,
        val groupId: Long,
        val groupLabel: String,
    )

    fun manualGroups(groups: List<ProxyGroup>): List<ProxyGroup> =
        groups.filter { it.type == GroupType.BASIC && it.isUserOwnedLibraryItem() }
            .sortedBy { it.userOrder }

    fun groupLabel(group: ProxyGroup, ungroupedLabel: String): String =
        if (group.ungrouped) ungroupedLabel else group.name.orEmpty()

    fun buildChips(
        groups: List<ProxyGroup>,
        profilesByGroup: Map<Long, List<ProxyEntity>>,
        ungroupedLabel: String,
    ): List<GroupChip> = manualGroups(groups).mapNotNull { group ->
        val count = profilesByGroup[group.id]?.size ?: 0
        if (count <= 0) return@mapNotNull null
        GroupChip(
            groupId = group.id,
            label = groupLabel(group, ungroupedLabel),
            profileCount = count,
        )
    }

    fun buildRows(
        groups: List<ProxyGroup>,
        profilesByGroup: Map<Long, List<ProxyEntity>>,
        ungroupedLabel: String,
    ): List<ProfileRow> {
        val rows = ArrayList<ProfileRow>()
        for (group in manualGroups(groups)) {
            val profiles = profilesByGroup[group.id].orEmpty()
                .sortedWith(compareBy<ProxyEntity> { it.userOrder }.thenBy { displayName(it) })
            val label = groupLabel(group, ungroupedLabel)
            for (profile in profiles) {
                rows += ProfileRow(profile = profile, groupId = group.id, groupLabel = label)
            }
        }
        return rows
    }

    fun filterRows(rows: List<ProfileRow>, chipGroupId: Long?): List<ProfileRow> {
        if (chipGroupId == null || chipGroupId == ALL_GROUPS) return rows
        return rows.filter { it.groupId == chipGroupId }
    }

    private fun displayName(profile: ProxyEntity): String {
        val bean: AbstractBean = profile.requireBean()
        return bean.displayName().ifBlank { bean.displayAddress() }
    }
}
