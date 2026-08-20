package fr.husi.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.bg.BackendState
import fr.husi.bg.ServiceState
import fr.husi.compose.SimpleIconButton
import fr.husi.compose.rememberVpnServiceLauncher
import fr.husi.database.DataStore
import fr.husi.database.Probe2kProgress
import fr.husi.ktx.exitApplication
import fr.husi.platform.PlatformInfo
import fr.husi.repository.resolveRepository
import fr.husi.resources.Res
import fr.husi.resources.app_name
import fr.husi.resources.settings
import fr.husi.resources.simple_mode_all_servers_dead_banner_subtitle
import fr.husi.resources.simple_mode_all_servers_dead_banner_title
import fr.husi.resources.simple_mode_connected
import fr.husi.resources.simple_mode_connecting
import fr.husi.resources.simple_mode_preparing
import fr.husi.resources.simple_mode_full_ui
import fr.husi.resources.simple_mode_logs
import fr.husi.resources.simple_mode_no_internet_banner_subtitle
import fr.husi.resources.simple_mode_no_internet_banner_title
import fr.husi.resources.simple_mode_no_profile
import fr.husi.resources.simple_mode_permission_pending
import fr.husi.resources.simple_mode_permission_unlock
import fr.husi.resources.simple_mode_wl_banner_subtitle
import fr.husi.resources.simple_mode_wl_banner_title
import fr.husi.resources.simple_mode_stopped
import fr.husi.resources.probe_2k_activity_scan
import fr.husi.resources.probe_2k_pool_line
import fr.husi.resources.vpn_permission_denied
import fr.husi.ui.MainViewModel
import fr.husi.ui.SimpleModeAllServersDeadChoice
import fr.husi.ui.StringOrRes
import fr.husi.utils.canShareSimpleModeLogs
import fr.husi.simplemode.SimpleModeConnectCoordinator
import fr.husi.simplemode.SimpleModeVpnSessionMarker
import fr.husi.simplemode.releaseSimpleModeVpnSession
import fr.husi.ui.rememberShouldRequestBatteryOptimizations
import fr.husi.simplemode.isSimpleModePrepareActivity
import fr.husi.simplemode.isSimpleModeProgressActivity
import fr.husi.simplemode.isSimpleModeVpnProgressActivity
import fr.husi.utils.shareSimpleModeLogs
import fr.husi.utils.simpleModeLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** Hide the scan progress line/ring 8s after the last published scan tick. */
private const val SCAN_STALE_TIMEOUT_MS = 8_000L

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
    val scanTotal by DataStore.configurationStore
        .intFlow(Key.PROBE_2K_SCAN_TOTAL, 0)
        .collectAsStateWithLifecycle(0)
    val scanChecked by DataStore.configurationStore
        .intFlow(Key.PROBE_2K_SCAN_CHECKED, 0)
        .collectAsStateWithLifecycle(0)
    var whitelistOnly by remember { mutableStateOf(DataStore.activeWhitelistRestrictedNetwork) }
    var noInternet by remember { mutableStateOf(false) }
    var allServersDead by remember { mutableStateOf(false) }
    var showUncleanStopNotice by remember { mutableStateOf(false) }
    var scanStale by remember { mutableStateOf(false) }
    var lastScanUpdateAt by remember { mutableLongStateOf(0L) }
    val shouldRequestBatteryForLog = rememberShouldRequestBatteryOptimizations()
    LaunchedEffect(activityText, status.state) {
        whitelistOnly = DataStore.activeWhitelistRestrictedNetwork
    }
    LaunchedEffect(status.state, shouldRequestBatteryForLog) {
        when (status.state) {
            ServiceState.Stopped,
            ServiceState.Idle,
            -> {
                if (!showUncleanStopNotice) {
                    showUncleanStopNotice = PlatformInfo.isAndroid &&
                        SimpleModeVpnSessionMarker.evaluateUncleanStop(
                            batteryRestrictedForLog = shouldRequestBatteryForLog,
                        )
                }
            }
            else -> showUncleanStopNotice = false
        }
    }
    // Scan progress is a live signal: once `checked` stops advancing, hide the
    // ring/line after 8s even if nothing cleared the scan state (defense in depth
    // for the "Scanning 1/1 hangs" case; AutoServerSelector.clearScan() is the fix).
    LaunchedEffect(scanChecked, scanTotal) {
        if (scanTotal <= 0) {
            scanStale = false
            return@LaunchedEffect
        }
        lastScanUpdateAt = System.currentTimeMillis()
        while (true) {
            delay(SCAN_STALE_TIMEOUT_MS)
            if (System.currentTimeMillis() - lastScanUpdateAt >= SCAN_STALE_TIMEOUT_MS) {
                scanStale = true
                break
            }
        }
    }
    val onVpnDenied: () -> Unit = {
        permissionPending = false
        simpleModeLog("SimpleMode", "permission_denied")
        mainViewModel.showSnackbar(StringOrRes.Res(Res.string.vpn_permission_denied))
    }
    val connector = rememberVpnServiceLauncher(onVpnDenied)
    val connectHost = remember(mainViewModel, connector) {
        object : SimpleModeConnectCoordinator.ConnectHost {
            override fun setPermissionPending(pending: Boolean) {
                permissionPending = pending
            }

            override fun requestVpnConnect() {
                connector()
            }

            override fun onVpnPermissionDenied() {
                onVpnDenied()
            }

            override fun onNoInternet() {
                // Persistent banner instead of a one-shot snackbar: the user must see WHY
                // Connect does nothing when the tariff/data link is dead ("app is broken"
                // feedback, field 2026-08-21). Cleared on the next attempt / activity.
                noInternet = true
            }

            override fun onAllServersDead() {
                // Same idea for the BS dead-end: after the revival watch exhausts, the 30s
                // prompt can time out and the UI would silently return to Stopped. Keep the
                // banner until the next attempt / successful connect (field 2026-08-21).
                allServersDead = true
            }

            override fun onNoProfile() {
                mainViewModel.showSnackbar(StringOrRes.Res(Res.string.simple_mode_no_profile))
            }

            override fun onNeedForegroundForPermission() {
                mainViewModel.showSnackbar(StringOrRes.Res(Res.string.simple_mode_permission_pending))
            }

            override fun onNeedUnlockForPermission() {
                mainViewModel.showSnackbar(StringOrRes.Res(Res.string.simple_mode_permission_unlock))
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
        if (status.state == ServiceState.Connected) {
            noInternet = false
            allServersDead = false
        }
    }
    LaunchedEffect(activityText) {
        // Any non-blank activity means a connect attempt got past the network gate.
        if (activityText.isNotBlank()) {
            noInternet = false
            allServersDead = false
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

    val onOpenFullModeClick = {
        simpleModeLog(
            "SimpleMode",
            "switch_to_full_mode_clicked state=${status.state.name} " +
                "connectInFlight=${SimpleModeConnectCoordinator.isInFlight()} " +
                "simpleOwned=${DataStore.simpleMode}",
        )
        onOpenFullMode()
    }
    val onShareLogsClick: () -> Unit = {
        scope.launch {
            runCatching {
                simpleModeLog("SimpleMode", "share_logs_clicked")
                shareSimpleModeLogs()
            }.onFailure {
                simpleModeLog("SimpleMode", "share_logs_failed=${it.message}")
                mainViewModel.showSnackbar(StringOrRes.Direct(it.message ?: "Unable to share logs"))
            }
        }
    }

    val tone = statusTone(status.state, permissionPending, activityText)
    val statusLabel = when (tone) {
        StatusTone.CONNECTED -> stringResource(Res.string.simple_mode_connected)
        StatusTone.CONNECTING -> stringResource(Res.string.simple_mode_connecting)
        StatusTone.PREPARING -> stringResource(Res.string.simple_mode_preparing)
        StatusTone.STOPPED -> stringResource(Res.string.simple_mode_stopped)
    }
    // Live scan progress: only while prepare is actually running (checked < total,
    // not connected/stopped, not stale) — the stale "Scanning N/N" never sticks.
    val scanning = scanTotal > 0 &&
        scanChecked < scanTotal &&
        !scanStale &&
        tone != StatusTone.STOPPED &&
        !status.state.canStop
    val scanLine = if (scanning) {
        stringResource(Res.string.probe_2k_activity_scan, scanChecked, scanTotal)
    } else {
        null
    }
    val poolLine = if (Probe2kProgress.hasPoolSummary()) {
        stringResource(
            Res.string.probe_2k_pool_line,
            DataStore.probe2kPoolAlive,
            DataStore.probe2kPoolCandidate,
            DataStore.probe2kPoolDead + DataStore.probe2kPoolCemetery,
            DataStore.probe2kPoolUnknown,
        )
    } else {
        null
    }
    val detailText = when {
        permissionPending && !status.state.canStop -> stringResource(Res.string.simple_mode_permission_pending)
        scanLine != null -> scanLine
        activityText.isNotBlank() -> displaySimpleModeActivity(activityText)
        poolLine != null -> poolLine
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            SimpleIconButton(
                imageVector = vectorResource(Res.drawable.settings),
                contentDescription = stringResource(Res.string.simple_mode_full_ui),
                onClick = onOpenFullModeClick,
            )
        }

        if (noInternet) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.onErrorContainer, CircleShape),
                    )
                    Text(
                        text = stringResource(Res.string.simple_mode_no_internet_banner_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(Res.string.simple_mode_no_internet_banner_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    )
                }
            }
        } else if (allServersDead) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.onErrorContainer, CircleShape),
                    )
                    Text(
                        text = stringResource(Res.string.simple_mode_all_servers_dead_banner_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(Res.string.simple_mode_all_servers_dead_banner_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    )
                }
            }
        } else if (whitelistOnly) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.onErrorContainer, CircleShape),
                    )
                    Text(
                        text = stringResource(Res.string.simple_mode_wl_banner_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(Res.string.simple_mode_wl_banner_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(440.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    tone.color().copy(alpha = 0.10f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
                SimplePowerButton(
                    tone = tone,
                    scanProgress = if (scanning && tone == StatusTone.PREPARING) {
                        scanChecked.toFloat() / scanTotal
                    } else {
                        null
                    },
                    permissionPending = permissionPending,
                    enabled = !permissionPending && status.state != ServiceState.Stopping,
                    onClick = {
                        if (status.state == ServiceState.Stopping) {
                            simpleModeLog("SimpleMode", "connect_block_reason=service_stopping_ui")
                            return@SimplePowerButton
                        }
                        if (status.state.canStop) {
                            simpleModeLog("SimpleMode", "disconnect_clicked")
                            releaseSimpleModeVpnSession("simple_disconnect")
                            resolveRepository().stopService()
                            showUncleanStopNotice = false
                            return@SimplePowerButton
                        }
                        SimpleModeVpnSessionMarker.clearOnConnectAttempt()
                        showUncleanStopNotice = false
                        // New attempt: the previous network-gate result is stale — the banner
                        // re-appears only if the coordinator blocks again.
                        noInternet = false
                        allServersDead = false
                        if (permissionPending) {
                            simpleModeLog("SimpleMode", "connect_ignored_permission_pending")
                            return@SimplePowerButton
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
                )
            }
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = tone.color(),
                textAlign = TextAlign.Center,
            )
            detailText?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (scanning) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    progress = { scanChecked.toFloat() / scanTotal },
                    modifier = Modifier
                        .width(210.dp)
                        .height(4.dp),
                    color = tone.color(),
                    trackColor = tone.color().copy(alpha = 0.12f),
                )
            }
        }

        if (showUncleanStopNotice && !status.state.canStop) {
            SimpleModeUncleanStopNotice(modifier = Modifier.padding(bottom = 14.dp))
        }

        Row(
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (canShareSimpleModeLogs()) {
                TextButton(onClick = onShareLogsClick) {
                    Text(
                        text = stringResource(Res.string.simple_mode_logs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onOpenFullModeClick) {
                Text(
                    text = stringResource(Res.string.simple_mode_full_ui),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
        isSimpleModePrepareActivity(activityText) ||
            (SimpleModeConnectCoordinator.isInFlight() && !state.canStop) -> StatusTone.PREPARING
        isSimpleModeVpnProgressActivity(activityText) ||
            state == ServiceState.Connecting ||
            state.canStop -> StatusTone.CONNECTING
        else -> StatusTone.STOPPED
    }
}
