package fr.husi.database

import fr.husi.GroupType
import kotlinx.coroutines.flow.first

/** Keeps [ProxyGroup.origin] aligned with subscription metadata and legacy bootstrap markers. */
object GroupOriginSync {

    suspend fun reconcileAll() {
        val groups = SagerDatabase.groupDao.allGroups().first()
        for (group in groups) {
            if (reconcileGroup(group)) {
                GroupManager.updateGroup(group)
            }
        }
    }

    internal fun reconcileGroup(group: ProxyGroup): Boolean {
        var changed = false
        group.subscription?.let { sub ->
            val target = GroupOrigin.fromSubscription(sub)
            if (group.origin != target) {
                group.origin = target
                changed = true
            }
            if (group.originSourceId.isBlank() && sub.sourceId.isNotBlank()) {
                group.originSourceId = sub.sourceId
                changed = true
            }
        }
        if (group.type == GroupType.BASIC &&
            group.origin == GroupOrigin.USER &&
            isLegacyBuiltinRelayGroupName(group.name)
        ) {
            group.origin = GroupOrigin.BUILTIN
            if (group.originSourceId.isBlank()) {
                group.originSourceId = BuiltinRelayDefaults.groupSourceId()
            }
            if (group.name != BuiltinRelayDefaults.GROUP_NAME) {
                group.name = BuiltinRelayDefaults.GROUP_NAME
            }
            changed = true
        }
        return changed
    }

    private fun isLegacyBuiltinRelayGroupName(name: String?): Boolean =
        name == BuiltinRelayDefaults.LEGACY_GROUP_NAME ||
            name == BuiltinRelayDefaults.GROUP_NAME
}
