package fr.husi.database

/** @see ConnectPoolPolicy */
internal object WlAutoselectPolicy {

    fun maxSessionFallbackSteps(whitelistRestricted: Boolean): Int =
        ConnectPoolPolicy.maxSessionFallbackSteps(whitelistRestricted)

    fun wlNodeRank(profileId: Long, subscriptionWlIds: Set<Long>): Int =
        ConnectPoolPolicy.wlNodeRank(profileId, subscriptionWlIds)
}
