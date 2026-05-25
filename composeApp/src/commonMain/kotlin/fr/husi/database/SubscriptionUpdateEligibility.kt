package fr.husi.database

/**
 * Due-queue and notification rules for subscription HTTP fetch (group-level jail).
 */
internal object SubscriptionUpdateEligibility {

    fun isDueForScheduledUpdate(
        groupId: Long,
        secondsUntilDue: Long,
        state: SubscriptionUpdateState?,
        nowMs: Long,
        unjailGroupId: Long?,
    ): Boolean {
        if (secondsUntilDue > 0L) return false
        val row = state ?: return true
        if (row.state != SubUpdateState.JAIL) {
            return nowMs >= row.nextAttemptAtMs
        }
        return unjailGroupId == groupId && nowMs >= row.nextAttemptAtMs
    }

    fun pickUnjailGroupId(
        states: Map<Long, SubscriptionUpdateState>,
        nowMs: Long,
    ): Long? = states.values
        .asSequence()
        .filter { it.state == SubUpdateState.JAIL && nowMs >= it.nextAttemptAtMs }
        .minByOrNull { it.nextAttemptAtMs }
        ?.groupId

    fun sortDueQueue(
        groups: List<fr.husi.database.ProxyGroup>,
        states: Map<Long, SubscriptionUpdateState>,
    ): List<fr.husi.database.ProxyGroup> {
        if (groups.size <= 1) return groups
        return groups.sortedWith(
            compareBy<fr.husi.database.ProxyGroup> { group ->
                queueRank(states[group.id])
            }.thenBy { group ->
                states[group.id]?.nextAttemptAtMs ?: 0L
            }.thenBy { it.name },
        )
    }

    fun sortConnectRefreshQueue(
        groups: List<fr.husi.database.ProxyGroup>,
        states: Map<Long, SubscriptionUpdateState>,
    ): List<fr.husi.database.ProxyGroup> {
        if (groups.size <= 1) return groups
        return groups.sortedWith(
            compareBy<fr.husi.database.ProxyGroup> { group ->
                if (group.subscription?.connectPoolRole == ConnectPoolRole.WL) 0 else 1
            }.thenBy { group ->
                queueRank(states[group.id])
            }.thenBy { group ->
                states[group.id]?.nextAttemptAtMs ?: 0L
            }.thenBy { it.name },
        )
    }

    fun filterConnectRefreshCandidates(
        groups: List<fr.husi.database.ProxyGroup>,
        states: Map<Long, SubscriptionUpdateState>,
        nowMs: Long,
    ): List<fr.husi.database.ProxyGroup> {
        val withoutJail = groups.filter { group ->
            states[group.id]?.state != SubUpdateState.JAIL
        }
        var suspectUsed = 0
        return withoutJail.filter { group ->
            if (states[group.id]?.state != SubUpdateState.SUSPECT) return@filter true
            if (suspectUsed < 1) {
                suspectUsed++
                true
            } else {
                false
            }
        }
    }

    suspend fun shouldShowUpdateNotification(group: fr.husi.database.ProxyGroup): Boolean {
        val row = SagerDatabase.subscriptionUpdateStateDao.getByGroupId(group.id)
        return row?.state != SubUpdateState.JAIL
    }

    fun countsAsSuccessForWorker(
        attempted: Boolean,
        updateSucceeded: Boolean,
        groupId: Long,
        statesAfter: Map<Long, SubscriptionUpdateState>,
    ): Boolean {
        if (updateSucceeded) return true
        if (!attempted) return true
        return statesAfter[groupId]?.state == SubUpdateState.JAIL
    }

    private fun queueRank(state: SubscriptionUpdateState?): Int = when (state?.state) {
        SubUpdateState.OK, null -> 0
        SubUpdateState.SUSPECT -> 1
        SubUpdateState.JAIL -> 2
        else -> 1
    }
}
