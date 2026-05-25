package fr.husi.database

import fr.husi.GroupType

/**
 * WL subscription detection for gh-managed catalog feeds via [SubscriptionBean.connectPoolRole].
 */
internal object WlSubscriptionTag {

    private const val GITHUB_PREFIX = "gh."

    /** Legacy fallback for gh-managed [ConnectPoolRole.ANY] until catalog pool_role is applied. */
    val KNOWN_WL_SOURCE_IDS: Set<String> = setOf(
        "white-lattice",
        "white-list-vpn-black",
        "aetris-vpn",
        "wlrus-blackl",
        "black-vless-rus-mobile",
        "vless-wl-rus-mobile",
        "vless-wl-rus-mobile-2",
        "tri-228-wl",
    )

    data class Resolution(
        val wlGroupIds: Set<Long>,
        val subscriptionWlProxyIds: Set<Long>,
        val subsWlMarkedCount: Int,
    )

    fun resolve(
        allProxies: List<ProxyEntity>,
        groups: List<ProxyGroup>,
    ): Resolution {
        val wlGroupIds = LinkedHashSet<Long>()
        for (group in groups) {
            if (isWlGroup(group)) wlGroupIds += group.id
        }
        val fromGroups = allProxies.filter { it.groupId in wlGroupIds }.map { it.id }.toSet()
        val fromNames = allProxies.filter { it.isProfileNameWlMarked() }.map { it.id }.toSet()
        val ids = fromGroups + fromNames
        return Resolution(
            wlGroupIds = wlGroupIds,
            subscriptionWlProxyIds = ids,
            subsWlMarkedCount = ids.size,
        )
    }

    fun isWlGroup(group: ProxyGroup): Boolean {
        if (group.type != GroupType.SUBSCRIPTION) return false
        val sub = group.subscription ?: return false
        if (sub.catalogOwnership != CatalogOwnership.GH_MANAGED) return false
        return when (sub.connectPoolRole) {
            ConnectPoolRole.WL -> true
            ConnectPoolRole.OPEN -> false
            else -> {
                val key = sub.sourceId.trim().removePrefix(GITHUB_PREFIX)
                key in KNOWN_WL_SOURCE_IDS || isWlGroupName(group.displayName())
            }
        }
    }

    private fun isWlGroupName(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("white lists") || n.contains("white list") || n.contains("whitelist")
    }

    private fun ProxyEntity.isProfileNameWlMarked(): Boolean {
        val n = displayName().lowercase()
        return n.contains("white lists") || n.contains("white list") || n.contains("whitelist")
    }
}
