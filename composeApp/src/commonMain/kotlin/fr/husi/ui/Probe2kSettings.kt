package fr.husi.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.compose.PreferenceCategory
import fr.husi.compose.PreferenceType
import fr.husi.database.DataStore
import fr.husi.database.Probe2kDefaults
import fr.husi.database.Probe2kProgress
import fr.husi.database.ProbeScheduler
import fr.husi.database.UserPoolMode
import fr.husi.database.UserPoolPolicy
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.resources.Res
import fr.husi.resources.cancel
import fr.husi.resources.dahusim_section_performance
import fr.husi.resources.dahusim_section_pool_priority
import fr.husi.resources.dahusim_section_probe2k
import fr.husi.resources.dahusim_section_telegram_probe
import fr.husi.resources.expert_connect_recover_enabled
import fr.husi.resources.expert_connect_recover_summary
import fr.husi.resources.fast_forward
import fr.husi.resources.ok
import fr.husi.resources.probe_2k_background_done
import fr.husi.resources.probe_2k_background_scheduler_enabled
import fr.husi.resources.probe_2k_background_scheduler_summary
import fr.husi.resources.probe_2k_builtin_cap_enabled
import fr.husi.resources.probe_2k_last_reason
import fr.husi.resources.probe_2k_persistence_enabled
import fr.husi.resources.probe_2k_persistence_summary
import fr.husi.resources.probe_2k_power_high
import fr.husi.resources.probe_2k_power_low
import fr.husi.resources.probe_2k_power_normal
import fr.husi.resources.probe_2k_power_preset
import fr.husi.resources.probe_2k_run_background_now
import fr.husi.resources.probe_2k_warm_reserve_count
import fr.husi.resources.probe_2k_warm_reserve_status
import fr.husi.resources.probe_2k_warm_reserve_summary
import fr.husi.resources.probe_2k_warm_ranking_enabled
import fr.husi.resources.probe_2k_warm_ranking_summary
import fr.husi.resources.switch_use_full_picker
import fr.husi.resources.switch_use_full_picker_summary
import fr.husi.resources.user_pool_mode
import fr.husi.resources.user_pool_mode_exclusive
import fr.husi.resources.user_pool_mode_exclusive_confirm
import fr.husi.resources.user_pool_mode_exclusive_confirm_title
import fr.husi.resources.user_pool_mode_help
import fr.husi.resources.user_pool_mode_off
import fr.husi.resources.user_pool_mode_priority
import fr.husi.resources.user_pool_mode_priority_fallback
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.ListPreferenceType
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SliderPreference
import me.zhanghai.compose.preference.SwitchPreference
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

internal fun LazyListScope.probe2kAutoselectSettings(
    showMessage: (String) -> Unit,
) {
    item("dahusim_section_telegram_probe", PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.dahusim_section_telegram_probe)) })
    }
    simpleModeProbeSettings()

    item("dahusim_section_pool_priority", PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.dahusim_section_pool_priority)) })
    }
    probe2kPoolSettings()

    item("dahusim_section_probe2k", PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.dahusim_section_probe2k)) })
    }
    probe2kCoreSettings(showMessage)

    item("dahusim_section_performance", PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.dahusim_section_performance)) })
    }
    probeParallelismCategory()
    connectionTestConcurrentPreference()
}

/** @deprecated Use [probe2kAutoselectSettings] from Dahusim autoselect screen. */
internal fun LazyListScope.probe2kSettings(
    showMessage: (String) -> Unit,
) = probe2kAutoselectSettings(showMessage)

