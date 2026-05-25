package fr.husi.database

import fr.husi.bg.classifySubscriptionUpdateError
import fr.husi.utils.simpleModeLog
import kotlin.random.Random

object SubscriptionUpdateStateStore {

    suspend fun loadMap(groupIds: Collection<Long>): Map<Long, SubscriptionUpdateState> {
        if (groupIds.isEmpty()) return emptyMap()
        return SagerDatabase.subscriptionUpdateStateDao
            .getByGroupIds(groupIds.distinct())
            .associateBy { it.groupId }
    }

    suspend fun recordSuccess(groupId: Long) {
        if (groupId <= 0L) return
        val now = System.currentTimeMillis()
        val prev = SagerDatabase.subscriptionUpdateStateDao.getByGroupId(groupId)
        val next = SubscriptionUpdateState(
            groupId = groupId,
            state = SubUpdateState.OK,
            failCountConsecutive = 0,
            lastAttemptAtMs = now,
            nextAttemptAtMs = 0L,
            lastErrorClass = "",
        )
        SagerDatabase.subscriptionUpdateStateDao.upsertAll(listOf(next))
        logStateTransition(groupId, prev?.state, next)
    }

    suspend fun recordFailureFromMessage(groupId: Long, errorMessage: String) {
        if (groupId <= 0L) return
        recordFailure(groupId, classifySubscriptionUpdateError(errorMessage))
    }

    suspend fun recordFailure(groupId: Long, errorClass: String) {
        if (groupId <= 0L) return
        val now = System.currentTimeMillis()
        val prev = SagerDatabase.subscriptionUpdateStateDao.getByGroupId(groupId)
        val next = applyFailure(prev, groupId, errorClass, now)
        SagerDatabase.subscriptionUpdateStateDao.upsertAll(listOf(next))
        logStateTransition(groupId, prev?.state, next)
    }

    internal fun applyFailure(
        prev: SubscriptionUpdateState?,
        groupId: Long,
        errorClass: String,
        nowMs: Long,
    ): SubscriptionUpdateState {
        val failStreak = (prev?.failCountConsecutive ?: 0) + 1
        val state = when {
            errorClass == SubscriptionUpdateErrorClass.HTTP_PERMANENT && failStreak >= 1 ->
                SubUpdateState.JAIL
            failStreak >= 3 -> SubUpdateState.JAIL
            failStreak >= 1 -> SubUpdateState.SUSPECT
            else -> prev?.state ?: SubUpdateState.OK
        }
        val nextAttemptAtMs = computeNextAttemptAt(state, failStreak, nowMs)
        return SubscriptionUpdateState(
            groupId = groupId,
            state = state,
            failCountConsecutive = failStreak,
            lastAttemptAtMs = nowMs,
            nextAttemptAtMs = nextAttemptAtMs,
            lastErrorClass = errorClass,
        )
    }

    internal fun computeNextAttemptAt(state: Int, failStreak: Int, nowMs: Long): Long {
        val base = when (state) {
            SubUpdateState.SUSPECT -> Probe2kDefaults.SUSPECT_RETRY_MS
            SubUpdateState.JAIL -> Probe2kDefaults.CEMETERY_BACKOFF_MS
            else -> 0L
        }
        if (base <= 0L) return 0L
        val jitter = Random.nextLong(0, (base / 8).coerceAtLeast(1_000L))
        return nowMs + base + jitter
    }

    private fun logStateTransition(groupId: Long, prevState: Int?, next: SubscriptionUpdateState) {
        when {
            prevState == SubUpdateState.JAIL && next.state == SubUpdateState.OK ->
                simpleModeLog("SimpleMode", "H39 sub_unjail groupId=$groupId from=JAIL to=OK")
            prevState != SubUpdateState.JAIL && next.state == SubUpdateState.JAIL ->
                simpleModeLog(
                    "SimpleMode",
                    "H39 sub_jail groupId=$groupId errorClass=${next.lastErrorClass} failStreak=${next.failCountConsecutive}",
                )
        }
        simpleModeLog(
            "SimpleMode",
            "H39 sub_update_state groupId=$groupId state=${stateLabel(next.state)} " +
                "failStreak=${next.failCountConsecutive} nextAt=${next.nextAttemptAtMs} " +
                "errorClass=${next.lastErrorClass.ifBlank { "-" }}",
        )
    }

    private fun stateLabel(state: Int): String = when (state) {
        SubUpdateState.OK -> "OK"
        SubUpdateState.SUSPECT -> "SUSPECT"
        SubUpdateState.JAIL -> "JAIL"
        else -> "UNKNOWN"
    }
}
