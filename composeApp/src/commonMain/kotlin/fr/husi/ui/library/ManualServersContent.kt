package fr.husi.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.husi.compose.SimpleIconButton
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.Text
import fr.husi.database.ProxyEntity
import fr.husi.database.displayType
import fr.husi.resources.Res
import fr.husi.resources.available
import fr.husi.resources.connection_test_url_test
import fr.husi.resources.connect
import fr.husi.resources.delete
import fr.husi.resources.edit
import fr.husi.resources.ic_service_idle
import fr.husi.resources.library_empty_manual_profiles
import fr.husi.resources.library_empty_manual_profiles_action
import fr.husi.resources.library_manual_filter_all
import fr.husi.resources.library_manual_no_folder
import fr.husi.resources.library_manage_folders
import fr.husi.resources.link
import fr.husi.ui.NavRoutes
import fr.husi.ui.configuration.ConfigurationScreenViewModel
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

    DisposableEffect(Unit) {
        onDispose { manualViewModel.commit() }
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
            ManualServerRow(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                row = row,
                isSelected = row.profile.id == uiState.selectedProfileId,
                onSelect = { configViewModel.onProfileSelect(row.profile.id) },
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
    isSelected: Boolean,
    onSelect: () -> Unit,
    onUrlTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = row.profile
    val bean = profile.requireBean()
    val statusText = when (profile.status) {
        ProxyEntity.STATUS_AVAILABLE ->
            stringResource(Res.string.available, profile.ping)

        else -> profile.displayType()
    }
    val statusColor = when (profile.status) {
        ProxyEntity.STATUS_AVAILABLE -> MaterialTheme.colorScheme.primary
        ProxyEntity.STATUS_UNAVAILABLE,
        ProxyEntity.STATUS_UNREACHABLE,
        -> Color.Red

        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val swipeState = rememberSwipeToDismissBoxState()

    LaunchedEffect(swipeState.currentValue) {
        when (swipeState.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> {
                onEdit()
                swipeState.snapTo(SwipeToDismissBoxValue.Settled)
            }

            SwipeToDismissBoxValue.EndToStart -> {
                onDelete()
                swipeState.snapTo(SwipeToDismissBoxValue.Settled)
            }

            else -> {}
        }
    }

    SwipeToDismissBox(
        state = swipeState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = vectorResource(Res.drawable.edit),
                    contentDescription = stringResource(Res.string.edit),
                    tint = MaterialTheme.colorScheme.primary,
                )
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
            onClick = onSelect,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bean.displayName().ifBlank { bean.displayAddress() },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isSelected) {
                            androidx.compose.ui.text.font.FontWeight.Bold
                        } else {
                            null
                        },
                    )
                    Text(
                        text = "${row.groupLabel} · $statusText",
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                    )
                }
                SimpleIconButton(
                    imageVector = vectorResource(Res.drawable.ic_service_idle),
                    contentDescription = stringResource(Res.string.connect),
                    onClick = onSelect,
                )
                SimpleIconButton(
                    imageVector = vectorResource(Res.drawable.link),
                    contentDescription = stringResource(Res.string.connection_test_url_test),
                    onClick = onUrlTest,
                )
            }
        }
    }
}
