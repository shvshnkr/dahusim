package fr.husi.ui.simple

import androidx.compose.runtime.Composable
import fr.husi.resources.Res
import fr.husi.resources.simple_mode_activity_checking_network
import fr.husi.resources.simple_mode_activity_connection_unstable
import fr.husi.resources.simple_mode_activity_connecting_server
import fr.husi.resources.simple_mode_activity_connection_error
import fr.husi.resources.simple_mode_activity_finding_server
import fr.husi.resources.simple_mode_activity_network_changed
import fr.husi.resources.simple_mode_activity_permission_foreground
import fr.husi.resources.simple_mode_activity_permission_vpn
import fr.husi.resources.simple_mode_activity_ranking
import fr.husi.resources.simple_mode_activity_refreshing_subs
import fr.husi.resources.simple_mode_activity_server_degraded
import fr.husi.resources.simple_mode_activity_server_unreachable
import fr.husi.resources.simple_mode_activity_server_unstable
import fr.husi.resources.simple_mode_activity_starting_vpn
import fr.husi.resources.simple_mode_activity_testing_tcp
import fr.husi.resources.simple_mode_activity_testing_url
import fr.husi.resources.simple_mode_activity_trying_next
import fr.husi.resources.simple_mode_activity_verifying_internet
import fr.husi.resources.simple_mode_activity_verifying_last
import fr.husi.simplemode.ACTIVITY_CONNECTION_UNSTABLE_RECHECKING
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun displaySimpleModeActivity(raw: String): String {
    if (raw.isBlank()) return raw
    return when {
        raw.startsWith("Refreshing subscriptions") ->
            stringResource(Res.string.simple_mode_activity_refreshing_subs)
        raw.startsWith("Finding best server") ->
            stringResource(Res.string.simple_mode_activity_finding_server)
        raw.startsWith("Checking network") ->
            stringResource(Res.string.simple_mode_activity_checking_network)
        raw.startsWith("Verifying last server") ->
            stringResource(Res.string.simple_mode_activity_verifying_last)
        raw.startsWith("Verifying internet access") ->
            stringResource(Res.string.simple_mode_activity_verifying_internet)
        raw.startsWith("Connecting to server") ->
            stringResource(Res.string.simple_mode_activity_connecting_server)
        raw.startsWith("Starting VPN") ->
            stringResource(Res.string.simple_mode_activity_starting_vpn)
        raw.startsWith("Allow VPN when prompted") ->
            stringResource(Res.string.simple_mode_activity_permission_vpn)
        raw.startsWith("Return to app to allow VPN") ->
            stringResource(Res.string.simple_mode_activity_permission_foreground)
        raw.startsWith("Network changed, reconnecting") ->
            stringResource(Res.string.simple_mode_activity_network_changed)
        raw == ACTIVITY_CONNECTION_UNSTABLE_RECHECKING ->
            stringResource(Res.string.simple_mode_activity_connection_unstable)
        raw.startsWith("Server unstable, switching") ->
            stringResource(Res.string.simple_mode_activity_server_unstable)
        raw.startsWith("Server degraded, switching") ->
            stringResource(Res.string.simple_mode_activity_server_degraded)
        raw.startsWith("Server unreachable, trying next") ->
            stringResource(Res.string.simple_mode_activity_server_unreachable)
        raw.startsWith("Connection error, trying next") ->
            stringResource(Res.string.simple_mode_activity_connection_error)
        raw.startsWith("Trying next server") ->
            stringResource(Res.string.simple_mode_activity_trying_next)
        raw.startsWith("Ranking ") -> {
            val count = raw.removePrefix("Ranking ").substringBefore(' ').toIntOrNull()
            if (count != null) {
                stringResource(Res.string.simple_mode_activity_ranking, count)
            } else {
                stringResource(Res.string.simple_mode_activity_finding_server)
            }
        }
        raw.startsWith("Testing TCP ") -> {
            val parts = raw.removePrefix("Testing TCP ").split('/')
            val done = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val total = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (done != null && total != null) {
                stringResource(Res.string.simple_mode_activity_testing_tcp, done, total)
            } else {
                stringResource(Res.string.simple_mode_activity_finding_server)
            }
        }
        raw.startsWith("Testing URL ") -> {
            val parts = raw.removePrefix("Testing URL ").split('/')
            val done = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val total = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (done != null && total != null) {
                stringResource(Res.string.simple_mode_activity_testing_url, done, total)
            } else {
                stringResource(Res.string.simple_mode_activity_finding_server)
            }
        }
        else -> raw
    }
}
