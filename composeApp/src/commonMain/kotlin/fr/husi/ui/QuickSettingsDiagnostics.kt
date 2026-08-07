package fr.husi.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.compose.PreferenceType
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.Text
import fr.husi.database.DataStore
import fr.husi.database.Probe2kProgress
import fr.husi.resources.Res
import fr.husi.resources.bug_report
import fr.husi.resources.dahusim_diagnostics_logs_on_hub
import fr.husi.resources.dahusim_diagnostics_pool_empty
import fr.husi.resources.dahusim_diagnostics_pool_empty_hint
import fr.husi.resources.delete_sweep
import fr.husi.resources.probe_2k_pool_line
import fr.husi.resources.probe_2k_persistence_enabled
import fr.husi.resources.probe_2k_persistence_summary
import fr.husi.resources.simple_mode_clear_log
import fr.husi.resources.simple_mode_clear_log_done
import fr.husi.resources.simple_mode_logs
import fr.husi.utils.canShareSimpleModeLogs
import fr.husi.utils.clearSimpleModeLogs
import fr.husi.utils.shareSimpleModeLogs
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SwitchPreference
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

internal fun LazyListScope.dahusimDiagnosticsPreferences(
    showMessage: (String) -> Unit,
) {
    if (Probe2kProgress.hasPoolSummary()) {
        item("dahusim_pool_line", PreferenceType.TEXT_FIELD) {
            Preference(
                title = {
                    Text(
                        stringResource(
                            Res.string.probe_2k_pool_line,
                            DataStore.probe2kPoolAlive,
                            DataStore.probe2kPoolCandidate,
                            DataStore.probe2kPoolDead + DataStore.probe2kPoolCemetery,
                            DataStore.probe2kPoolUnknown,
                        ),
                    )
                },
                enabled = false,
                icon = { Icon(vectorResource(Res.drawable.bug_report), null) },
            )
        }
    } else {
        item("dahusim_pool_empty", PreferenceType.TEXT_FIELD) {
            Preference(
                title = { Text(stringResource(Res.string.dahusim_diagnostics_pool_empty)) },
                summary = { Text(stringResource(Res.string.dahusim_diagnostics_pool_empty_hint)) },
                enabled = false,
                icon = { Icon(vectorResource(Res.drawable.bug_report), null) },
            )
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
                icon = { Icon(vectorResource(Res.drawable.bug_report), null) },
            )
        }
    }
    item("dahusim_logs_on_hub", PreferenceType.TEXT_FIELD) {
        Preference(
            title = { Text(stringResource(Res.string.dahusim_diagnostics_logs_on_hub)) },
            enabled = false,
            icon = { Icon(vectorResource(Res.drawable.bug_report), null) },
        )
    }
    if (canShareSimpleModeLogs()) {
        item("dahusim_share_log", PreferenceType.TEXT_FIELD) {
            val scope = rememberCoroutineScope()
            Preference(
                title = { Text(stringResource(Res.string.simple_mode_logs)) },
                icon = { Icon(vectorResource(Res.drawable.bug_report), null) },
                onClick = {
                    scope.launch {
                        runCatching { shareSimpleModeLogs() }
                            .onFailure { showMessage(it.message ?: "Unable to share logs") }
                    }
                },
            )
        }
    }
    item("dahusim_clear_log", PreferenceType.TEXT_FIELD) {
        val scope = rememberCoroutineScope()
        Preference(
            title = { Text(stringResource(Res.string.simple_mode_clear_log)) },
            icon = { Icon(vectorResource(Res.drawable.delete_sweep), null) },
            onClick = {
                scope.launch {
                    clearSimpleModeLogs()
                    showMessage(getStringOrRes(StringOrRes.Res(Res.string.simple_mode_clear_log_done)))
                }
            },
        )
    }
}
