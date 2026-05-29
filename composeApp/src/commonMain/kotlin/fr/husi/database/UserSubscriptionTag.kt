package fr.husi.database

import fr.husi.GroupType

/**
 * User-owned subscriptions and manually imported servers for simple-mode autoselect.
 * Builtin standalone profiles from bootstrap are excluded (managed/open pool).
 */
object UserSubscriptionTag {

    private const val BUILTIN_STANDALONE_GROUP = "Quick standalone SE"
    private const val BUILTIN_STANDALONE_PROFILE = "SE relay builtin"

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

    fun isUserOwnedGroup(group: ProxyGroup): Boolean {
        if (isBuiltinStandaloneGroup(group)) return false
        if (group.type != GroupType.SUBSCRIPTION) return true
        val sub = group.subscription ?: return true
        return sub.catalogOwnership == CatalogOwnership.USER
    }

    fun isBuiltinStandaloneGroup(group: ProxyGroup): Boolean =
        group.name == BUILTIN_STANDALONE_GROUP

    fun isBuiltinStandaloneProfile(proxy: ProxyEntity): Boolean =
        proxy.displayName() == BUILTIN_STANDALONE_PROFILE
}
