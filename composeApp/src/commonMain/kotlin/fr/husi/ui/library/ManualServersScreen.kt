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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.husi.compose.SimpleIconButton
import fr.husi.compose.colorForUrlTestDelay
import fr.husi.compose.material3.Icon
import fr.husi.database.DataStore
import fr.husi.database.ProxyEntity
import fr.husi.database.displayType
import fr.husi.ktx.readableUrlTestError
import fr.husi.resources.Res
import fr.husi.resources.connection_test
import fr.husi.resources.connect
import fr.husi.resources.library_empty_manual
import fr.husi.resources.library_empty_manual_action
import fr.husi.resources.library_filter_all
import fr.husi.resources.library_manage_folders
import fr.husi.resources.library_ungrouped_chip
import fr.husi.resources.ic_service_idle
import fr.husi.resources.update
import fr.husi.resources.unavailable
import fr.husi.ui.MainViewModel
import fr.husi.ui.NavRoutes
import fr.husi.ui.configuration.ConfigurationScreenViewModel
import fr.husi.ui.configuration.TestType
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ManualServersScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    bottomPadding: Dp,
    mainViewModel: MainViewModel,
    onManageFolders: () -> Unit,
    onOpenProfileEditor: (NavRoutes.ProfileEditor) -> Unit,
    manualViewModel: ManualServersViewModel = viewModel { ManualServersViewModel() },
    testViewModel: ConfigurationScreenViewModel = viewModel(key = "manual-url-test") {
        ConfigurationScreenViewModel()
    },
) {
    val uiState by manualViewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onManageFolders) {
                Text(stringResource(Res.string.library_manage_folders))
            }
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            uiState.chips.forEach { chip ->
                val selected = uiState.selectedFilter == chip.filterId
                FilterChip(
                    selected = selected,
                    onClick = { manualViewModel.setFolderFilter(chip.filterId) },
                    label = {
                        Text(
                            when (chip.filterId) {
                                ManualFolderFilter.All -> stringResource(Res.string.library_filter_all)
                                ManualFolderFilter.UngroupedOnly ->
                                    stringResource(Res.string.library_ungrouped_chip)
                                else -> chip.label
                            },
                        )
                    },
                )
            }
        }
        if (uiState.items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.library_empty_manual),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.library_empty_manual_action),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = contentPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = contentPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    top = 8.dp,
                    bottom = bottomPadding,
                ),
            ) {
                items(uiState.items, key = { it.profile.id }) { item ->
                    ManualServerRow(
                        item = item,
                        onConnect = {
                            DataStore.selectedGroup = item.group.id
                            DataStore.selectedProxy = item.profile.id
                        },
                        onTest = {
                            testViewModel.doTest(
                                item.group.id,
                                TestType.URLTest,
                                setOf(item.profile.id),
                            )
                        },
                        onEdit = {
                            onOpenProfileEditor(
                                NavRoutes.ProfileEditor(
                                    type = item.profile.type,
                                    id = item.profile.id,
                                ),
                            )
                        },
                    )
                }
                item("fab_spacer") {
                    Spacer(Modifier.size(88.dp))
                }
            }
        }
    }
}

@Composable
private fun ManualServerRow(
    item: ManualServerItem,
    onConnect: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
) {
    val profile = item.profile
    val delayText = when (profile.status) {
        ProxyEntity.STATUS_AVAILABLE -> "${profile.ping}ms"
        ProxyEntity.STATUS_UNAVAILABLE,
        ProxyEntity.STATUS_UNREACHABLE,
        -> readableUrlTestError(profile.error)?.let { stringResource(it) }
            ?: stringResource(Res.string.unavailable)
        else -> "—"
    }
    val delayColor = when (profile.status) {
        ProxyEntity.STATUS_AVAILABLE -> colorForUrlTestDelay(profile.ping)
        ProxyEntity.STATUS_UNAVAILABLE,
        ProxyEntity.STATUS_UNREACHABLE,
        -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    OutlinedCard(
        onClick = onEdit,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${profile.displayType()} · ${item.group.displayName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = delayText,
                style = MaterialTheme.typography.labelMedium,
                color = delayColor,
            )
            SimpleIconButton(
                imageVector = vectorResource(Res.drawable.ic_service_idle),
                contentDescription = stringResource(Res.string.connect),
                onClick = onConnect,
            )
            SimpleIconButton(
                imageVector = vectorResource(Res.drawable.update),
                contentDescription = stringResource(Res.string.connection_test),
                onClick = onTest,
            )
        }
    }
}
