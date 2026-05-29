package fr.husi.database

import fr.husi.GroupType

/**
 * User-owned subscriptions and manually imported servers for simple-mode autoselect.
 * Built-in and catalog-managed groups are excluded via [ProxyGroup.resolvedOrigin].
 */
object UserSubscriptionTag {

    data class Resolution(
        val userGroupIds: Set<Long>,
        val userProxyIds: Set<Long>,
        val userGroupCount: Int,
    )

    fun resolve(
        allProxies: List<ProxyEntity>,
        groups: List<ProxyGroup>,
    ): Resolution {
        val userGroupIds = LinkedHashSet<Long>()
        for (group in groups) {
            if (isUserOwnedGroup(group)) userGroupIds += group.id
        }
        val userProxyIds = allProxies.filter { it.groupId in userGroupIds }.map { it.id }.toSet()
        return Resolution(
            userGroupIds = userGroupIds,
            userProxyIds = userProxyIds,
            userGroupCount = userGroupIds.size,
        )
    }

    fun isUserOwnedGroup(group: ProxyGroup): Boolean = group.isUserOwnedLibraryItem()

    fun isBuiltinStandaloneGroup(group: ProxyGroup): Boolean =
        group.type == GroupType.BASIC && group.resolvedOrigin() == GroupOrigin.BUILTIN

    fun isBuiltinStandaloneProfile(proxy: ProxyEntity): Boolean =
        proxy.originSourceId == BuiltinRelayDefaults.profileSourceId()
}
