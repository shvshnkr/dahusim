package fr.husi.database

import fr.husi.GroupType

/**
 * Stable WL subscription detection: catalog [sourceId] and group name before profile display name.
 */
internal object WlSubscriptionTag {

    private const val GITHUB_PREFIX = "gh."
    private const val BUILTIN_PREFIX = "builtin."

    /** Known WL-oriented catalog / bootstrap source keys (without prefix). */
    val KNOWN_WL_SOURCE_IDS: Set<String> = setOf(
        "white-lattice",
        "white-list-vpn-black",
        "aetris-vpn",
        "wlrus-blackl",
        "black-vless-rus-mobile",
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
        if (group.type == GroupType.SUBSCRIPTION) {
            val sub = group.subscription ?: return isWlGroupName(group.displayName())
            val raw = sub.sourceId.trim()
            if (raw.isNotEmpty()) {
                val key = raw.removePrefix(GITHUB_PREFIX).removePrefix(BUILTIN_PREFIX)
                if (key in KNOWN_WL_SOURCE_IDS) return true
            }
        }
        return isWlGroupName(group.displayName())
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
