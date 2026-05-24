package fr.husi.database

/**
 * Simple-mode connect pool: WL subscription cap, open-net pool, merged WL+open session pool.
 */
internal object ConnectPoolPolicy {

    const val WL_PREPARE_CAP = 128
    const val OPEN_PREPARE_CAP = 256
    const val WL_STRATIFIED_PER_GROUP = 3
    const val OPEN_STRATIFIED_PER_GROUP = 4
    const val MAX_SESSION_FALLBACK_STEPS_WL = 4
    const val MAX_SESSION_FALLBACK_STEPS_OPEN = 32

    enum class PoolBuildMode {
        /** All profiles except WL-marked subscriptions. */
        OPEN,
        /** WL-marked subscription nodes only. */
        WL_SUBSCRIPTION,
        /** WL-marked + open nodes (after WL pool failed once on this session). */
        MERGED,
    }

    data class BuildResult(
        val priorityFirstIds: Set<Long>,
        val orderedProxies: List<ProxyEntity>,
        val subscriptionWlIds: Set<Long>,
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
    ): BuildResult {
        val tag = WlSubscriptionTag.resolve(allProxies, groups)
        return when (mode) {
            PoolBuildMode.WL_SUBSCRIPTION -> buildWhitelist(
                allProxies = allProxies,
                handoffIds = handoffIds,
                subscriptionWlIds = tag.subscriptionWlProxyIds,
                subsWlMarkedCount = tag.subsWlMarkedCount,
                wlGroupCount = tag.wlGroupIds.size,
                probeStates = probeStates,
            )
            PoolBuildMode.MERGED -> buildMerged(
                allProxies = allProxies,
                handoffIds = handoffIds,
                subscriptionWlIds = tag.subscriptionWlProxyIds,
                subsWlMarkedCount = tag.subsWlMarkedCount,
                wlGroupCount = tag.wlGroupIds.size,
                probeStates = probeStates,
            )
            PoolBuildMode.OPEN -> buildOpen(
                allProxies = allProxies,
                handoffIds = handoffIds,
                subscriptionWlIds = tag.subscriptionWlProxyIds,
                subsWlMarkedCount = tag.subsWlMarkedCount,
                wlGroupCount = tag.wlGroupIds.size,
                probeStates = probeStates,
            )
        }
    }

