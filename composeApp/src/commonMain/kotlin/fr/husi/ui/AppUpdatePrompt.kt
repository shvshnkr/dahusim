package fr.husi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.compose.ScrollableDialog
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.resources.Res
import fr.husi.resources.app_update_available_message
import fr.husi.resources.app_update_available_title
import fr.husi.resources.app_update_disable_checks
import fr.husi.resources.app_update_install
import fr.husi.resources.app_update_install_failed
import fr.husi.resources.app_update_install_pending
import fr.husi.resources.app_update_install_pending_desktop
import fr.husi.resources.app_update_later
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
    val installState by AppUpdateCoordinator.installState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val repository = remember { resolveRepository() }
    val disableChecksText = stringResource(Res.string.app_update_disable_checks)

    offer?.let { pending ->
        val installEnabled = !installState.active
        val onInstall: () -> Unit = {
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
            }
        }

        ScrollableDialog(
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
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Button(
                        onClick = onInstall,
                        enabled = installEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.app_update_install))
                    }
                    if (!pending.mandatory) {
                        TextButton(
                            onClick = { AppUpdateCoordinator.dismissOffer(pending) },
                            enabled = installEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(Res.string.app_update_later),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        }
                        TextButton(
                            onClick = {
                                AppUpdateCoordinator.disableChecks()
                                showMessage(disableChecksText)
                            },
                            enabled = installEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(Res.string.app_update_disable_checks),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
        )
    }
}
