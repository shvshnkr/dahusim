package fr.husi.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.database.DataStore
import fr.husi.platform.PlatformInfo
import fr.husi.compose.PreferenceCategory
import fr.husi.compose.PreferenceType
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.repository.resolveRepository
import fr.husi.resources.Res
import fr.husi.resources.app_update_check_enabled
import fr.husi.resources.app_update_check_enabled_sum
import fr.husi.resources.app_update_check_interval_hours
import fr.husi.resources.app_update_available_title
import fr.husi.resources.app_update_check_now
import fr.husi.resources.app_update_open_downloaded
import fr.husi.resources.app_update_checking
import fr.husi.resources.app_update_error
import fr.husi.resources.app_update_install_pending_desktop
import fr.husi.resources.app_update_settings
import fr.husi.resources.app_update_up_to_date
import fr.husi.resources.update
import fr.husi.update.AppUpdateCheckResult
import fr.husi.update.AppUpdateCoordinator
import fr.husi.update.AppUpdateUpdater
import fr.husi.update.AppUpdateInstallResult
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SwitchPreference
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

internal fun LazyListScope.appUpdateSettings(
    showMessage: (String) -> Unit,
) {
    item(Key.APP_UPDATE_SETTINGS, PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.app_update_settings)) })
    }
    item(Key.APP_UPDATE_CHECK_ENABLED, PreferenceType.SWITCH) {
        val scope = rememberCoroutineScope()
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.APP_UPDATE_CHECK_ENABLED, true)
            .collectAsStateWithLifecycle(true)
        SwitchPreference(
            value = enabled,
            onValueChange = {
                DataStore.appUpdateCheckEnabled = it
                scope.launch {
                    runCatching { AppUpdateUpdater.reconfigureUpdater() }
                }
            },
            title = { Text(stringResource(Res.string.app_update_check_enabled)) },
            summary = { Text(stringResource(Res.string.app_update_check_enabled_sum)) },
            icon = { Icon(vectorResource(Res.drawable.update), null) },
        )
    }
    item(Key.APP_UPDATE_CHECK_INTERVAL_HOURS, PreferenceType.TEXT_FIELD) {
        val scope = rememberCoroutineScope()
        val hours by DataStore.configurationStore
            .intFlow(Key.APP_UPDATE_CHECK_INTERVAL_HOURS, 24)
            .collectAsStateWithLifecycle(24)
        var preview by remember(hours) { mutableStateOf(hours.toFloat()) }
        me.zhanghai.compose.preference.SliderPreference(
            value = hours.toFloat(),
            onValueChange = {
                DataStore.appUpdateCheckIntervalHours = it.toInt().coerceAtLeast(1)
                scope.launch {
                    runCatching { AppUpdateUpdater.reconfigureUpdater() }
                }
            },
            sliderValue = preview,
            onSliderValueChange = { preview = it },
            title = { Text(stringResource(Res.string.app_update_check_interval_hours)) },
            valueRange = 6f..168f,
            valueSteps = 27,
            icon = { Icon(vectorResource(Res.drawable.update), null) },
            valueText = { Text(preview.toInt().toString()) },
        )
    }
    item(Key.APP_UPDATE_CHECK_NOW, PreferenceType.TEXT_FIELD) {
        val scope = rememberCoroutineScope()
        val repository = remember { resolveRepository() }
        var checking by remember { mutableStateOf(false) }
        Preference(
            title = { Text(stringResource(Res.string.app_update_check_now)) },
            enabled = !checking,
            icon = { Icon(vectorResource(Res.drawable.update), null) },
            onClick = {
                if (checking) return@Preference
                checking = true
                scope.launch {
                    val result = onDefaultDispatcher { AppUpdateCoordinator.checkForUpdate(manual = true) }
                    val message = when (result) {
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
                    showMessage(message)
                    checking = false
                }
            },
        )
    }
    if (!PlatformInfo.isAndroid) {
        item(Key.APP_UPDATE_REOPEN_DOWNLOADED, PreferenceType.TEXT_FIELD) {
            val scope = rememberCoroutineScope()
            val repository = remember { resolveRepository() }
            var reopening by remember { mutableStateOf(false) }
            Preference(
                title = { Text(stringResource(Res.string.app_update_open_downloaded)) },
                enabled = !reopening,
                icon = { Icon(vectorResource(Res.drawable.update), null) },
                onClick = {
                    if (reopening) return@Preference
                    reopening = true
                    scope.launch {
                        val result = onDefaultDispatcher { AppUpdateCoordinator.reopenDownloadedArtifact() }
                        val message = when (result) {
                            AppUpdateInstallResult.PendingUserAction,
                            AppUpdateInstallResult.Success,
                            -> repository.getString(Res.string.app_update_install_pending_desktop)
                            AppUpdateInstallResult.Cancelled -> repository.getString(Res.string.app_update_up_to_date)
                            is AppUpdateInstallResult.Failed -> repository.getString(
                                Res.string.app_update_error,
                                result.message,
                            )
                        }
                        showMessage(message)
                        reopening = false
                    }
                },
            )
        }
    }
}
