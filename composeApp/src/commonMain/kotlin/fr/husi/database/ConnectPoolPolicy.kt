package fr.husi.database

/**
 * Simple-mode connect pool: WL subscription cap, open-net pool, merged WL+open session pool.
 */
internal object ConnectPoolPolicy {

    const val WL_PREPARE_CAP = 128
    const val OPEN_PREPARE_CAP = 256
    const val USER_PREPARE_CAP = 128
    const val MERGED_PREPARE_CAP = 4096
    const val WL_LIST_HEAD_PER_GROUP = 2
    const val USER_LIST_HEAD_PER_GROUP = 2
    const val MAX_SESSION_FALLBACK_STEPS_WL = 4
    const val MAX_SESSION_FALLBACK_STEPS_OPEN = 32

    enum class PoolBuildMode {
        /** All profiles except WL-marked subscriptions. */
        OPEN,
        /** WL-marked subscription nodes only. */
        WL_SUBSCRIPTION,
        /** WL-marked + open nodes: all selectable on BS from start, WL-first. */
        MERGED,
    }

    enum class PoolMembershipFilter {
        NONE,
        USER_ONLY,
    }

    data class BuildResult(
        val priorityFirstIds: Set<Long>,
        val orderedProxies: List<ProxyEntity>,
        val subscriptionWlIds: Set<Long>,
        val userProxyIds: Set<Long>,
        val subsWlMarkedCount: Int,
        val wlGroupCount: Int,
        val poolMode: PoolBuildMode,
    )

    fun maxSessionFallbackSteps(whitelistRestricted: Boolean): Int =
        if (whitelistRestricted && !DataStore.simpleModeAutoselectPoolMerged) {
            MAX_SESSION_FALLBACK_STEPS_WL
        } else if (whitelistRestricted) {
            MAX_SESSION_FALLBACK_STEPS_OPEN
        } else {
            MAX_SESSION_FALLBACK_STEPS_OPEN
        }

    fun build(
        mode: PoolBuildMode,
        allProxies: List<ProxyEntity>,
        groups: List<ProxyGroup>,
        handoffIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        membershipFilter: PoolMembershipFilter = PoolMembershipFilter.NONE,
        userProxyIds: Set<Long> = emptySet(),
        userPoolMode: UserPoolMode = UserPoolMode.OFF,
    ): BuildResult {
        val poolProxies = when (membershipFilter) {
            PoolMembershipFilter.USER_ONLY -> allProxies.filter { it.id in userProxyIds }
            PoolMembershipFilter.NONE -> allProxies
        }
        val tag = WlSubscriptionTag.resolve(poolProxies, groups)
        val userBoost = UserPoolPolicy.priorityBoostIds(userPoolMode, userProxyIds, handoffIds)
        return when (mode) {
            PoolBuildMode.WL_SUBSCRIPTION -> {
                if (membershipFilter == PoolMembershipFilter.USER_ONLY) {
                    buildUserOnly(
                        userProxies = poolProxies,
                        handoffIds = handoffIds,
                        userProxyIds = userProxyIds,
                        userBoostIds = userBoost,
                        subscriptionWlIds = tag.subscriptionWlProxyIds,
                        subsWlMarkedCount = tag.subsWlMarkedCount,
                        wlGroupCount = tag.wlGroupIds.size,
                        probeStates = probeStates,
                    )
                } else {
                    buildWhitelist(
                        allProxies = poolProxies,
                        handoffIds = handoffIds,
                        userBoostIds = userBoost,
                        subscriptionWlIds = tag.subscriptionWlProxyIds,
                        subsWlMarkedCount = tag.subsWlMarkedCount,
                        wlGroupCount = tag.wlGroupIds.size,
                        userProxyIds = userProxyIds,
                        probeStates = probeStates,
                    )
                }
            }
            PoolBuildMode.MERGED -> buildMerged(
                allProxies = poolProxies,
                handoffIds = handoffIds,
                userBoostIds = userBoost,
                subscriptionWlIds = tag.subscriptionWlProxyIds,
                subsWlMarkedCount = tag.subsWlMarkedCount,
                wlGroupCount = tag.wlGroupIds.size,
                userProxyIds = userProxyIds,
                probeStates = probeStates,
            )
            PoolBuildMode.OPEN -> {
                if (membershipFilter == PoolMembershipFilter.USER_ONLY) {
                    buildUserOnly(
                        userProxies = poolProxies,
                        handoffIds = handoffIds,
                        userProxyIds = userProxyIds,
                        userBoostIds = userBoost,
                        subscriptionWlIds = tag.subscriptionWlProxyIds,
                        subsWlMarkedCount = tag.subsWlMarkedCount,
                        wlGroupCount = tag.wlGroupIds.size,
                        probeStates = probeStates,
                    )
                } else {
                    buildOpen(
                        allProxies = poolProxies,
                        handoffIds = handoffIds,
                        userBoostIds = userBoost,
                        subscriptionWlIds = tag.subscriptionWlProxyIds,
                        subsWlMarkedCount = tag.subsWlMarkedCount,
                        wlGroupCount = tag.wlGroupIds.size,
                        userProxyIds = userProxyIds,
                        probeStates = probeStates,
                    )
                }
            }
        }
    }

