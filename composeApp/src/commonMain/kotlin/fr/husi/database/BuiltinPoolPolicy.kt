package fr.husi.database

/**
 * Built-in Trojan helpers stay in the DB for whitelist-only bootstrap, but on a normal
 * (open) network subscription nodes should win probes and ranking unless a builtin is LKG/handoff.
 */
internal object BuiltinPoolPolicy {

    fun reorderForCompactProbe(
        proxies: List<ProxyEntity>,
        builtinProfileIds: Set<Long>,
        whitelistBuiltinOnly: Boolean,
    ): List<ProxyEntity> {
        if (whitelistBuiltinOnly || builtinProfileIds.isEmpty()) return proxies
        val (subscription, builtin) = proxies.partition { it.id !in builtinProfileIds }
        return subscription + builtin
    }

    /** Lower is better in [Comparator.thenBy]. */
    fun openNetSelectionRank(
        profileId: Long,
        builtinProfileIds: Set<Long>,
        whitelistBuiltinOnly: Boolean,
    ): Int = when {
        whitelistBuiltinOnly -> 0
        profileId in builtinProfileIds -> 1
        else -> 0
    }
}
