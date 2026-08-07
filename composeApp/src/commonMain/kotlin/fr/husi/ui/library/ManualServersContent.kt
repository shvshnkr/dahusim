package fr.husi.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.husi.bg.ServiceState
import fr.husi.compose.SimpleIconButton
import fr.husi.compose.TextButton
import fr.husi.compose.canStartFromFullFab
import fr.husi.compose.colorForUrlTestDelay
import fr.husi.compose.material3.Button
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.Text
import fr.husi.compose.rememberVpnServiceLauncher
import fr.husi.database.ProxyEntity
import fr.husi.database.displayType
import fr.husi.ktx.readableUrlTestError
import fr.husi.resources.Res
import fr.husi.resources.available
import fr.husi.resources.cancel
import fr.husi.resources.connect
import fr.husi.resources.connecting
import fr.husi.resources.connection_test
import fr.husi.resources.connection_test_domain_not_found
import fr.husi.resources.connection_test_error
import fr.husi.resources.connection_test_icmp_ping_unavailable
import fr.husi.resources.connection_test_refused
import fr.husi.resources.connection_test_tcp_ping_unavailable
import fr.husi.resources.connection_test_timeout
import fr.husi.resources.connection_test_unreachable
import fr.husi.resources.delete
import fr.husi.resources.ecg
import fr.husi.resources.edit
import fr.husi.resources.library_empty_manual_profiles
import fr.husi.resources.library_empty_manual_profiles_action
import fr.husi.resources.library_manual_action_test
import fr.husi.resources.library_manual_filter_all
import fr.husi.resources.library_manual_no_folder
import fr.husi.resources.library_manual_status_connected
import fr.husi.resources.library_manual_status_untested
import fr.husi.resources.library_manage_folders
import fr.husi.resources.plugin_unknown
import fr.husi.resources.simple_mode_disconnect
import fr.husi.resources.unavailable
import fr.husi.ui.NavRoutes
import fr.husi.ui.configuration.ConfigurationScreenViewModel
import fr.husi.ui.configuration.FailureReason
import fr.husi.ui.configuration.TestResult
import fr.husi.ui.configuration.TestType
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualServersContent(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    segment: LibrarySegment,
    onSegmentSelect: (LibrarySegment) -> Unit,
    configViewModel: ConfigurationScreenViewModel,
    serviceState: ServiceState,
    onAdd: () -> Unit,
    onManageFolders: () -> Unit,
    onOpenProfileEditor: (NavRoutes.ProfileEditor) -> Unit,
    manualViewModel: ManualServersViewModel = viewModel { ManualServersViewModel() },
) {
    val ungroupedLabel = stringResource(Res.string.library_manual_no_folder)
    LaunchedEffect(ungroupedLabel) {
        manualViewModel.setUngroupedLabel(ungroupedLabel)
    }
    val uiState by manualViewModel.uiState.collectAsStateWithLifecycle()
    val configUiState by configViewModel.uiState.collectAsStateWithLifecycle()
    var permissionPending by remember { mutableStateOf(false) }
    val connector = rememberVpnServiceLauncher {
        permissionPending = false
    }
    LaunchedEffect(serviceState) {
        when (serviceState) {
            ServiceState.Connected,
            ServiceState.Stopped,
            ServiceState.Idle,
            -> permissionPending = false
            else -> Unit
        }
    }

    DisposableEffect(Unit) {
        onDispose { manualViewModel.commit() }
    }

    configUiState.testState?.let { testState ->
        ManualServerTestDialog(
            testState = testState,
            onCancel = configViewModel::cancelTest,
        )
    }

    if (uiState.rows.isEmpty() && uiState.chips.isEmpty()) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item("segments") {
                LibrarySegmentRow(
                    selected = segment,
                    onSelect = onSegmentSelect,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item("manage_folders") {
                ManualFoldersToolbar(onManageFolders = onManageFolders)
            }
            item("empty") {
                LibraryEmptyState(
                    segment = segment,
                    onAdd = onAdd,
                    onRefresh = {},
                    titleRes = Res.string.library_empty_manual_profiles,
                    actionRes = Res.string.library_empty_manual_profiles_action,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item("segments") {
            LibrarySegmentRow(
                selected = segment,
                onSelect = onSegmentSelect,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item("manage_folders") {
            ManualFoldersToolbar(onManageFolders = onManageFolders)
        }
        if (uiState.chips.size > 1) {
            item("chips") {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = uiState.selectedChipGroupId == null,
                        onClick = { manualViewModel.selectChip(null) },
                        label = { Text(stringResource(Res.string.library_manual_filter_all)) },
                    )
                    for (chip in uiState.chips) {
                        FilterChip(
                            selected = uiState.selectedChipGroupId == chip.groupId,
                            onClick = { manualViewModel.selectChip(chip.groupId) },
                            label = { Text("${chip.label} (${chip.profileCount})") },
                        )
                    }
                }
            }
        }
        items(uiState.rows, key = { it.profile.id }) { row ->
            val isTesting = configUiState.testState != null &&
                (configUiState.testState?.total == 1 ||
                    configUiState.testState?.latestResult?.profile?.id == row.profile.id)
            ManualServerRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                row = row,
                serviceState = serviceState,
                selectedProfileId = uiState.selectedProfileId,
                isTesting = isTesting,
                permissionPending = permissionPending,
                onConnect = {
                    if (!canStartFromFullFab(serviceState, permissionPending) &&
                        !(serviceState.canStop && row.profile.id == uiState.selectedProfileId)
                    ) {
                        return@ManualServerRow
                    }
                    configViewModel.toggleManualServerConnection(
                        profileId = row.profile.id,
                        groupId = row.groupId,
                    ) {
                        permissionPending = true
                        connector()
                    }
                },
                onUrlTest = {
                    configViewModel.doTest(
                        group = row.groupId,
                        type = TestType.URLTest,
                        profileIds = setOf(row.profile.id),
                    )
                },
                onEdit = {
                    onOpenProfileEditor(
                        NavRoutes.ProfileEditor(
                            type = row.profile.type,
                            id = row.profile.id,
                        ),
                    )
                },
                onDelete = { manualViewModel.undoableRemove(row.profile.id, row.groupId) },
            )
        }
        item("fab_spacer") {
            Spacer(Modifier.size(88.dp))
        }
    }
}

@Composable
private fun ManualFoldersToolbar(
    onManageFolders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onManageFolders) {
            Text(stringResource(Res.string.library_manage_folders))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualServerRow(
    row: ManualServersPolicy.ProfileRow,
    serviceState: ServiceState,
    selectedProfileId: Long,
    isTesting: Boolean,
    permissionPending: Boolean,
    onConnect: () -> Unit,
    onUrlTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = row.profile
    val bean = profile.requireBean()
    val isSelected = profile.id == selectedProfileId
    val isConnected = serviceState.connected && isSelected
    val isConnecting = serviceState == ServiceState.Connecting && isSelected

    val (statusText, statusColor) = manualServerLatencyLabel(profile)
    val swipeState = rememberSwipeToDismissBoxState()

    LaunchedEffect(swipeState.currentValue) {
        if (swipeState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            swipeState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    val cardBorder = when {
        isConnected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        isSelected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
        else -> CardDefaults.outlinedCardBorder()
    }

    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = vectorResource(Res.drawable.delete),
                    contentDescription = stringResource(Res.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
            border = cardBorder,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = bean.displayName().ifBlank { bean.displayAddress() },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${profile.displayType()} · ${row.groupLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isConnected) {
                        ManualServerStatusBadge(
                            text = stringResource(Res.string.library_manual_status_connected),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val connectEnabled = when {
                        isConnecting || permissionPending -> false
                        isConnected -> true
                        else -> canStartFromFullFab(serviceState, permissionPending)
                    }
                    val connectLabel = when {
                        isConnecting || (permissionPending && isSelected) ->
                            stringResource(Res.string.connecting)

                        isConnected -> stringResource(Res.string.simple_mode_disconnect)
                        else -> stringResource(Res.string.connect)
                    }
                    Button(
                        onClick = onConnect,
                        enabled = connectEnabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(connectLabel)
                    }
                    OutlinedButton(
                        onClick = onUrlTest,
                        enabled = !isTesting,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isTesting) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(18.dp))
                        } else {
                            Text(stringResource(Res.string.library_manual_action_test))
                        }
                    }
                    SimpleIconButton(
                        imageVector = vectorResource(Res.drawable.edit),
                        contentDescription = stringResource(Res.string.edit),
                        onClick = onEdit,
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualServerStatusBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun manualServerLatencyLabel(profile: ProxyEntity): Pair<String, Color> {
    return when (profile.status) {
        ProxyEntity.STATUS_AVAILABLE ->
            stringResource(Res.string.available, profile.ping) to
                colorForUrlTestDelay(profile.ping)

        ProxyEntity.STATUS_UNAVAILABLE -> {
            val text = readableUrlTestError(profile.error)?.let { stringResource(it) }
                ?: stringResource(Res.string.unavailable)
            text to Color.Red
        }

        ProxyEntity.STATUS_UNREACHABLE -> {
            val text = readableUrlTestError(profile.error)?.let { stringResource(it) }
                ?: stringResource(Res.string.connection_test_unreachable)
            text to Color.Red
        }

        else ->
            stringResource(Res.string.library_manual_status_untested) to
                MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun ManualServerTestDialog(
    testState: fr.husi.ui.configuration.ConfigurationTestUiState,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            TextButton(stringResource(Res.string.cancel), onCancel)
        },
        icon = {
            Icon(vectorResource(Res.drawable.ecg), contentDescription = null)
        },
        title = {
            Text(stringResource(Res.string.connection_test))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularWavyProgressIndicator(
                    progress = {
                        if (testState.total > 0) {
                            testState.processedCount.toFloat() / testState.total.toFloat()
                        } else {
                            0f
                        }
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
                testState.latestResult?.let { result ->
                    val profile = result.profile
                    val bean = profile.requireBean()
                    val (resultText, resultColor) = when (val testResult = result.result) {
                        is TestResult.Success ->
                            "${testResult.ping} ms" to colorForUrlTestDelay(testResult.ping)

                        is TestResult.Failure -> {
                            val text = when (val reason = testResult.reason) {
                                FailureReason.InvalidConfig ->
                                    stringResource(Res.string.connection_test_error, "Invalid Config")

                                FailureReason.DomainNotFound ->
                                    stringResource(Res.string.connection_test_domain_not_found)

                                FailureReason.IcmpUnavailable ->
                                    stringResource(Res.string.connection_test_icmp_ping_unavailable)

                                FailureReason.TcpUnavailable ->
                                    stringResource(Res.string.connection_test_tcp_ping_unavailable)

                                FailureReason.ConnectionRefused ->
                                    stringResource(Res.string.connection_test_refused)

                                FailureReason.Timeout ->
                                    stringResource(Res.string.connection_test_timeout)

                                FailureReason.NetworkUnreachable ->
                                    stringResource(Res.string.connection_test_unreachable)

                                is FailureReason.Generic -> reason.message ?: "Unknown"

                                is FailureReason.PluginNotFound ->
                                    stringResource(Res.string.plugin_unknown, reason.plugin)
                            }
                            text to Color.Red
                        }
                    }
                    Text(
                        text = bean.displayName().ifBlank { bean.displayAddress() },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = resultText,
                        color = resultColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}
