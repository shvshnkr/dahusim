package fr.husi.ui.simple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.compose.rememberVpnServiceLauncher
import fr.husi.database.DataStore
import fr.husi.ktx.exitApplication
import fr.husi.repository.resolveRepository
import fr.husi.resources.Res
import fr.husi.resources.simple_mode_connect
import fr.husi.resources.simple_mode_connected
import fr.husi.resources.simple_mode_connecting
import fr.husi.resources.simple_mode_description
import fr.husi.resources.simple_mode_disconnect
import fr.husi.resources.simple_mode_full_ui
import fr.husi.resources.simple_mode_logs
import fr.husi.resources.simple_mode_no_internet_pause
import fr.husi.resources.simple_mode_no_profile
import fr.husi.resources.simple_mode_permission_pending
import fr.husi.resources.simple_mode_status
import fr.husi.resources.simple_mode_stopped
import fr.husi.resources.vpn_permission_denied
import fr.husi.ui.MainViewModel
import fr.husi.ui.SimpleModeAllServersDeadChoice
import fr.husi.ui.StringOrRes
import fr.husi.utils.canShareSimpleModeLogs
import fr.husi.simplemode.SimpleModeConnectCoordinator
import fr.husi.simplemode.cancelSimpleModeNetworkAdaptation
import fr.husi.simplemode.isSimpleModeProgressActivity
import fr.husi.utils.shareSimpleModeLogs
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color

