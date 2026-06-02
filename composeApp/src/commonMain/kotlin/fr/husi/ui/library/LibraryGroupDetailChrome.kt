package fr.husi.ui.library

import fr.husi.GroupType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.husi.compose.SimpleIconButton
import androidx.compose.material3.TextButton as Material3TextButton
import fr.husi.compose.TextButton
import fr.husi.compose.material3.Text
import fr.husi.resources.Res
import fr.husi.resources.action_import_file
import fr.husi.resources.delete
import fr.husi.resources.library_action_import
import fr.husi.resources.library_action_scan
import fr.husi.resources.group_update
import fr.husi.resources.library_bulk_copy
import fr.husi.resources.library_bulk_test
import fr.husi.resources.library_selected_count
import fr.husi.resources.sort_mode
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

internal enum class LibraryGroupDetailActionMode {
    Basic,
    Subscription,
}

internal fun libraryGroupDetailActionMode(groupType: Int): LibraryGroupDetailActionMode =
    if (groupType == GroupType.SUBSCRIPTION) {
        LibraryGroupDetailActionMode.Subscription
    } else {
        LibraryGroupDetailActionMode.Basic
    }

@Composable
internal fun LibraryActionStrip(
    modifier: Modifier = Modifier,
    mode: LibraryGroupDetailActionMode = LibraryGroupDetailActionMode.Basic,
    onImportClipboard: () -> Unit = {},
    onImportFile: () -> Unit = {},
    onScan: (() -> Unit)? = null,
    onUpdate: (() -> Unit)? = null,
    updateEnabled: Boolean = true,
    onTest: () -> Unit,
    onSort: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (mode) {
            LibraryGroupDetailActionMode.Basic -> {
                TextButton(stringResource(Res.string.library_action_import), onImportClipboard)
                onScan?.let { scan ->
                    TextButton(stringResource(Res.string.library_action_scan), scan)
                }
                TextButton(stringResource(Res.string.action_import_file), onImportFile)
            }

            LibraryGroupDetailActionMode.Subscription -> {
                onUpdate?.let { update ->
                    Material3TextButton(
                        onClick = update,
                        enabled = updateEnabled,
                    ) {
                        Text(stringResource(Res.string.group_update))
                    }
                }
            }
        }
        TextButton(stringResource(Res.string.library_bulk_test), onTest)
        TextButton(stringResource(Res.string.sort_mode), onSort)
    }
}

@Composable
internal fun LibraryBulkActionBar(
    modifier: Modifier = Modifier,
    selectedCount: Int,
    onTest: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(
                    Res.plurals.library_selected_count,
                    selectedCount,
                    selectedCount,
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(stringResource(Res.string.library_bulk_test), onTest)
                TextButton(stringResource(Res.string.library_bulk_copy), onCopy)
                SimpleIconButton(
                    imageVector = vectorResource(Res.drawable.delete),
                    contentDescription = stringResource(Res.string.delete),
                    onClick = onDelete,
                )
            }
        }
    }
}