private fun LazyListScope.probe2kPoolSettings() {
    item(Key.USER_POOL_MODE, PreferenceType.LIST) {
        val wire by DataStore.configurationStore
            .intFlow(Key.USER_POOL_MODE, UserPoolMode.OFF.wire)
            .collectAsStateWithLifecycle(UserPoolMode.OFF.wire)
        val mode = UserPoolMode.fromWire(wire)
        val values = UserPoolMode.entries.map { it.wire }
        var showExclusiveConfirm by remember { mutableStateOf(false) }
        var pendingWire by remember { mutableStateOf<Int?>(null) }

        fun applyUserPoolMode(newWire: Int) {
            DataStore.userPoolMode = newWire
            UserPoolPolicy.simpleModeUserPoolFallbackUsed = false
        }

        ListPreference(
            value = wire,
            onValueChange = { newWire ->
                val newMode = UserPoolMode.fromWire(newWire)
                if (newMode == UserPoolMode.EXCLUSIVE && mode != UserPoolMode.EXCLUSIVE) {
                    pendingWire = newWire
                    showExclusiveConfirm = true
                } else {
                    applyUserPoolMode(newWire)
                }
            },
            values = values,
            title = { Text(stringResource(Res.string.user_pool_mode)) },
            summary = { Text(userPoolModeLabel(mode)) },
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
            type = ListPreferenceType.DROPDOWN_MENU,
            valueToText = { AnnotatedString(userPoolModeLabel(UserPoolMode.fromWire(it))) },
        )

        if (showExclusiveConfirm) {
            AlertDialog(
                onDismissRequest = {
                    showExclusiveConfirm = false
                    pendingWire = null
                },
                title = { Text(stringResource(Res.string.user_pool_mode_exclusive_confirm_title)) },
                text = { Text(stringResource(Res.string.user_pool_mode_exclusive_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingWire?.let(::applyUserPoolMode)
                            showExclusiveConfirm = false
                            pendingWire = null
                        },
                    ) {
                        Text(stringResource(Res.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showExclusiveConfirm = false
                            pendingWire = null
                        },
                    ) {
                        Text(stringResource(Res.string.cancel))
                    }
                },
            )
        }
    }
    item("${Key.USER_POOL_MODE}_help", PreferenceType.TEXT_FIELD) {
        Preference(
            title = { Text(stringResource(Res.string.user_pool_mode_help)) },
            enabled = false,
        )
    }
    item(Key.PROBE_2K_BUILTIN_FALLBACK_CAP_ENABLED, PreferenceType.SWITCH) {
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.PROBE_2K_BUILTIN_FALLBACK_CAP_ENABLED, false)
            .collectAsStateWithLifecycle(false)
        SwitchPreference(
            value = enabled,
            onValueChange = { DataStore.probe2kBuiltinFallbackCapEnabled = it },
            title = { Text(stringResource(Res.string.probe_2k_builtin_cap_enabled)) },
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
        )
    }
    item(Key.SWITCH_USE_FULL_PROFILE_PICKER, PreferenceType.SWITCH) {
        val useFullPicker by DataStore.configurationStore
            .booleanFlow(Key.SWITCH_USE_FULL_PROFILE_PICKER, false)
            .collectAsStateWithLifecycle(false)
        SwitchPreference(
            value = useFullPicker,
            onValueChange = { DataStore.switchUseFullProfilePicker = it },
            title = { Text(stringResource(Res.string.switch_use_full_picker)) },
            summary = { Text(stringResource(Res.string.switch_use_full_picker_summary)) },
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
        )
    }
    item(Key.EXPERT_CONNECT_RECOVER_ENABLED, PreferenceType.SWITCH) {
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.EXPERT_CONNECT_RECOVER_ENABLED, true)
            .collectAsStateWithLifecycle(false)
        SwitchPreference(
            value = enabled,
            onValueChange = { DataStore.expertConnectRecoverEnabled = it },
            title = { Text(stringResource(Res.string.expert_connect_recover_enabled)) },
            summary = { Text(stringResource(Res.string.expert_connect_recover_summary)) },
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
        )
    }
}

