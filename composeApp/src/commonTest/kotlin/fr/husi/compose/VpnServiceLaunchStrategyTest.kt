package fr.husi.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VpnServiceLaunchStrategyTest {

    @Test
    fun canLaunchWhenLauncherRegisteredWithoutActivity() {
        assertTrue(VpnServiceLaunchStrategy.canLaunch(isLauncherRegistered = true, hasActivity = false))
    }

    @Test
    fun canLaunchWhenActivityPresentWithoutLauncher() {
        assertTrue(VpnServiceLaunchStrategy.canLaunch(isLauncherRegistered = false, hasActivity = true))
    }

    @Test
    fun cannotLaunchWithoutLauncherOrActivity() {
        assertFalse(VpnServiceLaunchStrategy.canLaunch(isLauncherRegistered = false, hasActivity = false))
    }

    @Test
    fun prefersComposeLauncherWhenRegistered() {
        assertEquals(
            VpnServiceLaunchStrategy.PrimaryPath.ComposeLauncher,
            VpnServiceLaunchStrategy.choosePrimaryPath(isLauncherRegistered = true),
        )
    }

    @Test
    fun fallsBackToActivityWhenLauncherUnregistered() {
        assertEquals(
            VpnServiceLaunchStrategy.PrimaryPath.ActivityFallback,
            VpnServiceLaunchStrategy.choosePrimaryPath(isLauncherRegistered = false),
        )
    }
}
