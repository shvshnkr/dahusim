package fr.husi.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.resources.Res
import fr.husi.resources.app_update_available_message
import fr.husi.resources.app_update_available_title
import fr.husi.resources.app_update_install
import fr.husi.resources.app_update_install_failed
import fr.husi.resources.app_update_install_pending
import fr.husi.resources.app_update_install_pending_desktop
import fr.husi.resources.app_update_later
import fr.husi.resources.app_update_disable_checks
import fr.husi.repository.resolveRepository
import fr.husi.update.AppUpdateCoordinator
import fr.husi.update.AppUpdateInstallResult
import fr.husi.platform.PlatformInfo
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppUpdatePromptHost(
    showMessage: (String) -> Unit,
) {
    val offer by AppUpdateCoordinator.pendingOffer.collectAsStateWithLifecycle()
    var installing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repository = remember { resolveRepository() }
    val disableChecksText = stringResource(Res.string.app_update_disable_checks)

    offer?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                if (!pending.mandatory) {
                    AppUpdateCoordinator.dismissOffer(pending)
                }
            },
            title = { Text(stringResource(Res.string.app_update_available_title)) },
            text = {
                val body = pending.notes.ifBlank {
                    stringResource(
                        Res.string.app_update_available_message,
                        pending.versionName,
                        pending.versionCode,
                    )
                }
                Text(body)
            },
            confirmButton = {
                TextButton(
                    enabled = !installing,
                    onClick = {
                        installing = true
                        scope.launch {
                            when (val result = onDefaultDispatcher { AppUpdateCoordinator.installPendingOffer() }) {
                                AppUpdateInstallResult.Success,
                                AppUpdateInstallResult.Cancelled,
                                -> Unit
                                AppUpdateInstallResult.PendingUserAction -> showMessage(
                                    repository.getString(
                                        if (PlatformInfo.isAndroid) {
                                            Res.string.app_update_install_pending
                                        } else {
                                            Res.string.app_update_install_pending_desktop
                                        },
                                    ),
                                )
                                is AppUpdateInstallResult.Failed -> showMessage(
                                    repository.getString(
                                        Res.string.app_update_install_failed,
                                        result.message,
                                    ),
                                )
                            }
                            installing = false
                        }
                    },
                ) {
                    Text(stringResource(Res.string.app_update_install))
                }
            },
            dismissButton = {
                if (!pending.mandatory) {
                    androidx.compose.foundation.layout.Row {
                        TextButton(
                            enabled = !installing,
                            onClick = {
                                AppUpdateCoordinator.disableChecks()
                                showMessage(disableChecksText)
                            },
                        ) {
                            Text(stringResource(Res.string.app_update_disable_checks))
                        }
                        TextButton(
                            enabled = !installing,
                            onClick = { AppUpdateCoordinator.dismissOffer(pending) },
                        ) {
                            Text(stringResource(Res.string.app_update_later))
                        }
                    }
                }
            },
        )
    }
}
