package fr.husi.ui.simple

import fr.husi.bg.ServiceState
import fr.husi.resources.Res
import fr.husi.resources.simple_mode_recovering_checking
import fr.husi.resources.simple_mode_recovering_reconnect
import fr.husi.resources.simple_mode_recovering_switching
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Render contract of the simple screen status ring ([statusTone]) — the same mapping
 * [SimpleHomeScreen] uses to pick the status label, ring color and power-button action.
 *
 * Regression anchor (field BS session 2026-08-21): a stale prepare activity
 * ("Verifying last server…") left in [fr.husi.database.DataStore.simpleModeActivity] while the
 * tunnel was already Connected made the screen stick in "Preparing…". Connected + prepare text
 * must stay PREPARING (the live adaptation is still running), and only an activity clear
 * ([fr.husi.simplemode.SimpleModeNetworkAdaptation.clearActivityAfterPrepareTimeout]) returns the
 * tone to CONNECTED.
 *
 * v2 states (approved mockup 2026-08-24): FAILED is a persistent terminal tone for the network
 * gate / all-probes-dead instead of a silent reset to Stopped; RECOVERING covers problem
 * recovery activities (switching/unstable/unreachable/next/network-changed) while clean
 * bring-up texts stay CONNECTING.
 */
class SimpleHomeScreenStatusToneTest {

    @Test
    fun connectedWithBlankActivityIsConnected() {
        assertEquals(
            StatusTone.CONNECTED,
            statusTone(ServiceState.Connected, permissionPending = false, activityText = ""),
        )
    }

    @Test
    fun connectedWithStalePrepareActivityStaysPreparing() {
        assertEquals(
            StatusTone.PREPARING,
            statusTone(ServiceState.Connected, permissionPending = false, activityText = "Verifying last server…"),
        )
    }

    @Test
    fun connectedWithPrepareProgressIsPreparing() {
        assertEquals(
            StatusTone.PREPARING,
            statusTone(ServiceState.Connected, permissionPending = false, activityText = "Finding best server…"),
        )
    }

