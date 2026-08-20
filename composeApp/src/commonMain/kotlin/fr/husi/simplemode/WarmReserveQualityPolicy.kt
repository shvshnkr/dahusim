package fr.husi.simplemode

import fr.husi.database.AutoServerSelectorProbePolicy
import fr.husi.database.Probe2kDefaults
import fr.husi.database.ProbeState
import fr.husi.database.ProxyProbeState
import fr.husi.database.ProxyProbeStateStore

data class WarmQualitySample(
    val profileId: Long,
    val liveUrlMs: Int?,
    val probeState: ProxyProbeState?,
    val nowMs: Long = System.currentTimeMillis(),
)

sealed class WarmSwitchDecision {
    data class SwitchTo(val profileId: Long) : WarmSwitchDecision()
    data object AlreadyOnBest : WarmSwitchDecision()
    data object NoReserves : WarmSwitchDecision()
    data object NoLiveData : WarmSwitchDecision()
}

/** Lower score is better — aligned with [fr.husi.database.AutoServerSelector] warm ranking. */
internal object WarmReserveQualityPolicy {

    private const val STATE_RANK_WEIGHT = 1_000
    private const val FAIL_STREAK_WEIGHT = 500
    private const val FRESH_URL_BONUS_MS = 30
    private const val DEGRADED_SCORE = Int.MAX_VALUE - 10_000

    fun qualityScore(sample: WarmQualitySample): Int {
        val state = sample.probeState
        if (state != null && (state.state == ProbeState.DEAD || state.state == ProbeState.CEMETERY)) {
            return Int.MAX_VALUE
        }
        if (AutoServerSelectorProbePolicy.isRecentlyDegraded(profileId = sample.profileId, nowMs = sample.nowMs)) {
            return DEGRADED_SCORE
        }
        val base = when {
            sample.liveUrlMs != null && sample.liveUrlMs > 0 -> sample.liveUrlMs
            state != null -> ProxyProbeStateStore.persistedDelayScore(state)
            else -> Int.MAX_VALUE / 2
        }
        val statePenalty = ProxyProbeStateStore.probeStateRank(state) * STATE_RANK_WEIGHT
        val failPenalty = (state?.failCountConsecutive ?: 0) * FAIL_STREAK_WEIGHT
        val freshBonus = if (ProxyProbeStateStore.isFreshUrlVerified(state, sample.nowMs)) {
            -FRESH_URL_BONUS_MS
        } else {
            0
        }
        return (base + statePenalty + failPenalty + freshBonus).coerceAtLeast(0)
    }

    fun compareForManualSwitch(
        connectedId: Long,
        reserveIds: List<Long>,
        liveUrlMs: Map<Long, Int?>,
        probeStates: Map<Long, ProxyProbeState>,
        nowMs: Long = System.currentTimeMillis(),
    ): WarmSwitchDecision {
        if (reserveIds.isEmpty()) return WarmSwitchDecision.NoReserves
        val connectedScore = qualityScore(
            WarmQualitySample(
                profileId = connectedId,
                liveUrlMs = liveUrlMs[connectedId],
                probeState = probeStates[connectedId],
                nowMs = nowMs,
            ),
        )
        val viableReserves = reserveIds.mapNotNull { id ->
            val score = qualityScore(
                WarmQualitySample(
                    profileId = id,
                    liveUrlMs = liveUrlMs[id],
                    probeState = probeStates[id],
                    nowMs = nowMs,
                ),
            )
            if (score >= Int.MAX_VALUE / 2) null else id to score
        }
        if (viableReserves.isEmpty()) {
            return if (connectedScore >= Int.MAX_VALUE / 2) {
                WarmSwitchDecision.NoLiveData
            } else {
                WarmSwitchDecision.AlreadyOnBest
            }
        }
        val (bestId, bestScore) = viableReserves.minBy { it.second }
        return if (bestScore >= connectedScore - Probe2kDefaults.WARM_QUALITY_TIE_EPSILON_MS) {
            WarmSwitchDecision.AlreadyOnBest
        } else {
            WarmSwitchDecision.SwitchTo(bestId)
        }
    }
}