    private fun buildWhitelist(
        allProxies: List<ProxyEntity>,
        handoffIds: Set<Long>,
        userBoostIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
        subsWlMarkedCount: Int,
        wlGroupCount: Int,
        userProxyIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState>,
    ): BuildResult {
        val priorityFirstIds = (subscriptionWlIds + userBoostIds).toSet()
        val subWlPool = allProxies.filter { it.id in subscriptionWlIds }
        val listHead = wlListHeadPerGroup(subWlPool)
        val subWlProxies = stratifiedSample(
            proxies = subWlPool,
            totalCap = minOf(subWlPool.size, WL_PREPARE_CAP / 2),
        )
        val handoffProxies = allProxies.filter { it.id in handoffIds && it.id !in subscriptionWlIds }
        val priorityHead = (handoffProxies + listHead + subWlProxies).distinctBy { it.id }
        val priorityIds = priorityHead.map { it.id }.toSet()
        val rest = allProxies.filter { it.id !in priorityIds && it.id in subscriptionWlIds }
        val urlHinted = rest.filter { (probeStates[it.id]?.lastUrlMs ?: 0) > 0 }
        val urlIds = urlHinted.map { it.id }.toSet()
        val stratifiedBudget = (WL_PREPARE_CAP - priorityHead.size - urlHinted.size).coerceAtLeast(0)
        val stratified = if (stratifiedBudget > 0) {
            stratifiedSample(
                proxies = rest.filter { it.id !in urlIds },
                totalCap = stratifiedBudget,
            )
        } else {
            emptyList()
        }
        val ordered = (priorityHead + urlHinted + stratified).distinctBy { it.id }.take(WL_PREPARE_CAP)
        return BuildResult(
            priorityFirstIds = priorityFirstIds,
            orderedProxies = ordered,
            subscriptionWlIds = subscriptionWlIds,
            userProxyIds = userProxyIds,
            subsWlMarkedCount = subsWlMarkedCount,
            wlGroupCount = wlGroupCount,
            poolMode = PoolBuildMode.WL_SUBSCRIPTION,
        )
    }

    private fun buildUserOnly(
        userProxies: List<ProxyEntity>,
        handoffIds: Set<Long>,
        userProxyIds: Set<Long>,
        userBoostIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
        subsWlMarkedCount: Int,
        wlGroupCount: Int,
        probeStates: Map<Long, ProxyProbeState>,
    ): BuildResult {
        val priorityFirstIds = userBoostIds.toSet()
        val handoffProxies = userProxies.filter { it.id in handoffIds }
        val handoffIdsSet = handoffProxies.map { it.id }.toSet()
        val listHead = wlListHeadPerGroup(userProxies, USER_LIST_HEAD_PER_GROUP)
        val rest = userProxies.filter { it.id !in handoffIdsSet }
        val urlHinted = rest.filter { (probeStates[it.id]?.lastUrlMs ?: 0) > 0 }
        val urlIds = urlHinted.map { it.id }.toSet()
        val stratifiedBudget = (USER_PREPARE_CAP - handoffProxies.size - listHead.size - urlHinted.size)
            .coerceAtLeast(0)
        val stratified = if (stratifiedBudget > 0) {
            stratifiedSample(
                proxies = rest.filter { it.id !in urlIds && it.id !in listHead.map { p -> p.id }.toSet() },
                totalCap = stratifiedBudget,
            )
        } else {
            emptyList()
        }
        val ordered = (handoffProxies + listHead + urlHinted + stratified)
            .distinctBy { it.id }
            .take(USER_PREPARE_CAP)
        return BuildResult(
            priorityFirstIds = priorityFirstIds,
            orderedProxies = ordered,
            subscriptionWlIds = subscriptionWlIds,
            userProxyIds = userProxyIds,
            subsWlMarkedCount = subsWlMarkedCount,
            wlGroupCount = wlGroupCount,
            poolMode = PoolBuildMode.OPEN,
        )
    }

