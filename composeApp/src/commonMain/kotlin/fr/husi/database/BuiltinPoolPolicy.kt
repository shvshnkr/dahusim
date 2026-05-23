package fr.husi.database

/** @see ConnectPoolPolicy */
internal object BuiltinPoolPolicy {

    fun reorderForCompactProbe(
        proxies: List<ProxyEntity>,
        builtinProfileIds: Set<Long>,
        whitelistBuiltinOnly: Boolean,
    ): List<ProxyEntity> = ConnectPoolPolicy.reorderForCompactProbe(
        proxies,
        builtinProfileIds,
        whitelistBuiltinOnly,
    )

    fun openNetSelectionRank(
        profileId: Long,
        builtinProfileIds: Set<Long>,
        whitelistBuiltinOnly: Boolean,
        subscriptionWlIds: Set<Long> = emptySet(),
    ): Int = ConnectPoolPolicy.openNetSelectionRank(
        profileId,
        builtinProfileIds,
        whitelistBuiltinOnly,
        subscriptionWlIds,
    )
}
