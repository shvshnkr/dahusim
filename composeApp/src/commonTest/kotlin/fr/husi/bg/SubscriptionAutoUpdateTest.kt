package fr.husi.bg

import fr.husi.database.ConnectPoolRole
import fr.husi.database.ProxyGroup
import fr.husi.database.SubUpdateState
import fr.husi.database.SubscriptionBean
import fr.husi.database.SubscriptionUpdateEligibility
import fr.husi.database.SubscriptionUpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionAutoUpdateTest {

    @Test
    fun `planner uses shortest interval and earliest due time`() {
        val nowSeconds = 10_000L
        val plan = SubscriptionAutoUpdatePlanner.plan(
            subscriptions = listOf(
                autoUpdateGroup(name = "slow", delayMinutes = 60, lastUpdated = (nowSeconds - 30 * 60).toInt()),
                autoUpdateGroup(name = "fast", delayMinutes = 15, lastUpdated = (nowSeconds - 5 * 60).toInt()),
            ),
            nowSeconds = nowSeconds,
        )

        assertEquals(15, plan?.repeatIntervalMinutes)
        assertEquals(10 * 60L, plan?.initialDelaySeconds)
    }

    @Test
    fun `planner clamps non-positive interval to one minute`() {
        val plan = SubscriptionAutoUpdatePlanner.plan(
            subscriptions = listOf(
                autoUpdateGroup(
                    name = "clamped",
                    delayMinutes = 0,
                    lastUpdated = 100,
                ),
            ),
            nowSeconds = 100,
        )

        assertEquals(1, plan?.repeatIntervalMinutes)
        assertEquals(60L, plan?.initialDelaySeconds)
    }

    @Test
    fun `autoUpdateGroup sets delay and overdue is due`() {
        val g = autoUpdateGroup(name = "overdue", delayMinutes = 30, lastUpdated = 100)
        assertEquals(30, g.subscription!!.autoUpdateDelay)
        assertEquals(
            0L,
            secondsUntilDue(
                lastUpdatedSeconds = 100L,
                repeatIntervalMinutes = 30,
                nowSeconds = 100 + 31L * 60L,
            ),
        )
    }

    @Test
    fun `due subscriptions treat overdue profiles as due immediately`() {
        val dueSubscriptions = SubscriptionAutoUpdateRunner.dueSubscriptions(
            subscriptions = listOf(
                autoUpdateGroup(
                    name = "overdue",
                    delayMinutes = 30,
                    lastUpdated = 100,
                ),
                autoUpdateGroup(
                    name = "not-due",
                    delayMinutes = 30,
                    lastUpdated = 100 + 25 * 60,
                ),
            ),
            nowSeconds = 100 + 31L * 60L,
            connected = true,
        )

        assertEquals(listOf("overdue"), dueSubscriptions.map { it.name })
    }

    @Test
    fun `planner returns null when there are no subscriptions`() {
        assertNull(
            SubscriptionAutoUpdatePlanner.plan(
                subscriptions = emptyList(),
                nowSeconds = 10_000L,
            ),
        )
    }

    @Test
    fun `due subscriptions skip connected-only profiles while disconnected`() {
        val dueSubscriptions = SubscriptionAutoUpdateRunner.dueSubscriptions(
            subscriptions = listOf(
                autoUpdateGroup(
                    name = "plain-due",
                    delayMinutes = 15,
                    lastUpdated = 0,
                ),
                autoUpdateGroup(
                    name = "connected-only-due",
                    delayMinutes = 15,
                    lastUpdated = 0,
                    updateWhenConnectedOnly = true,
                ),
                autoUpdateGroup(
                    name = "plain-not-due",
                    delayMinutes = 15,
                    lastUpdated = 15 * 60,
                ),
            ),
            nowSeconds = 20 * 60L,
            connected = false,
        )

        assertEquals(listOf("plain-due"), dueSubscriptions.map { it.name })
    }

    @Test
    fun `due subscriptions keep connected-only profiles while connected`() {
        val dueSubscriptions = SubscriptionAutoUpdateRunner.dueSubscriptions(
            subscriptions = listOf(
                autoUpdateGroup(
                    name = "connected-only-due",
                    delayMinutes = 15,
                    lastUpdated = 0,
                    updateWhenConnectedOnly = true,
                ),
                autoUpdateGroup(
                    name = "plain-due",
                    delayMinutes = 15,
                    lastUpdated = 0,
                ),
                autoUpdateGroup(
                    name = "connected-only-not-due",
                    delayMinutes = 15,
                    lastUpdated = 10 * 60,
                    updateWhenConnectedOnly = true,
                ),
            ),
            nowSeconds = 20 * 60L,
            connected = true,
        )

        assertEquals(
            listOf("connected-only-due", "plain-due"),
            dueSubscriptions.map { it.name },
        )
        assertFalse(dueSubscriptions.any { it.name == "connected-only-not-due" })
    }

    @Test
    fun `foreground mode returns configured higher parallelism`() {
        val p = SubscriptionAutoUpdateRunner.previewParallelism(
            mode = SubscriptionUpdateMode.ForegroundInteractive,
            foregroundParallelism = 5,
            backgroundParallelism = 1,
        )
        assertEquals(5, p)
    }

    @Test
    fun `background mode stays battery-friendly by default`() {
        val p = SubscriptionAutoUpdateRunner.previewParallelism(
            mode = SubscriptionUpdateMode.BackgroundEco,
            foregroundParallelism = 5,
            backgroundParallelism = 0,
        )
        assertEquals(1, p)
    }

    @Test
    fun `foreground overdue jail is not due without unjail`() {
        val group = autoUpdateGroup(name = "jailed", delayMinutes = 15, lastUpdated = 0).copy(id = 77L)
        val states = mapOf(
            77L to SubscriptionUpdateState(
                groupId = 77L,
                state = SubUpdateState.JAIL,
                failCountConsecutive = 3,
                nextAttemptAtMs = 0L,
            ),
        )
        val due = SubscriptionAutoUpdateRunner.dueSubscriptions(
            subscriptions = listOf(group),
            nowSeconds = 20 * 60L,
            connected = true,
            mode = SubscriptionUpdateMode.ForegroundInteractive,
            updateStates = states,
        )
        assertTrue(due.isEmpty())
    }

    @Test
    fun `overdue jail with future nextAttempt is not due`() {
        val group = autoUpdateGroup(name = "jailed", delayMinutes = 15, lastUpdated = 0).copy(id = 99L)
        val states = mapOf(
            99L to SubscriptionUpdateState(
                groupId = 99L,
                state = SubUpdateState.JAIL,
                failCountConsecutive = 2,
                nextAttemptAtMs = 9_999_999_999L,
            ),
        )
        val due = SubscriptionAutoUpdateRunner.dueSubscriptions(
            subscriptions = listOf(group),
            nowSeconds = 20 * 60L,
            connected = true,
            updateStates = states,
        )
        assertTrue(due.isEmpty())
    }

    @Test
    fun `connect refresh prefers wl pool feeds first`() {
        val nowSeconds = 20 * 60L
        val open = autoUpdateGroup(name = "open", delayMinutes = 15, lastUpdated = 0).copy(id = 10L).apply {
            subscription!!.connectPoolRole = ConnectPoolRole.OPEN
        }
        val wl = autoUpdateGroup(name = "wl", delayMinutes = 15, lastUpdated = 0).copy(id = 11L).apply {
            subscription!!.connectPoolRole = ConnectPoolRole.WL
        }
        val due = SubscriptionAutoUpdateRunner.dueSubscriptions(
            subscriptions = listOf(open, wl),
            nowSeconds = nowSeconds,
            connected = true,
            connectRefresh = true,
            mode = SubscriptionUpdateMode.ForegroundInteractive,
        )
        assertEquals(listOf("wl", "open"), due.map { it.name })
    }

    @Test
    fun `connect refresh skips jail and caps suspect`() {
        val nowSeconds = 20 * 60L
        val ok = autoUpdateGroup(name = "ok", delayMinutes = 15, lastUpdated = 0).copy(id = 1L)
        val suspect1 = autoUpdateGroup(name = "suspect-a", delayMinutes = 15, lastUpdated = 0).copy(id = 2L)
        val suspect2 = autoUpdateGroup(name = "suspect-b", delayMinutes = 15, lastUpdated = 0).copy(id = 3L)
        val jailed = autoUpdateGroup(name = "jail", delayMinutes = 15, lastUpdated = 0).copy(id = 4L)
        val states = mapOf(
            2L to SubscriptionUpdateState(groupId = 2L, state = SubUpdateState.SUSPECT, nextAttemptAtMs = 0L),
            3L to SubscriptionUpdateState(groupId = 3L, state = SubUpdateState.SUSPECT, nextAttemptAtMs = 0L),
            4L to SubscriptionUpdateState(groupId = 4L, state = SubUpdateState.JAIL, nextAttemptAtMs = 0L),
        )
        val due = SubscriptionAutoUpdateRunner.dueSubscriptions(
            subscriptions = listOf(jailed, suspect2, suspect1, ok),
            nowSeconds = nowSeconds,
            connected = true,
            connectRefresh = true,
            mode = SubscriptionUpdateMode.ForegroundInteractive,
            updateStates = states,
        )
        assertEquals(listOf("ok", "suspect-a"), due.map { it.name })
    }

    @Test
    fun `worker success when failure ends in jail`() {
        val states = mapOf(
            5L to SubscriptionUpdateState(groupId = 5L, state = SubUpdateState.JAIL),
        )
        assertTrue(
            SubscriptionUpdateEligibility.countsAsSuccessForWorker(
                attempted = true,
                updateSucceeded = false,
                groupId = 5L,
                statesAfter = states,
            ),
        )
    }

    @Test
    fun `planner uses nextAttempt delay when subscription interval not due`() {
        val nowSeconds = 10_000L
        val group = autoUpdateGroup(name = "suspect-wait", delayMinutes = 60, lastUpdated = nowSeconds.toInt()).copy(id = 8L)
        val states = mapOf(
            8L to SubscriptionUpdateState(
                groupId = 8L,
                state = SubUpdateState.SUSPECT,
                nextAttemptAtMs = (nowSeconds + 30) * 1000L,
            ),
        )
        val plan = SubscriptionAutoUpdatePlanner.plan(
            subscriptions = listOf(group),
            nowSeconds = nowSeconds,
            updateStates = states,
        )
        assertEquals(30L, plan?.initialDelaySeconds)
    }

    @Test
    fun `parallelism clamp enforces safe upper bounds`() {
        val fg = SubscriptionAutoUpdateRunner.previewParallelism(
            mode = SubscriptionUpdateMode.ForegroundInteractive,
            foregroundParallelism = 100,
            backgroundParallelism = 1,
        )
        val bg = SubscriptionAutoUpdateRunner.previewParallelism(
            mode = SubscriptionUpdateMode.BackgroundEco,
            foregroundParallelism = 3,
            backgroundParallelism = 99,
        )
        assertTrue(fg <= 6)
        assertTrue(bg <= 2)
    }

    private fun autoUpdateGroup(
        name: String,
        delayMinutes: Int,
        lastUpdated: Int,
        updateWhenConnectedOnly: Boolean = false,
    ): ProxyGroup {
        return ProxyGroup(
            name = name,
            subscription = SubscriptionBean().apply {
                autoUpdate = true
                autoUpdateDelay = delayMinutes
                this.lastUpdated = lastUpdated
                this.updateWhenConnectedOnly = updateWhenConnectedOnly
            },
        )
    }
}
