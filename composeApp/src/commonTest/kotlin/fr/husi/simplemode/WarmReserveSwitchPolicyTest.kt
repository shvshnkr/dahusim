package fr.husi.simplemode

import fr.husi.database.DataStore
import fr.husi.database.ProbeState
import fr.husi.database.ProxyProbeState
import fr.husi.test.HusiKoinTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarmReserveSwitchPolicyTest : HusiKoinTest() {

    override suspend fun postStartKoin() {
        DataStore.configurationStore.reset()
    }

    @Test
    fun warmSwitchAvailableOnlyWhenSimpleModePersistenceConnectedAndQueue() {
        val queue = listOf(1L, 2L)
        assertTrue(
            WarmReserveSwitchPolicy.isWarmSwitchAvailable(
                simpleMode = true,
                persistenceEnabled = true,
                vpnConnected = true,
                queue = queue,
            ),
        )
        assertFalse(
            WarmReserveSwitchPolicy.isWarmSwitchAvailable(
                simpleMode = false,
                persistenceEnabled = true,
                vpnConnected = true,
                queue = queue,
            ),
        )
        assertFalse(
            WarmReserveSwitchPolicy.isWarmSwitchAvailable(
                simpleMode = true,
                persistenceEnabled = false,
                vpnConnected = true,
                queue = queue,
            ),
        )
        assertFalse(
            WarmReserveSwitchPolicy.isWarmSwitchAvailable(
                simpleMode = true,
                persistenceEnabled = true,
                vpnConnected = false,
                queue = queue,
            ),
        )
        assertFalse(
            WarmReserveSwitchPolicy.isWarmSwitchAvailable(
                simpleMode = true,
                persistenceEnabled = true,
                vpnConnected = true,
                queue = emptyList(),
            ),
        )
    }

    @Test
    fun resolveNotificationActionInstantWarmByDefault() {
        assertEquals(
            NotificationSwitchAction.INSTANT_WARM,
            WarmReserveSwitchPolicy.resolveNotificationAction(
                useFullProfilePicker = false,
                warmAvailable = true,
            ),
        )
    }

    @Test
    fun resolveNotificationActionOpensPickerWhenPreferenceOrUnavailable() {
        assertEquals(
            NotificationSwitchAction.OPEN_FULL_PICKER,
            WarmReserveSwitchPolicy.resolveNotificationAction(
                useFullProfilePicker = true,
                warmAvailable = true,
            ),
        )
        assertEquals(
            NotificationSwitchAction.OPEN_FULL_PICKER,
            WarmReserveSwitchPolicy.resolveNotificationAction(
                useFullProfilePicker = false,
                warmAvailable = false,
            ),
        )
    }

    @Test
    fun loadCandidatesFollowsReserveOrderAndFreshFlag() {
        val now = System.currentTimeMillis()
        val queue = listOf(1L, 2L, 3L, 4L)
        val states = mapOf(
            2L to ProxyProbeState(
                profileId = 2L,
                state = ProbeState.ALIVE,
                lastUrlMs = 100,
                lastOkAt = now,
            ),
            3L to ProxyProbeState(profileId = 3L, state = ProbeState.CANDIDATE),
        )
        val candidates = WarmReserveSwitchPolicy.loadCandidates(
            queue = queue,
            connectedId = 1L,
            probeStates = states,
            target = 2,
        )
        assertEquals(listOf(2L, 3L), candidates.map { it.profileId })
        assertTrue(candidates[0].freshUrlVerified)
        assertFalse(candidates[1].freshUrlVerified)
    }

    @Test
    fun decideManualSwitchUsesQualityPolicy() {
        val now = System.currentTimeMillis()
        val queue = listOf(1L, 2L, 3L)
        val states = mapOf(
            1L to ProxyProbeState(profileId = 1L, state = ProbeState.ALIVE, lastUrlMs = 200, lastOkAt = now),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE, lastUrlMs = 80, lastOkAt = now),
        )
        val decision = WarmReserveSwitchPolicy.decideManualSwitch(
            queue = queue,
            connectedId = 1L,
            liveUrlMs = mapOf(1L to 200, 2L to 80),
            probeStates = states,
            target = 2,
        )
        assertEquals(WarmSwitchDecision.SwitchTo(2L), decision)
    }

    @Test
    fun decideLiveManualSwitchSwitchesToLiveReserve() {
        val now = System.currentTimeMillis()
        val queue = listOf(1L, 2L, 3L)
        val states = mapOf(
            1L to ProxyProbeState(profileId = 1L, state = ProbeState.ALIVE, lastUrlMs = 200, lastOkAt = now),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE, lastUrlMs = 80, lastOkAt = now),
        )
        val decision = WarmReserveSwitchPolicy.decideLiveManualSwitch(
            queue = queue,
            connectedId = 1L,
            liveUrlMs = mapOf(1L to 200, 2L to 80),
            probeStates = states,
            target = 2,
        )
        assertEquals(WarmSwitchDecision.SwitchTo(2L), decision)
    }

    @Test
    fun decideLiveManualSwitchAlreadyOnBestWhenConnectedFaster() {
        val now = System.currentTimeMillis()
        val queue = listOf(1L, 2L, 3L)
        val states = mapOf(
            1L to ProxyProbeState(profileId = 1L, state = ProbeState.ALIVE, lastUrlMs = 100, lastOkAt = now),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE, lastUrlMs = 400, lastOkAt = now),
        )
        val decision = WarmReserveSwitchPolicy.decideLiveManualSwitch(
            queue = queue,
            connectedId = 1L,
            liveUrlMs = mapOf(1L to 100, 2L to 400),
            probeStates = states,
            target = 2,
        )
        assertEquals(WarmSwitchDecision.AlreadyOnBest, decision)
    }

    @Test
    fun decideLiveManualSwitchExcludesReservesThatFailedLiveProbe() {
        val now = System.currentTimeMillis()
        val queue = listOf(1L, 2L, 3L)
        val states = mapOf(
            1L to ProxyProbeState(profileId = 1L, state = ProbeState.ALIVE, lastUrlMs = 200, lastOkAt = now),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE, lastUrlMs = 80, lastOkAt = now),
        )
        // Even with a fresh persisted state, a reserve that failed the live probe must not win:
        // otherwise the switch lands on a dead server and the service stops (field 2026-08-24).
        val decision = WarmReserveSwitchPolicy.decideLiveManualSwitch(
            queue = queue,
            connectedId = 1L,
            liveUrlMs = mapOf(1L to 200, 2L to null),
            probeStates = states,
            target = 2,
        )
        assertEquals(WarmSwitchDecision.NoReserves, decision)
    }

    @Test
    fun decideManualSwitchAlreadyOnBestWhenConnectedFaster() {
        val now = System.currentTimeMillis()
        val queue = listOf(1L, 2L, 3L)
        val states = mapOf(
            1L to ProxyProbeState(profileId = 1L, state = ProbeState.ALIVE, lastUrlMs = 100, lastOkAt = now),
            2L to ProxyProbeState(profileId = 2L, state = ProbeState.ALIVE, lastUrlMs = 400, lastOkAt = now),
        )
        val decision = WarmReserveSwitchPolicy.decideManualSwitch(
            queue = queue,
            connectedId = 1L,
            liveUrlMs = mapOf(1L to 100, 2L to 400),
            probeStates = states,
            target = 2,
        )
        assertEquals(WarmSwitchDecision.AlreadyOnBest, decision)
    }
}