    private fun buildWhitelist(
        allProxies: List<ProxyEntity>,
        handoffIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
        subsWlMarkedCount: Int,
        wlGroupCount: Int,
        probeStates: Map<Long, ProxyProbeState>,
    ): BuildResult {
        val priorityFirstIds = (subscriptionWlIds + handoffIds).toSet()
        val subWlPool = allProxies.filter { it.id in subscriptionWlIds }
        val subWlProxies = stratifiedSample(
            proxies = subWlPool,
            perGroupCap = WL_STRATIFIED_PER_GROUP,
            totalCap = minOf(subWlPool.size, WL_PREPARE_CAP / 2),
        )
        val handoffProxies = allProxies.filter { it.id in handoffIds && it.id !in subscriptionWlIds }
        val priorityHead = (handoffProxies + subWlProxies).distinctBy { it.id }
        val priorityIds = priorityHead.map { it.id }.toSet()
        val rest = allProxies.filter { it.id !in priorityIds && it.id in subscriptionWlIds }
        val urlHinted = rest.filter { (probeStates[it.id]?.lastUrlMs ?: 0) > 0 }
        val urlIds = urlHinted.map { it.id }.toSet()
        val stratifiedBudget = (WL_PREPARE_CAP - priorityHead.size - urlHinted.size).coerceAtLeast(0)
        val stratified = if (stratifiedBudget > 0) {
            stratifiedSample(
                proxies = rest.filter { it.id !in urlIds },
                perGroupCap = WL_STRATIFIED_PER_GROUP,
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
            subsWlMarkedCount = subsWlMarkedCount,
            wlGroupCount = wlGroupCount,
            poolMode = PoolBuildMode.WL_SUBSCRIPTION,
        )
    }

    private fun buildOpen(
        allProxies: List<ProxyEntity>,
        handoffIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
        subsWlMarkedCount: Int,
        wlGroupCount: Int,
        probeStates: Map<Long, ProxyProbeState>,
    ): BuildResult {
        val base = allProxies.filter { it.id !in subscriptionWlIds }
        val ordered = if (base.size <= OPEN_PREPARE_CAP) {
            base.sortedBy { it.userOrder }
        } else {
            val handoffProxies = base.filter { it.id in handoffIds }
            val handoffIdsSet = handoffProxies.map { it.id }.toSet()
            val rest = base.filter { it.id !in handoffIdsSet }
            val urlHinted = rest.filter { (probeStates[it.id]?.lastUrlMs ?: 0) > 0 }
            val urlIds = urlHinted.map { it.id }.toSet()
            val budget = (OPEN_PREPARE_CAP - handoffProxies.size - urlHinted.size).coerceAtLeast(0)
            val stratified = stratifiedSample(
                proxies = rest.filter { it.id !in urlIds },
                perGroupCap = OPEN_STRATIFIED_PER_GROUP,
                totalCap = budget,
            )
            (handoffProxies + urlHinted + stratified).distinctBy { it.id }.take(OPEN_PREPARE_CAP)
        }
        return BuildResult(
            priorityFirstIds = handoffIds,
            orderedProxies = ordered,
            subscriptionWlIds = subscriptionWlIds,
            subsWlMarkedCount = subsWlMarkedCount,
            wlGroupCount = wlGroupCount,
            poolMode = PoolBuildMode.OPEN,
        )
    }

    private fun buildMerged(
        allProxies: List<ProxyEntity>,
        handoffIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
        subsWlMarkedCount: Int,
        wlGroupCount: Int,
        probeStates: Map<Long, ProxyProbeState>,
    ): BuildResult {
        val handoffProxies = allProxies.filter { it.id in handoffIds }
        val handoffIdsSet = handoffProxies.map { it.id }.toSet()
        val rest = allProxies.filter { it.id !in handoffIdsSet }
        val urlHinted = rest.filter { (probeStates[it.id]?.lastUrlMs ?: 0) > 0 }
        val urlIds = urlHinted.map { it.id }.toSet()
        val budget = (OPEN_PREPARE_CAP - handoffProxies.size - urlHinted.size).coerceAtLeast(0)
        val stratified = stratifiedSample(
            proxies = rest.filter { it.id !in urlIds },
            perGroupCap = OPEN_STRATIFIED_PER_GROUP,
            totalCap = budget,
        )
        val ordered = (handoffProxies + urlHinted + stratified).distinctBy { it.id }.take(OPEN_PREPARE_CAP)
        return BuildResult(
            priorityFirstIds = handoffIds,
            orderedProxies = ordered,
            subscriptionWlIds = subscriptionWlIds,
            subsWlMarkedCount = subsWlMarkedCount,
            wlGroupCount = wlGroupCount,
            poolMode = PoolBuildMode.MERGED,
        )
    }

    fun selectionRank(
        profileId: Long,
        subscriptionWlIds: Set<Long>,
        mode: PoolBuildMode,
    ): Int = when (mode) {
        PoolBuildMode.WL_SUBSCRIPTION -> wlNodeRank(profileId, subscriptionWlIds)
        PoolBuildMode.OPEN, PoolBuildMode.MERGED -> 0
    }

    fun wlNodeRank(profileId: Long, subscriptionWlIds: Set<Long>): Int =
        if (profileId in subscriptionWlIds) 0 else 1

    fun compactTcpBatch(
        proxies: List<ProxyEntity>,
        priorityFirstIds: Set<Long>,
        maxTotal: Int,
    ): List<ProxyEntity> {
        if (proxies.isEmpty()) return proxies
        val priority = proxies.filter { it.id in priorityFirstIds }
        val restCap = (maxTotal - priority.size).coerceAtLeast(0)
        val rest = proxies.filter { it.id !in priorityFirstIds }.take(restCap)
        return (priority + rest).distinctBy { it.id }
    }

    fun orderForBackgroundProbe(
        proxies: List<ProxyEntity>,
        subscriptionWlIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState>,
        merged: Boolean,
    ): List<ProxyEntity> {
        if (proxies.isEmpty()) return proxies
        if (!merged) {
            return proxies.sortedBy { it.userOrder }
        }
        val priorityIds = subscriptionWlIds
        val priority = proxies.filter { it.id in priorityIds }.sortedBy { wlNodeRank(it.id, subscriptionWlIds) }
        val rest = proxies.filter { it.id !in priorityIds }
        val urlHinted = rest.filter { (probeStates[it.id]?.lastUrlMs ?: 0) > 0 }
        val urlIds = urlHinted.map { it.id }.toSet()
        val stratified = stratifiedSample(
            proxies = rest.filter { it.id !in urlIds },
            perGroupCap = WL_STRATIFIED_PER_GROUP,
            totalCap = (proxies.size - priority.size - urlHinted.size).coerceAtLeast(0),
        )
        return (priority + urlHinted + stratified).distinctBy { it.id }
    }

    private fun stratifiedSample(
        proxies: List<ProxyEntity>,
        perGroupCap: Int,
        totalCap: Int,
    ): List<ProxyEntity> {
        if (totalCap <= 0 || proxies.isEmpty()) return emptyList()
        val byGroup = proxies.groupBy { it.groupId }
        val orderedGroups = byGroup.entries.sortedBy { (_, list) -> list.minOf { it.userOrder } }
        val picked = LinkedHashSet<Long>()
        var round = 0
        while (picked.size < totalCap && round < perGroupCap) {
            var added = false
            for ((_, groupProxies) in orderedGroups) {
                val sorted = groupProxies.sortedBy { it.userOrder }
                val proxy = sorted.getOrNull(round) ?: continue
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