    private fun buildOpen(
        allProxies: List<ProxyEntity>,
        handoffIds: Set<Long>,
        userBoostIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
        subsWlMarkedCount: Int,
        wlGroupCount: Int,
        userProxyIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState>,
    ): BuildResult {
        val base = allProxies.filter { it.id !in subscriptionWlIds }
        val userHead = if (userProxyIds.isNotEmpty()) {
            wlListHeadPerGroup(base.filter { it.id in userProxyIds }, USER_LIST_HEAD_PER_GROUP)
        } else {
            emptyList()
        }
        val userHeadIds = userHead.map { it.id }.toSet()
        val ordered = if (base.size <= OPEN_PREPARE_CAP) {
            (userHead + base.filter { it.id !in userHeadIds }).distinctBy { it.id }
                .sortedWith(userThenOrderComparator(userProxyIds, userBoostIds))
        } else {
            val handoffProxies = base.filter { it.id in handoffIds }
            val handoffIdsSet = handoffProxies.map { it.id }.toSet()
            val rest = base.filter { it.id !in handoffIdsSet && it.id !in userHeadIds }
            val urlHinted = rest.filter { (probeStates[it.id]?.lastUrlMs ?: 0) > 0 }
            val urlIds = urlHinted.map { it.id }.toSet()
            val budget = (OPEN_PREPARE_CAP - handoffProxies.size - userHead.size - urlHinted.size).coerceAtLeast(0)
            val stratified = stratifiedSample(
                proxies = rest.filter { it.id !in urlIds },
                totalCap = budget,
            )
            (handoffProxies + userHead + urlHinted + stratified).distinctBy { it.id }.take(OPEN_PREPARE_CAP)
        }
        return BuildResult(
            priorityFirstIds = userBoostIds.toSet(),
            orderedProxies = ordered,
            subscriptionWlIds = subscriptionWlIds,
            userProxyIds = userProxyIds,
            subsWlMarkedCount = subsWlMarkedCount,
            wlGroupCount = wlGroupCount,
            poolMode = PoolBuildMode.OPEN,
        )
    }

    private fun buildMerged(
        allProxies: List<ProxyEntity>,
        handoffIds: Set<Long>,
        userBoostIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
        subsWlMarkedCount: Int,
        wlGroupCount: Int,
        userProxyIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState>,
    ): BuildResult {
        val handoffProxies = allProxies.filter { it.id in handoffIds }
        val handoffIdsSet = handoffProxies.map { it.id }.toSet()
        val rest = allProxies.filter { it.id !in handoffIdsSet }
        val userHead = if (userProxyIds.isNotEmpty()) {
            wlListHeadPerGroup(rest.filter { it.id in userProxyIds }, USER_LIST_HEAD_PER_GROUP)
        } else {
            emptyList()
        }
        val userHeadIds = userHead.map { it.id }.toSet()
        val restAfterUser = rest.filter { it.id !in userHeadIds }
        val urlHinted = restAfterUser.filter { (probeStates[it.id]?.lastUrlMs ?: 0) > 0 }
        val urlIds = urlHinted.map { it.id }.toSet()
        val tailPool = restAfterUser.filter { it.id !in urlIds }
        val budget = (MERGED_PREPARE_CAP - handoffProxies.size - userHead.size - urlHinted.size).coerceAtLeast(0)
        val wlTail = tailPool.filter { it.id in subscriptionWlIds }
        val openTail = tailPool.filter { it.id !in subscriptionWlIds }
        val wlBudget = minOf(wlTail.size, budget)
        val stratifiedWl = stratifiedSample(wlTail, totalCap = wlBudget)
        val stratifiedOpen = stratifiedSample(openTail, totalCap = budget - stratifiedWl.size)
        val ordered = (handoffProxies + userHead + urlHinted + stratifiedWl + stratifiedOpen)
            .distinctBy { it.id }
            .take(MERGED_PREPARE_CAP)
        return BuildResult(
            priorityFirstIds = (subscriptionWlIds + userBoostIds).toSet(),
            orderedProxies = ordered,
            subscriptionWlIds = subscriptionWlIds,
            userProxyIds = userProxyIds,
            subsWlMarkedCount = subsWlMarkedCount,
            wlGroupCount = wlGroupCount,
            poolMode = PoolBuildMode.MERGED,
        )
    }

