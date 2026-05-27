package fr.husi.compose

import fr.husi.bg.ServiceState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SagerFabPolicyTest {

    @Test
    fun blocksConnectWhilePermissionPending() {
        assertFalse(
            canStartFromFullFab(
                state = ServiceState.Stopped,
                permissionPending = true,
            ),
        )
    }

    @Test
    fun blocksConnectWhileStopping() {
        assertFalse(
            canStartFromFullFab(
                state = ServiceState.Stopping,
                permissionPending = false,
            ),
        )
    }

    @Test
    fun allowsConnectWhenStoppedAndNoPermissionRequest() {
        assertTrue(
            canStartFromFullFab(
                state = ServiceState.Stopped,
                permissionPending = false,
            ),
        )
    }
}
