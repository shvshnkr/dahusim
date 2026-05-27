package fr.husi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.BuildConfig
import fr.husi.Key
import fr.husi.compose.PlatformMenuIcon
import fr.husi.compose.SimpleTopAppBar
import fr.husi.compose.material3.Icon
import fr.husi.compose.withNavigation
import fr.husi.database.DataStore
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.ktx.showToast
import fr.husi.platform.PlatformInfo
import fr.husi.repository.resolveRepository
import fr.husi.resources.Res
import fr.husi.resources.app_update_available_title
import fr.husi.resources.app_update_channel_sum
import fr.husi.resources.app_update_check_enabled
import fr.husi.resources.app_update_check_enabled_sum
import fr.husi.resources.app_update_check_interval_hours
import fr.husi.resources.app_update_check_now
import fr.husi.resources.app_update_error
import fr.husi.resources.app_update_install
import fr.husi.resources.app_update_install_pending
import fr.husi.resources.app_update_install_pending_desktop
import fr.husi.resources.app_update_install_permission
import fr.husi.resources.app_update_install_permission_denied
import fr.husi.resources.app_update_install_permission_granted
import fr.husi.resources.app_update_install_permission_sum
import fr.husi.resources.app_update_install_stage_downloading
import fr.husi.resources.app_update_install_stage_launching_installer
import fr.husi.resources.app_update_install_stage_preparing
import fr.husi.resources.app_update_install_stage_verifying
import fr.husi.resources.app_update_installed_version
import fr.husi.resources.app_update_last_check_at
import fr.husi.resources.app_update_last_check_never
import fr.husi.resources.app_update_open_downloaded
import fr.husi.resources.app_update_pending_offer
import fr.husi.resources.app_update_screen_title
import fr.husi.resources.app_update_up_to_date
import fr.husi.resources.info
import fr.husi.resources.menu
import fr.husi.resources.update
import fr.husi.resources.warning_amber
import fr.husi.update.AppUpdateCheckResult
import fr.husi.update.AppUpdateCoordinator
import fr.husi.update.AppUpdateUpdater
import fr.husi.update.AppUpdateInstallResult
import fr.husi.update.AppUpdateInstallStage
import fr.husi.update.AppUpdatePlatform
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource
import kotlin.time.Instant

