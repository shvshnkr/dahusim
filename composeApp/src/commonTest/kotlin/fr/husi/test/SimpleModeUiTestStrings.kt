package fr.husi.test

import fr.husi.resources.Res
import fr.husi.resources.simple_mode_activity_verifying_last
import fr.husi.resources.simple_mode_attempt_n_of_m
import fr.husi.resources.simple_mode_connect
import fr.husi.resources.simple_mode_connected
import fr.husi.resources.simple_mode_connecting
import fr.husi.resources.simple_mode_disconnect
import fr.husi.resources.simple_mode_failed
import fr.husi.resources.simple_mode_no_internet_banner_title
import fr.husi.resources.simple_mode_preparing
import fr.husi.resources.simple_mode_recovering_switching
import fr.husi.resources.simple_mode_stopped
import fr.husi.resources.simple_mode_trail_network
import fr.husi.resources.simple_mode_trail_server
import fr.husi.resources.simple_mode_trail_subs
import fr.husi.resources.simple_mode_trail_vpn
import fr.husi.resources.simple_mode_wl_banner_title
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Bridge for desktop Compose UI tests: commonMain's generated `Res.string.*` accessors are
 * `internal`, and KMP internal visibility does not cross the commonMain → desktopTest compilation
 * boundary. This object (living in commonTest, which is a friend of commonMain) re-exposes the
 * few labels the simple-mode UI tests assert on, localized exactly like the composition's
 * `stringResource(...)` (same default environment).
 */
object SimpleModeUiTestStrings {

    fun simpleModeConnected(): String = get(Res.string.simple_mode_connected)

    fun simpleModePreparing(): String = get(Res.string.simple_mode_preparing)

    fun simpleModeConnecting(): String = get(Res.string.simple_mode_connecting)

    fun simpleModeStopped(): String = get(Res.string.simple_mode_stopped)

    fun simpleModeConnect(): String = get(Res.string.simple_mode_connect)

    fun simpleModeDisconnect(): String = get(Res.string.simple_mode_disconnect)

    fun simpleModeWlBannerTitle(): String = get(Res.string.simple_mode_wl_banner_title)

    fun simpleModeNoInternetBannerTitle(): String = get(Res.string.simple_mode_no_internet_banner_title)

    fun simpleModeActivityVerifyingLast(): String = get(Res.string.simple_mode_activity_verifying_last)

    fun simpleModeFailed(): String = get(Res.string.simple_mode_failed)

    fun simpleModeRecoveringSwitching(): String = get(Res.string.simple_mode_recovering_switching)

    fun simpleModeAttemptNOfM(n: Int, m: Int): String = get(Res.string.simple_mode_attempt_n_of_m, n, m)

    fun simpleModeTrailNetwork(): String = get(Res.string.simple_mode_trail_network)

    fun simpleModeTrailSubs(): String = get(Res.string.simple_mode_trail_subs)

    fun simpleModeTrailServer(): String = get(Res.string.simple_mode_trail_server)

    fun simpleModeTrailVpn(): String = get(Res.string.simple_mode_trail_vpn)

    private fun get(resource: StringResource, vararg args: Any): String =
        runBlocking { getString(resource, *args) }
}