    @Test
    fun connectedWithVpnProgressIsConnecting() {
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Connected, permissionPending = false, activityText = "Connecting to server…"),
        )
    }

    @Test
    fun stoppedWithPrepareActivityIsPreparing() {
        assertEquals(
            StatusTone.PREPARING,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Verifying last server…"),
        )
    }

    @Test
    fun stoppedWithBlankActivityIsStopped() {
        assertEquals(
            StatusTone.STOPPED,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = ""),
        )
    }

    @Test
    fun idleWithBlankActivityIsStopped() {
        assertEquals(
            StatusTone.STOPPED,
            statusTone(ServiceState.Idle, permissionPending = false, activityText = ""),
        )
    }

    @Test
    fun connectingStateIsConnecting() {
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Connecting, permissionPending = false, activityText = ""),
        )
    }

    @Test
    fun connectingWithPrepareActivityIsPreparing() {
        assertEquals(
            StatusTone.PREPARING,
            statusTone(ServiceState.Connecting, permissionPending = false, activityText = "Ranking 12 servers…"),
        )
    }

    @Test
    fun stoppedWithVpnProgressIsConnecting() {
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Starting VPN…"),
        )
    }

    @Test
    fun permissionPendingBeatsEverything() {
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Connected, permissionPending = true, activityText = ""),
        )
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Stopped, permissionPending = true, activityText = ""),
        )
    }

    // --- FAILED (v2): persistent terminal tone, not a silent reset to Stopped ---

    @Test
    fun stoppedWithNoInternetIsFailed() {
        assertEquals(
            StatusTone.FAILED,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "", noInternet = true),
        )
    }

    @Test
    fun idleWithAllServersDeadIsFailed() {
        assertEquals(
            StatusTone.FAILED,
            statusTone(ServiceState.Idle, permissionPending = false, activityText = "", allServersDead = true),
        )
    }

    @Test
    fun connectedWithNoInternetStaysConnected() {
        // A live session is not a failure even if a stale banner flag is still set.
        assertEquals(
            StatusTone.CONNECTED,
            statusTone(ServiceState.Connected, permissionPending = false, activityText = "", noInternet = true),
        )
    }

    @Test
    fun connectingStateWithAllServersDeadStaysConnecting() {
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Connecting, permissionPending = false, activityText = "", allServersDead = true),
        )
    }

    @Test
    fun stoppedWithNoInternetAndStalePrepareActivityIsStillFailed() {
        // A stale prepare text must NOT mask the terminal failure — otherwise the screen
        // would sit in "Preparing…" forever with the no-internet banner (the exact
        // "silent reset" problem mockup v2 fixes). A live prepare wins via isInFlight.
        assertEquals(
            StatusTone.FAILED,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Finding best server…", noInternet = true),
        )
    }

    @Test
    fun stoppedWithNoInternetAndPermissionPendingIsConnecting() {
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Stopped, permissionPending = true, activityText = "", noInternet = true),
        )
    }

    // --- RECOVERING (v2): problem recovery is a distinct tone ---

    @Test
    fun serverUnreachableIsRecovering() {
        assertEquals(
            StatusTone.RECOVERING,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Server unreachable, trying next..."),
        )
    }

    @Test
    fun serverUnstableIsRecovering() {
        assertEquals(
            StatusTone.RECOVERING,
            statusTone(ServiceState.Connected, permissionPending = false, activityText = "Server unstable, switching..."),
        )
    }

    @Test
    fun serverDegradedIsRecovering() {
        assertEquals(
            StatusTone.RECOVERING,
            statusTone(ServiceState.Connected, permissionPending = false, activityText = "Server degraded, switching…"),
        )
    }

    @Test
    fun connectionUnstableIsRecovering() {
        assertEquals(
            StatusTone.RECOVERING,
            statusTone(ServiceState.Connected, permissionPending = false, activityText = "Connection unstable, rechecking…"),
        )
    }

    @Test
    fun networkChangedIsRecovering() {
        assertEquals(
            StatusTone.RECOVERING,
            statusTone(ServiceState.Connected, permissionPending = false, activityText = "Network changed, reconnecting…"),
        )
    }

    @Test
    fun tryingNextServerIsRecovering() {
        assertEquals(
            StatusTone.RECOVERING,
            statusTone(ServiceState.Connecting, permissionPending = false, activityText = "Trying next server 2/3"),
        )
    }

    @Test
    fun connectionErrorIsRecovering() {
        assertEquals(
            StatusTone.RECOVERING,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Connection error, trying next..."),
        )
    }

    // Clean bring-up texts are NOT recovery — they stay CONNECTING.

    @Test
    fun connectingToServerStaysConnecting() {
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Connecting to server…"),
        )
    }

    @Test
    fun startingVpnStaysConnecting() {
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Stopped, permissionPending = false, activityText = "Starting VPN…"),
        )
    }

    @Test
    fun verifyingInternetStaysConnecting() {
        assertEquals(
            StatusTone.CONNECTING,
            statusTone(ServiceState.Connected, permissionPending = false, activityText = "Verifying internet access..."),
        )
    }

    // --- RECOVERING headline (v2) ---

    @Test
    fun recoveringHeadlineForNetworkChangeIsReconnect() {
        assertEquals(
            Res.string.simple_mode_recovering_reconnect,
            recoveringHeadlineRes("Network changed, reconnecting…"),
        )
    }

    @Test
    fun recoveringHeadlineForUnstableSessionIsChecking() {
        assertEquals(
            Res.string.simple_mode_recovering_checking,
            recoveringHeadlineRes("Connection unstable, rechecking…"),
        )
    }

    @Test
    fun recoveringHeadlineForServerSwitchIsSwitching() {
        assertEquals(
            Res.string.simple_mode_recovering_switching,
            recoveringHeadlineRes("Server degraded, switching…"),
        )
        assertEquals(
            Res.string.simple_mode_recovering_switching,
            recoveringHeadlineRes("Server unreachable, trying next..."),
        )
        assertEquals(
            Res.string.simple_mode_recovering_switching,
            recoveringHeadlineRes("Trying next server 2/3"),
        )
    }

    // --- step trail (v2): always shown, done/current/fail/pending ---

    private fun trailStates(
        tone: StatusTone,
        activity: String = "",
        noInternet: Boolean = false,
        allServersDead: Boolean = false,
    ): List<SimpleTrailStepState> =
        simpleTrailSteps(tone, activity, noInternet, allServersDead).map { it.second }

    @Test
    fun connectedTrailIsAllDone() {
        assertEquals(
            listOf(
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.DONE,
            ),
            trailStates(StatusTone.CONNECTED),
        )
    }

    @Test
    fun idleTrailIsAllPending() {
        assertEquals(
            listOf(
                SimpleTrailStepState.PENDING,
                SimpleTrailStepState.PENDING,
                SimpleTrailStepState.PENDING,
                SimpleTrailStepState.PENDING,
            ),
            trailStates(StatusTone.STOPPED),
        )
    }

    @Test
    fun checkingNetworkMarksNetworkCurrent() {
        assertEquals(
            listOf(
                SimpleTrailStepState.CURRENT,
                SimpleTrailStepState.PENDING,
                SimpleTrailStepState.PENDING,
                SimpleTrailStepState.PENDING,
            ),
            trailStates(StatusTone.PREPARING, activity = "Checking network…"),
        )
    }

    @Test
    fun findingServerMarksServerCurrentWithEarlierDone() {
        assertEquals(
            listOf(
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.CURRENT,
                SimpleTrailStepState.PENDING,
            ),
            trailStates(StatusTone.PREPARING, activity = "Finding best server…"),
        )
    }

    @Test
    fun connectingToServerMarksVpnCurrent() {
        assertEquals(
            listOf(
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.CURRENT,
            ),
            trailStates(StatusTone.CONNECTING, activity = "Connecting to server…"),
        )
    }

    @Test
    fun recoveringMarksVpnCurrent() {
        assertEquals(
            listOf(
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.CURRENT,
            ),
            trailStates(StatusTone.RECOVERING, activity = "Server unreachable, trying next..."),
        )
    }

    @Test
    fun noInternetFailsNetworkStage() {
        assertEquals(
            listOf(
                SimpleTrailStepState.FAIL,
                SimpleTrailStepState.PENDING,
                SimpleTrailStepState.PENDING,
                SimpleTrailStepState.PENDING,
            ),
            trailStates(StatusTone.FAILED, noInternet = true),
        )
    }

    @Test
    fun allServersDeadFailsServerStage() {
        assertEquals(
            listOf(
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.DONE,
                SimpleTrailStepState.FAIL,
                SimpleTrailStepState.PENDING,
            ),
            trailStates(StatusTone.FAILED, allServersDead = true),
        )
    }

    // --- fallback attempt pill (v2): N = queue position + 1, M = queue size ---

    @Test
    fun fallbackAttemptUsesIndexAndQueue() {
        assertEquals(1 to 3, fallbackAttempt(index = 0, queueRaw = "11,22,33"))
        assertEquals(2 to 3, fallbackAttempt(index = 1, queueRaw = "11,22,33"))
        assertEquals(3 to 3, fallbackAttempt(index = 2, queueRaw = "11,22,33"))
    }

    @Test
    fun fallbackAttemptClampsOutOfRangeIndex() {
        assertEquals(3 to 3, fallbackAttempt(index = 9, queueRaw = "11,22,33"))
        assertEquals(1 to 3, fallbackAttempt(index = -1, queueRaw = "11,22,33"))
    }

    @Test
    fun fallbackAttemptIsNullForEmptyQueue() {
        assertEquals(null, fallbackAttempt(index = 0, queueRaw = ""))
        assertEquals(null, fallbackAttempt(index = 0, queueRaw = "abc,xyz"))
    }

    @Test
    fun fallbackAttemptToleratesWhitespaceAndInvalidIds() {
        assertEquals(1 to 2, fallbackAttempt(index = 0, queueRaw = " 11 , junk ,22 "))
    }
}
