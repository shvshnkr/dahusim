package fr.husi.scenario.journey

import fr.husi.database.DataStore
import fr.husi.database.ProbeState
import fr.husi.database.ProxyProbeState
import fr.husi.database.SagerDatabase
import fr.husi.database.UserPoolPolicy
import fr.husi.database.UserSubscriptionTag
import fr.husi.simplemode.NotificationSwitchAction
import fr.husi.simplemode.WarmReserveSwitchPolicy
import fr.husi.simplemode.WarmSwitchDecision
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The notification «Сменить» promise (variant A, approved 2026-08-24): one tap = headless switch
 * to the best warm reserve without opening the picker UI (only a toast), and the service NEVER
 * stops because of the tap. The Android-side wiring (notification action → broadcast →
 * BaseService) is androidMain; this journey locks the decision contract in commonMain:
 * - the full picker opens only when switchUseFullProfilePicker is set;
 * - the decision is made on live probes + quality scoring (same as WARM_COMPARE, no UI):
 *   a live faster reserve wins, the connected server wins ties, and reserves that failed the
 *   live probe are excluded;
 * - non-switch outcomes keep the current connection — a dead reserve can never be picked,
 *   so the tap cannot land the app in Stopped (field 2026-08-24: persisted-state pick switched
 *   to a dead reserve and the service stopped).
 */
class NotificationSwitchInstantWarmJourneyTest : FeatureJourneyTest() {

    override suspend fun postStartKoin() {
        super.postStartKoin()
        DataStore.configurationStore.reset()
    }

    @Test
    fun notificationActionRoutesToPickerOnlyWhenConfigured() {
        assertEquals(
            NotificationSwitchAction.INSTANT_WARM,
            WarmReserveSwitchPolicy.resolveNotificationAction(
                useFullProfilePicker = false,
                warmAvailable = true,
            ),
        )
        assertEquals(
            NotificationSwitchAction.OPEN_FULL_PICKER,
            WarmReserveSwitchPolicy.resolveNotificationAction(
                useFullProfilePicker = true,
                warmAvailable = true,
            ),
        )
    }

    @Test
    fun liveVerifiedReserveWinsOverConnectedServer() = runBlocking {
        val queue = filteredQueue()
        val now = System.currentTimeMillis()
        val probeStates = mapOf(
            1L to ProxyProbeState(profileId = 1L, state = ProbeState.ALIVE, lastUrlMs = 200, lastOkAt = now),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE, lastUrlMs = 80, lastOkAt = now),
        )
        val decision = WarmReserveSwitchPolicy.decideLiveManualSwitch(
            queue = queue,
            connectedId = 1L,
            liveUrlMs = mapOf(1L to 200, 2L to 80),
            probeStates = probeStates,
            target = 2,
        )
        assertEquals(WarmSwitchDecision.SwitchTo(2L), decision)
    }

    @Test
    fun connectedServerWinsTieAndKeepsConnection() = runBlocking {
        val queue = filteredQueue()
        val now = System.currentTimeMillis()
        val probeStates = mapOf(
            1L to ProxyProbeState(profileId = 1L, state = ProbeState.ALIVE, lastUrlMs = 100, lastOkAt = now),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE, lastUrlMs = 400, lastOkAt = now),
        )
        val decision = WarmReserveSwitchPolicy.decideLiveManualSwitch(
            queue = queue,
            connectedId = 1L,
            liveUrlMs = mapOf(1L to 100, 2L to 400),
            probeStates = probeStates,
            target = 2,
        )
        assertEquals(WarmSwitchDecision.AlreadyOnBest, decision)
    }

    @Test
    fun deadReservesNeverCauseSwitchOrStop() = runBlocking {
        val queue = filteredQueue()
        val now = System.currentTimeMillis()
        val probeStates = mapOf(
            1L to ProxyProbeState(profileId = 1L, state = ProbeState.ALIVE, lastUrlMs = 200, lastOkAt = now),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE, lastUrlMs = 80, lastOkAt = now),
        )
        // The reserve failed the live probe despite a fresh persisted state: it must be excluded,
        // so no switch happens and the connection is kept (no-stop promise, field 2026-08-24).
        val decision = WarmReserveSwitchPolicy.decideLiveManualSwitch(
            queue = queue,
            connectedId = 1L,
            liveUrlMs = mapOf(1L to 200, 2L to null),
            probeStates = probeStates,
            target = 2,
        )
        assertEquals(WarmSwitchDecision.NoReserves, decision)
    }

    private suspend fun filteredQueue(): List<Long> {
        val rawQueue = WarmReserveSwitchPolicy.parseQueue("1,2,3")
        val groups = SagerDatabase.groupDao.allGroups().first()
        val userTag = UserSubscriptionTag.resolve(
            SagerDatabase.proxyDao.getAll(),
            groups,
        )
        val queue = UserPoolPolicy.filterProxyIds(
            UserPoolPolicy.effectiveMode(),
            rawQueue,
            userTag.userProxyIds,
        )
        assertEquals(listOf(1L, 2L, 3L), queue)
        return queue
    }
}