@Composable
fun SimpleHomeScreen(
    mainViewModel: MainViewModel,
    onOpenFullMode: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var permissionPending by remember { mutableStateOf(false) }
    val status = BackendState.status.collectAsStateWithLifecycle().value
    val activityText = DataStore.configurationStore
        .stringFlow(Key.SIMPLE_MODE_ACTIVITY)
        .collectAsStateWithLifecycle("")
        .value
    val connector = rememberVpnServiceLauncher {
        permissionPending = false
        simpleModeLog("SimpleMode", "permission_denied")
        mainViewModel.showSnackbar(StringOrRes.Res(Res.string.vpn_permission_denied))
    }
    val connectHost = remember(mainViewModel, connector) {
        object : SimpleModeConnectCoordinator.ConnectHost {
            override fun setPermissionPending(pending: Boolean) {
                permissionPending = pending
            }

            override fun requestVpnConnect() {
                connector()
            }

            override fun onNoInternet() {
                mainViewModel.showSnackbar(StringOrRes.Res(Res.string.simple_mode_no_internet_pause))
            }

            override fun onNoProfile() {
                mainViewModel.showSnackbar(StringOrRes.Res(Res.string.simple_mode_no_profile))
            }

            override fun onNeedForegroundForPermission() {
                mainViewModel.showSnackbar(StringOrRes.Res(Res.string.simple_mode_permission_pending))
            }

            override suspend fun promptAllServersDead(): SimpleModeAllServersDeadChoice =
                mainViewModel.promptSimpleModeAllServersDead()
        }
    }
    LaunchedEffect(status.state) {
        simpleModeLog("SimpleMode", "state=${status.state.name}")
        when (status.state) {
            ServiceState.Connected,
            ServiceState.Stopped,
            ServiceState.Idle,
            -> permissionPending = false
            else -> Unit
        }
    }
    LaunchedEffect(status.state) {
        val progress = isSimpleModeProgressActivity(DataStore.simpleModeActivity)
        when {
            status.state == ServiceState.Connected && !progress && !SimpleModeConnectCoordinator.isInFlight() ->
                DataStore.simpleModeActivity = ""
            (status.state == ServiceState.Stopped || status.state == ServiceState.Idle) &&
                !SimpleModeConnectCoordinator.isInFlight() &&
                !progress -> DataStore.simpleModeActivity = ""
        }
    }
    LaunchedEffect(activityText) {
        if (activityText.isNotBlank()) {
            simpleModeLog("SimpleMode", "H11 activity=$activityText")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(Res.string.simple_mode_description),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        ) {
            val statusTone = statusTone(status.state, permissionPending, activityText)
            val statusLabel = when (statusTone) {
                StatusTone.CONNECTED -> stringResource(Res.string.simple_mode_connected)
                StatusTone.CONNECTING -> stringResource(Res.string.simple_mode_connecting)
                StatusTone.STOPPED -> stringResource(Res.string.simple_mode_stopped)
            }
            val detailText = when {
                permissionPending && !status.state.canStop -> stringResource(Res.string.simple_mode_permission_pending)
                activityText.isNotBlank() -> activityText
                else -> null
            }
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(Res.string.simple_mode_status, statusLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = statusLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    color = statusTone.color(),
                    textAlign = TextAlign.Center,
                )
                detailText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Button(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .fillMaxWidth()
                .height(58.dp),
            enabled = !permissionPending,
            onClick = {
                if (status.state.canStop) {
                    simpleModeLog("SimpleMode", "disconnect_clicked")
                    SimpleModeConnectCoordinator.cancel("disconnect")
                    cancelSimpleModeNetworkAdaptation()
                    resolveRepository().stopService()
                    return@Button
                }
                if (permissionPending) {
                    simpleModeLog("SimpleMode", "connect_ignored_permission_pending")
                    return@Button
                }
                simpleModeLog(
                    "SimpleMode",
                    "connect_clicked state=${status.state.name} permissionPending=$permissionPending " +
                        "activity=${activityText.ifBlank { "-" }}",
                )
                if (SimpleModeConnectCoordinator.isInFlight()) {
                    simpleModeLog("SimpleMode", "connect_cancel_previous_inflight")
                }
                SimpleModeConnectCoordinator.start(connectHost)
            },
        ) {
            if (permissionPending && !status.state.canStop) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(Res.string.simple_mode_connecting),
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text(
                    text = if (status.state.canStop) {
                        stringResource(Res.string.simple_mode_disconnect)
                    } else {
                        stringResource(Res.string.simple_mode_connect)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        if (permissionPending && !status.state.canStop) {
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = stringResource(Res.string.simple_mode_permission_pending),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (canShareSimpleModeLogs()) {
                TextButton(
                    modifier = Modifier.padding(top = 8.dp),
                    onClick = {
                        scope.launch {
                            runCatching {
                                simpleModeLog("SimpleMode", "share_logs_clicked")
                                shareSimpleModeLogs()
                            }.onFailure {
                                simpleModeLog("SimpleMode", "share_logs_failed=${it.message}")
                                mainViewModel.showSnackbar(StringOrRes.Direct(it.message ?: "Unable to share logs"))
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(Res.string.simple_mode_logs))
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            TextButton(
                modifier = Modifier.padding(bottom = 8.dp),
                onClick = {
                    // Full UI is for inspection/manual control; do not abort adapt/TCP while VPN is up.
                    if (!status.state.canStop && SimpleModeConnectCoordinator.isInFlight()) {
                        SimpleModeConnectCoordinator.cancel("full_mode")
                    }
                    DataStore.simpleMode = false
                    simpleModeLog(
                        "SimpleMode",
                        "switch_to_full_mode_clicked state=${status.state.name} " +
                            "connectInFlight=${SimpleModeConnectCoordinator.isInFlight()}",
                    )
                    onOpenFullMode()
                },
            ) {
                Text(text = stringResource(Res.string.simple_mode_full_ui))
            }
        }
    }
}

private enum class StatusTone {
    STOPPED,
    CONNECTING,
    CONNECTED,
}

private fun statusTone(
    state: ServiceState,
    permissionPending: Boolean,
    activityText: String,
): StatusTone {
    return when {
        state == ServiceState.Connected && !isSimpleModeProgressActivity(activityText) &&
            !permissionPending && !SimpleModeConnectCoordinator.isInFlight() -> StatusTone.CONNECTED
        permissionPending -> StatusTone.CONNECTING
        activityText.isNotBlank() -> StatusTone.CONNECTING
        state == ServiceState.Connecting -> StatusTone.CONNECTING
        else -> StatusTone.STOPPED
    }
}

@Composable
private fun StatusTone.color(): Color {
    return when (this) {
        StatusTone.STOPPED -> MaterialTheme.colorScheme.error
        StatusTone.CONNECTING -> Color(0xFFC58A00)
        StatusTone.CONNECTED -> Color(0xFF2E7D32)
    }
}
