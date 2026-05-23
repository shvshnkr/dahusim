package fr.husi.database

/**
 * Single place for simple-mode connect pool: WL stratified cap, open-net builtin deprioritization,
 * compact TCP batch order, and ranking tie-breaks (replaces split [WlAutoselectPolicy] / [BuiltinPoolPolicy] logic).
 */
internal object ConnectPoolPolicy {

    const val WL_PREPARE_CAP = 128
    const val OPEN_PREPARE_CAP = 256
    const val WL_STRATIFIED_PER_GROUP = 3
    const val OPEN_STRATIFIED_PER_GROUP = 4
    const val MAX_SESSION_FALLBACK_STEPS_WL = 4
    const val MAX_SESSION_FALLBACK_STEPS_OPEN = 32

    data class BuildResult(
        val priorityFirstIds: Set<Long>,
        val orderedProxies: List<ProxyEntity>,
        val subscriptionWlIds: Set<Long>,
        val subsWlMarkedCount: Int,
        val wlGroupCount: Int,
    )

    fun maxSessionFallbackSteps(whitelistRestricted: Boolean): Int =
        if (whitelistRestricted) MAX_SESSION_FALLBACK_STEPS_WL else MAX_SESSION_FALLBACK_STEPS_OPEN

    fun build(
        allProxies: List<ProxyEntity>,
        groups: List<ProxyGroup>,
        builtinProxies: List<ProxyEntity>,
        builtinIds: Set<Long>,
        handoffIds: Set<Long>,
        whitelistBuiltinOnly: Boolean,
        probeStates: Map<Long, ProxyProbeState>,
    ): BuildResult {
        val tag = WlSubscriptionTag.resolve(allProxies, groups)
        return if (whitelistBuiltinOnly) {
            buildWhitelist(
                allProxies = allProxies,
                builtinProxies = builtinProxies,
                builtinIds = builtinIds,
                handoffIds = handoffIds,
                subscriptionWlIds = tag.subscriptionWlProxyIds,
                subsWlMarkedCount = tag.subsWlMarkedCount,
                wlGroupCount = tag.wlGroupIds.size,
                probeStates = probeStates,
            )
        } else {
            buildOpen(
                allProxies = allProxies,
                builtinIds = builtinIds,
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
        builtinProxies: List<ProxyEntity>,
        builtinIds: Set<Long>,
        handoffIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
        subsWlMarkedCount: Int,
        wlGroupCount: Int,
        probeStates: Map<Long, ProxyProbeState>,
    ): BuildResult {
        val priorityFirstIds = (builtinIds + subscriptionWlIds + handoffIds).toSet()
        val subWlProxies = allProxies
            .filter { it.id in subscriptionWlIds }
            .sortedBy { it.userOrder }
        val handoffProxies = allProxies.filter { it.id in handoffIds && it.id !in builtinIds && it.id !in subscriptionWlIds }
        val priorityHead = (builtinProxies + subWlProxies + handoffProxies).distinctBy { it.id }
        val priorityIds = priorityHead.map { it.id }.toSet()
        val rest = allProxies.filter { it.id !in priorityIds }
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
        )
    }

    private fun buildOpen(
        allProxies: List<ProxyEntity>,
        builtinIds: Set<Long>,
        handoffIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
        subsWlMarkedCount: Int,
        wlGroupCount: Int,
        probeStates: Map<Long, ProxyProbeState>,
    ): BuildResult {
        val base = allProxies.filter { it.id !in subscriptionWlIds }
        val ordered = if (base.size <= OPEN_PREPARE_CAP) {
            reorderForCompactProbe(base, builtinIds, whitelistBuiltinOnly = false)
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
            reorderForCompactProbe(
                handoffProxies + urlHinted + stratified,
                builtinIds,
                whitelistBuiltinOnly = false,
            ).take(OPEN_PREPARE_CAP)
        }
        return BuildResult(
            priorityFirstIds = handoffIds,
            orderedProxies = ordered,
            subscriptionWlIds = subscriptionWlIds,
            subsWlMarkedCount = subsWlMarkedCount,
            wlGroupCount = wlGroupCount,
        )
    }

    fun reorderForCompactProbe(
        proxies: List<ProxyEntity>,
        builtinProfileIds: Set<Long>,
        whitelistBuiltinOnly: Boolean,
    ): List<ProxyEntity> {
        if (whitelistBuiltinOnly || builtinProfileIds.isEmpty()) return proxies
        val (subscription, builtin) = proxies.partition { it.id !in builtinProfileIds }
        return subscription + builtin
    }

    fun openNetSelectionRank(
        profileId: Long,
        builtinProfileIds: Set<Long>,
        whitelistBuiltinOnly: Boolean,
        subscriptionWlIds: Set<Long> = emptySet(),
    ): Int = when {
        whitelistBuiltinOnly -> wlNodeRank(profileId, builtinProfileIds, subscriptionWlIds)
        profileId in builtinProfileIds -> 1
        else -> 0
    }

    fun wlNodeRank(
        profileId: Long,
        builtinProfileIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
    ): Int = when {
        profileId in builtinProfileIds -> 0
        profileId in subscriptionWlIds -> 1
        else -> 2
    }

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
        builtinIds: Set<Long>,
        subscriptionWlIds: Set<Long>,
        probeStates: Map<Long, ProxyProbeState>,
    ): List<ProxyEntity> {
        if (proxies.isEmpty()) return proxies
        val priorityIds = (builtinIds + subscriptionWlIds).toSet()
        val priority = proxies.filter { it.id in priorityIds }.sortedBy { wlNodeRank(it.id, builtinIds, subscriptionWlIds) }
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
