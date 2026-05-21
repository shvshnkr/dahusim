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
import fr.husi.compose.PreferenceCategory
import fr.husi.compose.PreferenceType
import fr.husi.database.DataStore
import fr.husi.database.Probe2kDefaults
import fr.husi.database.Probe2kProgress
import fr.husi.database.ProbeScheduler
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.resources.Res
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
import fr.husi.resources.probe_2k_settings
import fr.husi.resources.probe_2k_warm_ranking_enabled
import fr.husi.resources.probe_2k_warm_ranking_summary
import fr.husi.resources.security
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SwitchPreference
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

internal fun LazyListScope.probe2kSettings(
    showMessage: (String) -> Unit,
) {
    item(Key.PROBE_2K_SETTINGS, PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.probe_2k_settings)) })
    }
    item(Key.PROBE_2K_PERSISTENCE_ENABLED, PreferenceType.SWITCH) {
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.PROBE_2K_PERSISTENCE_ENABLED, true)
            .collectAsStateWithLifecycle(true)
        SwitchPreference(
            value = enabled,
            onValueChange = { DataStore.probe2kPersistenceEnabled = it },
            title = { Text(stringResource(Res.string.probe_2k_persistence_enabled)) },
            summary = { Text(stringResource(Res.string.probe_2k_persistence_summary)) },
            icon = { Icon(vectorResource(Res.drawable.security), null) },
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
            icon = { Icon(vectorResource(Res.drawable.security), null) },
        )
    }
    item(Key.PROBE_2K_BUILTIN_FALLBACK_CAP_ENABLED, PreferenceType.SWITCH) {
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.PROBE_2K_BUILTIN_FALLBACK_CAP_ENABLED, true)
            .collectAsStateWithLifecycle(false)
        SwitchPreference(
            value = enabled,
            onValueChange = { DataStore.probe2kBuiltinFallbackCapEnabled = it },
            title = { Text(stringResource(Res.string.probe_2k_builtin_cap_enabled)) },
            icon = { Icon(vectorResource(Res.drawable.security), null) },
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
            icon = { Icon(vectorResource(Res.drawable.security), null) },
        )
    }
    item("${Key.PROBE_2K_POWER_PRESET}_low", PreferenceType.TEXT_FIELD) {
        val preset by DataStore.configurationStore
            .stringFlow(Key.PROBE_2K_POWER_PRESET, Probe2kDefaults.POWER_NORMAL)
            .collectAsStateWithLifecycle(Probe2kDefaults.POWER_NORMAL)
        Preference(
            title = { Text(stringResource(Res.string.probe_2k_power_preset)) },
            summary = {
                Text(
                    when (preset) {
                        Probe2kDefaults.POWER_LOW -> stringResource(Res.string.probe_2k_power_low)
                        Probe2kDefaults.POWER_HIGH -> stringResource(Res.string.probe_2k_power_high)
                        else -> stringResource(Res.string.probe_2k_power_normal)
                    },
                )
            },
            icon = { Icon(vectorResource(Res.drawable.security), null) },
            onClick = {
                val next = when (preset) {
                    Probe2kDefaults.POWER_LOW -> Probe2kDefaults.POWER_NORMAL
                    Probe2kDefaults.POWER_NORMAL -> Probe2kDefaults.POWER_HIGH
                    else -> Probe2kDefaults.POWER_LOW
                }
                Probe2kDefaults.applyPowerPreset(next)
            },
        )
    }
    item("${Key.PROBE_2K_LAST_SELECTION_REASON}_ro", PreferenceType.TEXT_FIELD) {
        val reason by DataStore.configurationStore
            .stringFlow(Key.PROBE_2K_LAST_SELECTION_REASON, "")
            .collectAsStateWithLifecycle("")
        Preference(
            title = { Text(stringResource(Res.string.probe_2k_last_reason, reason.ifBlank { "—" })) },
            enabled = false,
            icon = { Icon(vectorResource(Res.drawable.security), null) },
        )
    }
    item("${Key.PROBE_2K_BACKGROUND_SCHEDULER_ENABLED}_now", PreferenceType.TEXT_FIELD) {
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        Preference(
            title = { Text(stringResource(Res.string.probe_2k_run_background_now)) },
            enabled = !running,
            icon = { Icon(vectorResource(Res.drawable.security), null) },
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
