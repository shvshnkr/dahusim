package fr.husi.database

/** @see ConnectPoolPolicy */
internal object WlAutoselectPolicy {

    const val MAX_SESSION_FALLBACK_STEPS = ConnectPoolPolicy.MAX_SESSION_FALLBACK_STEPS_WL

    fun maxSessionFallbackSteps(whitelistRestricted: Boolean): Int =
        ConnectPoolPolicy.maxSessionFallbackSteps(whitelistRestricted)

    fun wlNodeRank(
        profileId: Long,
        builtinProfileIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
    ): Int = ConnectPoolPolicy.wlNodeRank(profileId, builtinProfileIds, subscriptionWlIds)
}
