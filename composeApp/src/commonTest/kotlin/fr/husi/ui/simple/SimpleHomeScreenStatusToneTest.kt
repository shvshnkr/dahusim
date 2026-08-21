package fr.husi.ui.simple

import fr.husi.bg.ServiceState
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
}
