package fr.husi.ui.dahusim

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import fr.husi.compose.TextButton
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
import fr.husi.compose.material3.Icon
import fr.husi.resources.Res
import fr.husi.resources.cancel
import fr.husi.resources.confirm
import fr.husi.resources.dahusim_quick_access
import fr.husi.resources.dahusim_tile_clear_log
import fr.husi.resources.dahusim_tile_share_log
import fr.husi.resources.dahusim_tile_updates
import fr.husi.resources.delete_sweep
import fr.husi.resources.ok
import fr.husi.resources.simple_mode_clear_log
import fr.husi.resources.simple_mode_clear_log_done
import fr.husi.resources.simple_mode_logs
import fr.husi.resources.bug_report
import fr.husi.resources.update
import fr.husi.ui.StringOrRes
import fr.husi.ui.getStringOrRes
import fr.husi.utils.canShareSimpleModeLogs
import fr.husi.utils.clearSimpleModeLogs
import fr.husi.utils.shareSimpleModeLogs
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@Composable
internal fun DahusimQuickAccessRow(
    modifier: Modifier = Modifier,
    onOpenAppUpdate: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.dahusim_quick_access),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DahusimQuickTile(
                modifier = Modifier.weight(1f),
                label = stringResource(Res.string.dahusim_tile_clear_log),
                icon = { Icon(vectorResource(Res.drawable.delete_sweep), null) },
                onClick = { showClearConfirm = true },
            )
            if (canShareSimpleModeLogs()) {
                DahusimQuickTile(
                    modifier = Modifier.weight(1f),
                    label = stringResource(Res.string.dahusim_tile_share_log),
                    icon = { Icon(vectorResource(Res.drawable.bug_report), null) },
                    onClick = {
                        scope.launch {
                            runCatching { shareSimpleModeLogs() }
                                .onFailure { showMessage(it.message ?: "Unable to share logs") }
                        }
                    },
                )
            }
            DahusimQuickTile(
                modifier = Modifier.weight(1f),
                label = stringResource(Res.string.dahusim_tile_updates),
                icon = { Icon(vectorResource(Res.drawable.update), null) },
                onClick = onOpenAppUpdate,
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = { Icon(vectorResource(Res.drawable.delete_sweep), null) },
            title = { Text(stringResource(Res.string.simple_mode_clear_log)) },
            text = { Text(stringResource(Res.string.confirm)) },
            confirmButton = {
                TextButton(
                    text = stringResource(Res.string.ok),
                    onClick = {
                        scope.launch {
                            clearSimpleModeLogs()
                            showMessage(getStringOrRes(StringOrRes.Res(Res.string.simple_mode_clear_log_done)))
                            showClearConfirm = false
                        }
                    },
                )
            },
            dismissButton = {
                TextButton(
                    text = stringResource(Res.string.cancel),
                    onClick = { showClearConfirm = false },
                )
            },
        )
    }
}

@Composable
private fun DahusimQuickTile(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
