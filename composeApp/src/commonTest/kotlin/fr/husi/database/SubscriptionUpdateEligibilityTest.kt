package fr.husi.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionUpdateEligibilityTest {

    @Test
    fun pickUnjailGroupId_choosesEarliestNextAttempt() {
        val nowMs = 10_000_000L
        val states = mapOf(
            1L to SubscriptionUpdateState(
                groupId = 1L,
                state = SubUpdateState.JAIL,
                nextAttemptAtMs = nowMs - 5_000L,
            ),
            2L to SubscriptionUpdateState(
                groupId = 2L,
                state = SubUpdateState.JAIL,
                nextAttemptAtMs = nowMs - 1_000L,
            ),
        )
        assertEquals(1L, SubscriptionUpdateEligibility.pickUnjailGroupId(states, nowMs))
    }

    @Test
    fun isDueForScheduledUpdate_suspectWithFutureBackoffNotDue() {
        val nowMs = 100_000L
        val state = SubscriptionUpdateState(
            groupId = 3L,
            state = SubUpdateState.SUSPECT,
            nextAttemptAtMs = nowMs + 60_000L,
        )
        assertFalse(
            SubscriptionUpdateEligibility.isDueForScheduledUpdate(
                groupId = 3L,
                secondsUntilDue = 0L,
                state = state,
                nowMs = nowMs,
                unjailGroupId = null,
            ),
        )
    }

    @Test
    fun isDueForScheduledUpdate_jailOnlyWhenUnjailMatches() {
        val nowMs = 200_000L
        val state = SubscriptionUpdateState(
            groupId = 4L,
            state = SubUpdateState.JAIL,
            nextAttemptAtMs = nowMs - 1L,
        )
        assertFalse(
            SubscriptionUpdateEligibility.isDueForScheduledUpdate(
                groupId = 4L,
                secondsUntilDue = 0L,
                state = state,
                nowMs = nowMs,
                unjailGroupId = 99L,
            ),
        )
        assertTrue(
            SubscriptionUpdateEligibility.isDueForScheduledUpdate(
                groupId = 4L,
                secondsUntilDue = 0L,
                state = state,
                nowMs = nowMs,
                unjailGroupId = 4L,
            ),
        )
    }

    @Test
    fun filterConnectRefreshCandidates_okPlusOneSuspect() {
        val nowMs = 50_000L
        val ok = group(1L, "ok")
        val suspectA = group(2L, "suspect-a")
        val suspectB = group(3L, "suspect-b")
        val states = mapOf(
            2L to SubscriptionUpdateState(groupId = 2L, state = SubUpdateState.SUSPECT),
            3L to SubscriptionUpdateState(groupId = 3L, state = SubUpdateState.SUSPECT),
        )
        val filtered = SubscriptionUpdateEligibility.filterConnectRefreshCandidates(
            groups = listOf(ok, suspectA, suspectB),
            states = states,
            nowMs = nowMs,
        )
        assertEquals(listOf("ok", "suspect-a"), filtered.map { it.name })
    }

    @Test
    fun sortConnectRefreshQueue_wlFeedsFirst() {
        val open = group(1L, "open", ConnectPoolRole.OPEN)
        val wl = group(2L, "wl", ConnectPoolRole.WL)
        val sorted = SubscriptionUpdateEligibility.sortConnectRefreshQueue(
            groups = listOf(open, wl),
            states = emptyMap(),
        )
        assertEquals(listOf("wl", "open"), sorted.map { it.name })
    }

    private fun group(id: Long, name: String, poolRole: Int = ConnectPoolRole.ANY) = ProxyGroup().apply {
        this.id = id
        this.name = name
        subscription = SubscriptionBean().apply {
            connectPoolRole = poolRole
        }
    }
}