    fun selectionRank(
        profileId: Long,
        subscriptionWlIds: Set<Long>,
        mode: PoolBuildMode,
        userProxyIds: Set<Long> = emptySet(),
        userPoolMode: UserPoolMode = UserPoolMode.OFF,
    ): Int {
        val wlRank = when (mode) {
            PoolBuildMode.WL_SUBSCRIPTION, PoolBuildMode.MERGED -> wlNodeRank(profileId, subscriptionWlIds)
            PoolBuildMode.OPEN -> 0
        }
        val userRank = UserPoolPolicy.userSelectionRank(userPoolMode, profileId, userProxyIds)
        return wlRank * 2 + userRank
    }

    fun wlNodeRank(profileId: Long, subscriptionWlIds: Set<Long>): Int =
        if (profileId in subscriptionWlIds) 0 else 1

    fun userNodeRank(profileId: Long, userProxyIds: Set<Long>): Int =
        if (profileId in userProxyIds) 0 else 1

    fun compactTcpBatch(
        proxies: List<ProxyEntity>,
        priorityFirstIds: Set<Long>,
        maxTotal: Int,
    ): List<ProxyEntity> {
        if (proxies.isEmpty()) return proxies
        val priority = proxies.filter { it.id in priorityFirstIds }
        val restCap = (maxTotal - priority.size).coerceAtLeast(0)
        val rest = proxies.filter { it.id !in priorityFirstIds }.take(restCap)
        return (priority + rest).distinctBy { it.id }.take(maxTotal)
    }

    fun orderForBackgroundProbe(
        proxies: List<ProxyEntity>,
        subscriptionWlIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        merged: Boolean,
        userProxyIds: Set<Long> = emptySet(),
        userPoolMode: UserPoolMode = UserPoolMode.OFF,
    ): List<ProxyEntity> {
        if (proxies.isEmpty()) return proxies
        if (!merged) {
            return proxies.sortedWith(userThenOrderComparator(userProxyIds, userProxyIds))
        }
        val priorityIds = subscriptionWlIds
        val priority = proxies.filter { it.id in priorityIds }.sortedBy { wlNodeRank(it.id, subscriptionWlIds) }
        val rest = proxies.filter { it.id !in priorityIds }
        val userFirst = rest.filter { it.id in userProxyIds }
            .sortedWith(userThenOrderComparator(userProxyIds, userProxyIds))
        val restAfterUser = rest.filter { it.id !in userProxyIds }
        val urlHinted = restAfterUser.filter { (probeStates[it.id]?.lastUrlMs ?: 0) > 0 }
        val urlIds = urlHinted.map { it.id }.toSet()
        val stratified = stratifiedSample(
            proxies = restAfterUser.filter { it.id !in urlIds },
            totalCap = (proxies.size - priority.size - userFirst.size - urlHinted.size).coerceAtLeast(0),
        )
        return (priority + userFirst + urlHinted + stratified).distinctBy { it.id }
    }

    private fun userThenOrderComparator(
        userProxyIds: Set<Long>,
        priorityIds: Set<Long>,
    ): Comparator<ProxyEntity> =
        compareBy<ProxyEntity> { if (it.id in priorityIds) 0 else 1 }
            .thenBy { userNodeRank(it.id, userProxyIds) }
            .thenBy { it.userOrder }
            .thenBy { it.id }

    private fun wlListHeadPerGroup(
        proxies: List<ProxyEntity>,
        perGroup: Int = WL_LIST_HEAD_PER_GROUP,
    ): List<ProxyEntity> = proxies.groupBy { it.groupId }.flatMap { (_, groupProxies) ->
        groupProxies.sortedBy { it.userOrder }.take(perGroup)
    }

    private fun stratifiedSample(
        proxies: List<ProxyEntity>,
        totalCap: Int,
    ): List<ProxyEntity> {
        if (totalCap <= 0 || proxies.isEmpty()) return emptyList()
        // Sort each group once; the round-robin below used to re-sort every group on every
        // round, which made MERGED pool builds quadratic on big subscription groups
        // (~26s on device for a 5k-proxy dominant group; field BS session 2026-08-21).
        val sortedGroups = proxies.groupBy { it.groupId }
            .values
            .map { groupProxies -> groupProxies.sortedBy { it.userOrder } }
            .sortedBy { groupProxies -> groupProxies.first().userOrder }
        val picked = LinkedHashSet<Long>()
        var round = 0
        while (picked.size < totalCap) {
            var added = false
            for (groupProxies in sortedGroups) {
                val proxy = groupProxies.getOrNull(round) ?: continue
                if (picked.add(proxy.id)) {
                    added = true
                    if (picked.size >= totalCap) break
                }
            }
            if (!added) break
            round++
        }
        val orderIndex = picked.withIndex().associate { it.value to it.index }
        return proxies
            .filter { it.id in picked }
            .sortedBy { orderIndex[it.id] ?: Int.MAX_VALUE }
    }
}