@Composable
fun AppUpdateScreen(
    modifier: Modifier = Modifier,
    onDrawerClick: () -> Unit,
) {
    val windowInsets = WindowInsets.safeDrawing
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scope = rememberCoroutineScope()
    val repository = remember { resolveRepository() }
    val scrollState = rememberScrollState()

    val checkEnabled by DataStore.configurationStore
        .booleanFlow(Key.APP_UPDATE_CHECK_ENABLED, true)
        .collectAsStateWithLifecycle(true)
    val intervalHours by DataStore.configurationStore
        .intFlow(Key.APP_UPDATE_CHECK_INTERVAL_HOURS, 24)
        .collectAsStateWithLifecycle(24)
    val lastCheckAt by DataStore.configurationStore
        .longFlow(Key.APP_UPDATE_LAST_CHECK_AT, 0L)
        .collectAsStateWithLifecycle(0L)

    val pendingOffer by AppUpdateCoordinator.pendingOffer.collectAsStateWithLifecycle()
    val installState by AppUpdateCoordinator.installState.collectAsStateWithLifecycle()

    var checking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var intervalPreview by remember(intervalHours) { mutableIntStateOf(intervalHours) }

    var canInstall by remember { mutableStateOf(AppUpdatePlatform.canInstallPackages()) }

    LaunchedEffect(Unit) {
        canInstall = AppUpdatePlatform.canInstallPackages()
    }

    fun refreshInstallPermission() {
        canInstall = AppUpdatePlatform.canInstallPackages()
    }

    fun runCheck() {
        if (checking) return
        checking = true
        scope.launch {
            val result = onDefaultDispatcher { AppUpdateCoordinator.checkForUpdate(manual = true) }
            statusMessage = when (result) {
                AppUpdateCheckResult.Disabled,
                AppUpdateCheckResult.UpToDate,
                AppUpdateCheckResult.NoPlatformArtifact,
                -> repository.getString(Res.string.app_update_up_to_date)
                is AppUpdateCheckResult.Error -> repository.getString(
                    Res.string.app_update_error,
                    result.message,
                )
                is AppUpdateCheckResult.Available -> repository.getString(
                    Res.string.app_update_available_title,
                )
            }
            checking = false
            refreshInstallPermission()
        }
    }

    val lastCheckText = if (lastCheckAt <= 0L) {
        stringResource(Res.string.app_update_last_check_never)
    } else {
        stringResource(
            Res.string.app_update_last_check_at,
            formatCheckTimestamp(lastCheckAt),
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SimpleTopAppBar(
                title = { Text(stringResource(Res.string.app_update_screen_title)) },
                navigationIcon = {
                    PlatformMenuIcon(
                        imageVector = vectorResource(Res.drawable.menu),
                        contentDescription = stringResource(Res.string.menu),
                        onClick = onDrawerClick,
                    )
                },
                windowInsets = windowInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding.withNavigation())
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.update),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    Res.string.app_update_installed_version,
                                    BuildConfig.VERSION_NAME,
                                    BuildConfig.VERSION_CODE,
                                ),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(Res.string.app_update_channel_sum),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = lastCheckText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    statusMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    FilledTonalButton(
                        onClick = { runCheck() },
                        enabled = !checking,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(stringResource(Res.string.app_update_check_now))
                    }
                }
            }

            pendingOffer?.let { offer ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.app_update_available_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(
                                Res.string.app_update_pending_offer,
                                offer.versionName,
                                offer.versionCode,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (offer.notes.isNotBlank()) {
                            Text(
                                text = offer.notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    when (val result = onDefaultDispatcher {
                                        AppUpdateCoordinator.installPendingOffer()
                                    }) {
                                        AppUpdateInstallResult.Success,
                                        AppUpdateInstallResult.Cancelled,
                                        -> Unit
                                        AppUpdateInstallResult.PendingUserAction -> showToast(
                                            repository.getString(
                                                if (PlatformInfo.isAndroid) {
                                                    Res.string.app_update_install_pending
                                                } else {
                                                    Res.string.app_update_install_pending_desktop
                                                },
                                            ),
                                        )
                                        is AppUpdateInstallResult.Failed -> showToast(
                                            repository.getString(
                                                Res.string.app_update_error,
                                                result.message,
                                            ),
                                        )
                                    }
                                    refreshInstallPermission()
                                }
                            },
                            enabled = !installState.active,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (installState.active) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(stringResource(Res.string.app_update_install))
                        }
                        val installStageText = installStageText(installState.stage)
                        if (installStageText != null) {
                            Text(
                                text = installStageText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = vectorResource(Res.drawable.update),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(Res.string.app_update_check_enabled),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = stringResource(Res.string.app_update_check_enabled_sum),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = checkEnabled,
                            onCheckedChange = {
                                DataStore.appUpdateCheckEnabled = it
                                scope.launch {
                                    runCatching { AppUpdateUpdater.reconfigureUpdater() }
                                }
                            },
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.app_update_check_interval_hours),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Slider(
                                value = intervalPreview.toFloat(),
                                onValueChange = { intervalPreview = it.toInt() },
                                onValueChangeFinished = {
                                    DataStore.appUpdateCheckIntervalHours =
                                        intervalPreview.coerceAtLeast(1)
                                    scope.launch {
                                        runCatching { AppUpdateUpdater.reconfigureUpdater() }
                                    }
                                },
                                valueRange = 6f..168f,
                                steps = 27,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = intervalPreview.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.width(36.dp),
                            )
                        }
                    }
                }
            }

            if (PlatformInfo.isAndroid) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = vectorResource(
                                    if (canInstall) Res.drawable.info else Res.drawable.warning_amber,
                                ),
                                contentDescription = null,
                                tint = if (canInstall) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.app_update_install_permission),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(
                                        if (canInstall) {
                                            Res.string.app_update_install_permission_granted
                                        } else {
                                            Res.string.app_update_install_permission_denied
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = stringResource(Res.string.app_update_install_permission_sum),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!canInstall) {
                            Button(
                                onClick = {
                                    AppUpdatePlatform.requestInstallPackagePermission()
                                    refreshInstallPermission()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(Res.string.app_update_install_permission))
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    AppUpdatePlatform.requestInstallPackagePermission()
                                    refreshInstallPermission()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(Res.string.app_update_install_permission))
                            }
                        }
                    }
                }
            }

            if (!PlatformInfo.isAndroid) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val result = onDefaultDispatcher {
                                AppUpdateCoordinator.reopenDownloadedArtifact()
                            }
                            val message = when (result) {
                                AppUpdateInstallResult.PendingUserAction,
                                AppUpdateInstallResult.Success,
                                -> repository.getString(Res.string.app_update_up_to_date)
                                AppUpdateInstallResult.Cancelled -> repository.getString(
                                    Res.string.app_update_up_to_date,
                                )
                                is AppUpdateInstallResult.Failed -> repository.getString(
                                    Res.string.app_update_error,
                                    result.message,
                                )
                            }
                            showToast(message)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(vectorResource(Res.drawable.update), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.app_update_open_downloaded))
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun installStageText(stage: AppUpdateInstallStage): String? = when (stage) {
    AppUpdateInstallStage.PREPARING -> stringResource(Res.string.app_update_install_stage_preparing)
    AppUpdateInstallStage.DOWNLOADING -> stringResource(Res.string.app_update_install_stage_downloading)
    AppUpdateInstallStage.VERIFYING -> stringResource(Res.string.app_update_install_stage_verifying)
    AppUpdateInstallStage.LAUNCHING_INSTALLER -> {
        stringResource(Res.string.app_update_install_stage_launching_installer)
    }
    AppUpdateInstallStage.AWAITING_USER_ACTION -> {
        stringResource(
            if (PlatformInfo.isAndroid) {
                Res.string.app_update_install_pending
            } else {
                Res.string.app_update_install_pending_desktop
            },
        )
    }
    AppUpdateInstallStage.IDLE -> null
}

@OptIn(FormatStringsInDatetimeFormats::class)
private fun formatCheckTimestamp(epochSeconds: Long): String {
    val format = kotlinx.datetime.LocalDateTime.Format { byUnicodePattern("yyyy-MM-dd HH:mm") }
    return format.format(
        Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.currentSystemDefault()),
    )
}
