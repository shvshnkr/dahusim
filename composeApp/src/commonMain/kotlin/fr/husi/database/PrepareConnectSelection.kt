package fr.husi.database

import fr.husi.simplemode.SimpleModeHealthRoute

/**
 * Prepare-time profile pick: fresh probe results outweigh stale [ProxyEntity.status].
 */
internal object PrepareConnectSelection {

    fun hasFreshPrepareUrl(id: Long, urlTestDelays: Map<Long, Int>): Boolean = id in urlTestDelays

    fun hasFreshPrepareTcpAndUrl(
        id: Long,
        urlTestDelays: Map<Long, Int>,
        quickProbePings: Map<Long, Int>,
    ): Boolean = id in urlTestDelays && (quickProbePings[id] ?: 0) > 0

    /** Status used only for prepare ranking — fresh URL ok downgrades stale UNAVAILABLE/UNREACHABLE. */
    fun effectiveStatusForRanking(proxy: ProxyEntity, urlTestDelays: Map<Long, Int>): Int {
        if (proxy.id !in urlTestDelays) return proxy.status
        return when (proxy.status) {
            ProxyEntity.STATUS_UNAVAILABLE,
            ProxyEntity.STATUS_UNREACHABLE,
                -> ProxyEntity.STATUS_INITIAL
            else -> proxy.status
        }
    }

    fun openPreparePathTier(
        id: Long,
        wlUrlProbes: Boolean,
        urlTestDelays: Map<Long, Int>,
        quickProbePings: Map<Long, Int>,
    ): Int {
        if (wlUrlProbes || !SimpleModeHealthRoute.messengerProbeRequired(false)) return 0
        return when {
            hasFreshPrepareTcpAndUrl(id, urlTestDelays, quickProbePings) -> 0
            hasFreshPrepareUrl(id, urlTestDelays) -> 1
            else -> 2
        }
    }

    fun isSelectableDespiteStaleStatus(
        id: Long,
        status: Int,
        urlTestDelays: Map<Long, Int>,
    ): Boolean {
        if (id in urlTestDelays) {
            return status != ProxyEntity.STATUS_INVALID
        }
        return status != ProxyEntity.STATUS_UNAVAILABLE &&
            status != ProxyEntity.STATUS_INVALID &&
            status != ProxyEntity.STATUS_UNREACHABLE
    }

    fun selectBestOpenProfile(
        rankedFinal: List<Long>,
        profilesById: Map<Long, ProxyEntity>,
        probeStates: Map<Long, ProxyProbeState>,
        urlTestDelays: Map<Long, Int>,
        quickProbePings: Map<Long, Int>,
        isInFailureCooldown: (Long) -> Boolean,
    ): Long {
        val viable = rankedFinal.filter {
            ProbePoolEligibility.isViableFallbackTarget(probeStates[it], isInFailureCooldown(it))
        }
        val urlVerified = viable.filter { it in urlTestDelays }
        val ordered = if (quickProbePings.isNotEmpty() && SimpleModeHealthRoute.messengerProbeRequired(false)) {
            urlVerified.sortedBy { openPreparePathTier(it, wlUrlProbes = false, urlTestDelays, quickProbePings) }
        } else {
            urlVerified
        }
        return preferSelectable(ordered, profilesById, urlTestDelays)
            ?: preferSelectable(viable, profilesById, urlTestDelays)
            ?: rankedFinal.first()
    }

    fun selectBestWlProfile(
        rankedFinal: List<Long>,
        profilesById: Map<Long, ProxyEntity>,
        probeStates: Map<Long, ProxyProbeState>,
        urlTestDelays: Map<Long, Int>,
        isInFailureCooldown: (Long) -> Boolean,
    ): Long {
        val viable = rankedFinal.filter {
            ProbePoolEligibility.isViableFallbackTarget(probeStates[it], isInFailureCooldown(it))
        }
        // Fresh this-run URL verification outranks stale persisted viability (DEAD/jail recorded
        // when the server was down — field 2026-08-18 03:17: verified 340 was skipped for stale
        // 99, costing a dead-tunnel connect + post-connect recover).
        val urlVerified = rankedFinal.filter { it in urlTestDelays }
        preferSelectable(urlVerified, profilesById, urlTestDelays)?.let { return it }
        viable.firstOrNull {
            AutoServerSelectorProbePolicy.wlPrepareHasUrlConfirmation(it, urlTestDelays, probeStates)
        }?.let { return it }
        return rankedFinal.first()
    }

    private fun preferSelectable(
        ids: List<Long>,
        profilesById: Map<Long, ProxyEntity>,
        urlTestDelays: Map<Long, Int>,
    ): Long? = ids.firstOrNull { id ->
        val status = profilesById[id]?.status ?: ProxyEntity.STATUS_INITIAL
        isSelectableDespiteStaleStatus(id, status, urlTestDelays)
    } ?: ids.firstOrNull()

    /**
     * When OPEN messenger path has TCP+URL survivors, demote a URL-only best to the next TCP+URL node.
     */
    fun demoteUrlOnlyBestIfNeeded(
        best: Long,
        rankedFinal: List<Long>,
        urlTestDelays: Map<Long, Int>,
        quickProbePings: Map<Long, Int>,
        probeStates: Map<Long, ProxyProbeState>,
        isInFailureCooldown: (Long) -> Boolean,
    ): Long {
        if (!SimpleModeHealthRoute.messengerProbeRequired(false)) return best
        if (hasFreshPrepareTcpAndUrl(best, urlTestDelays, quickProbePings)) return best
        val hasTcpUrlCandidate = rankedFinal.any {
            hasFreshPrepareTcpAndUrl(it, urlTestDelays, quickProbePings)
        }
        if (!hasTcpUrlCandidate) return best
        val replacement = rankedFinal.firstOrNull { id ->
            id != best &&
                hasFreshPrepareTcpAndUrl(id, urlTestDelays, quickProbePings) &&
                ProbePoolEligibility.isViableFallbackTarget(probeStates[id], isInFailureCooldown(id))
        } ?: return best
        return replacement
    }
}
