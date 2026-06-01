package fr.husi.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.husi.compose.SheetActionRow
import fr.husi.compose.TextButton
import fr.husi.compose.getPlainText
import fr.husi.resources.action_import_file
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.Text
import fr.husi.resources.Res
import fr.husi.resources.action_import
import fr.husi.resources.file_export
import fr.husi.resources.group_create
import fr.husi.resources.group_create_subscription
import fr.husi.resources.library_add_sheet_title
import fr.husi.resources.link
import fr.husi.resources.playlist_add
import fr.husi.resources.qr_code
import fr.husi.resources.share_qr_nfc
import fr.husi.ui.MainViewModel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryAddSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    mainViewModel: MainViewModel,
    onOpenGroupSettings: (Long) -> Unit,
    onImportFile: () -> Unit,
    onScan: (() -> Unit)?,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var clipboardPreview by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(visible) {
        if (visible) {
            clipboardPreview = clipboard.getPlainText()?.lineSequence()?.firstOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(Res.string.library_add_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SheetActionRow(
                text = stringResource(Res.string.group_create_subscription),
                leadingIcon = { Icon(vectorResource(Res.drawable.link), null) },
                onClick = {
                    onDismiss()
                    onOpenGroupSettings(0L)
                },
            )
            SheetActionRow(
                text = stringResource(Res.string.action_import_file),
                leadingIcon = { Icon(vectorResource(Res.drawable.file_export), null) },
                onClick = {
                    onDismiss()
                    onImportFile()
                },
            )
            onScan?.let { scan ->
                SheetActionRow(
                    text = stringResource(Res.string.share_qr_nfc),
                    leadingIcon = { Icon(vectorResource(Res.drawable.qr_code), null) },
                    onClick = {
                        onDismiss()
                        scan()
                    },
                )
            }
            SheetActionRow(
                text = stringResource(Res.string.group_create),
                leadingIcon = { Icon(vectorResource(Res.drawable.playlist_add), null) },
                onClick = {
                    onDismiss()
                    onOpenGroupSettings(0L)
                },
            )
            clipboardPreview?.let { preview ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(stringResource(Res.string.action_import)) {
                        scope.launch {
                            mainViewModel.parseProxy(clipboard.getPlainText())
                        }
                        onDismiss()
                    }
                }
            }
        }
    }
}