private fun LazyListScope.probe2kCoreSettings(
    showMessage: (String) -> Unit,
) {
    item(Key.PROBE_2K_PERSISTENCE_ENABLED, PreferenceType.SWITCH) {
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.PROBE_2K_PERSISTENCE_ENABLED, true)
            .collectAsStateWithLifecycle(true)
        SwitchPreference(
            value = enabled,
            onValueChange = { DataStore.probe2kPersistenceEnabled = it },
            title = { Text(stringResource(Res.string.probe_2k_persistence_enabled)) },
            summary = { Text(stringResource(Res.string.probe_2k_persistence_summary)) },
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
        )
    }
    item(Key.PROBE_2K_WARM_RESERVE_COUNT, PreferenceType.LIST) {
        val count by DataStore.configurationStore
            .intFlow(Key.PROBE_2K_WARM_RESERVE_COUNT, Probe2kDefaults.WARM_RESERVE_COUNT_DEFAULT)
            .collectAsStateWithLifecycle(Probe2kDefaults.WARM_RESERVE_COUNT_DEFAULT)
        val status by DataStore.configurationStore
            .stringFlow(Key.PROBE_2K_WARM_RESERVE_STATUS, "")
            .collectAsStateWithLifecycle("")
        var preview by remember(count) { mutableStateOf(count.toFloat()) }
        SliderPreference(
            value = count.toFloat(),
            onValueChange = { DataStore.probe2kWarmReserveCount = it.toInt().coerceIn(1, 4) },
            sliderValue = preview,
            onSliderValueChange = { preview = it },
            title = { Text(stringResource(Res.string.probe_2k_warm_reserve_count)) },
            summary = {
                Text(
                    buildString {
                        append(stringResource(Res.string.probe_2k_warm_reserve_summary))
                        append("\n")
                        append(
                            stringResource(
                                Res.string.probe_2k_warm_reserve_status,
                                status.ifBlank { "—" },
                            ),
                        )
                    },
                )
            },
            valueRange = 1f..4f,
            valueSteps = 2,
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
            valueText = { Text(preview.toInt().toString()) },
        )
    }
    item(Key.PROBE_2K_WARM_RANKING_ENABLED, PreferenceType.SWITCH) {
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.PROBE_2K_WARM_RANKING_ENABLED, true)
            .collectAsStateWithLifecycle(false)
        SwitchPreference(
            value = enabled,
            onValueChange = { DataStore.probe2kWarmRankingEnabled = it },
            title = { Text(stringResource(Res.string.probe_2k_warm_ranking_enabled)) },
            summary = { Text(stringResource(Res.string.probe_2k_warm_ranking_summary)) },
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
        )
    }
    item(Key.PROBE_2K_BACKGROUND_SCHEDULER_ENABLED, PreferenceType.SWITCH) {
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.PROBE_2K_BACKGROUND_SCHEDULER_ENABLED, true)
            .collectAsStateWithLifecycle(false)
        SwitchPreference(
            value = enabled,
            onValueChange = { DataStore.probe2kBackgroundSchedulerEnabled = it },
            title = { Text(stringResource(Res.string.probe_2k_background_scheduler_enabled)) },
            summary = { Text(stringResource(Res.string.probe_2k_background_scheduler_summary)) },
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
        )
    }
    item("${Key.PROBE_2K_POWER_PRESET}_picker", PreferenceType.LIST) {
        val preset by DataStore.configurationStore
            .stringFlow(Key.PROBE_2K_POWER_PRESET, Probe2kDefaults.POWER_NORMAL)
            .collectAsStateWithLifecycle(Probe2kDefaults.POWER_NORMAL)
        val values = listOf(
            Probe2kDefaults.POWER_LOW,
            Probe2kDefaults.POWER_NORMAL,
            Probe2kDefaults.POWER_HIGH,
        )
        ListPreference(
            value = preset,
            onValueChange = { Probe2kDefaults.applyPowerPreset(it) },
            values = values,
            title = { Text(stringResource(Res.string.probe_2k_power_preset)) },
            summary = { Text(powerPresetLabel(preset)) },
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
            type = ListPreferenceType.DROPDOWN_MENU,
            valueToText = { AnnotatedString(powerPresetLabel(it)) },
        )
    }
    item("${Key.PROBE_2K_LAST_SELECTION_REASON}_ro", PreferenceType.TEXT_FIELD) {
        val reason by DataStore.configurationStore
            .stringFlow(Key.PROBE_2K_LAST_SELECTION_REASON, "")
            .collectAsStateWithLifecycle("")
        Preference(
            title = { Text(stringResource(Res.string.probe_2k_last_reason, reason.ifBlank { "—" })) },
            enabled = false,
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
        )
    }
    item("${Key.PROBE_2K_BACKGROUND_SCHEDULER_ENABLED}_now", PreferenceType.TEXT_FIELD) {
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        Preference(
            title = { Text(stringResource(Res.string.probe_2k_run_background_now)) },
            enabled = !running,
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
            onClick = {
                if (running) return@Preference
                running = true
                scope.launch {
                    onDefaultDispatcher {
                        ProbeScheduler.runBackgroundMaintenance(force = true)
                        Probe2kProgress.refreshPoolCounts()
                    }
                    showMessage(getStringOrRes(StringOrRes.Res(Res.string.probe_2k_background_done)))
                    running = false
                }
            },
        )
    }
}

@androidx.compose.runtime.Composable
internal fun userPoolModeLabel(mode: UserPoolMode): String = when (mode) {
    UserPoolMode.OFF -> stringResource(Res.string.user_pool_mode_off)
    UserPoolMode.PRIORITY -> stringResource(Res.string.user_pool_mode_priority)
    UserPoolMode.PRIORITY_FALLBACK -> stringResource(Res.string.user_pool_mode_priority_fallback)
    UserPoolMode.EXCLUSIVE -> stringResource(Res.string.user_pool_mode_exclusive)
}

@androidx.compose.runtime.Composable
private fun powerPresetLabel(preset: String): String = when (preset) {
    Probe2kDefaults.POWER_LOW -> stringResource(Res.string.probe_2k_power_low)
    Probe2kDefaults.POWER_HIGH -> stringResource(Res.string.probe_2k_power_high)
    else -> stringResource(Res.string.probe_2k_power_normal)
}
