package fr.husi.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.compose.PreferenceType
import fr.husi.database.DataStore
import fr.husi.resources.Res
import fr.husi.resources.security
import fr.husi.resources.simple_mode_telegram_probe
import fr.husi.resources.simple_mode_telegram_probe_summary
import me.zhanghai.compose.preference.SwitchPreference
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

internal fun LazyListScope.simpleModeProbeSettings() {
    item(Key.SIMPLE_MODE_TELEGRAM_PROBE, PreferenceType.SWITCH) {
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.SIMPLE_MODE_TELEGRAM_PROBE, true)
            .collectAsStateWithLifecycle(true)
        SwitchPreference(
            value = enabled,
            onValueChange = { DataStore.simpleModeTelegramProbe = it },
            title = { Text(stringResource(Res.string.simple_mode_telegram_probe)) },
            summary = { Text(stringResource(Res.string.simple_mode_telegram_probe_summary)) },
            icon = { Icon(vectorResource(Res.drawable.security), null) },
        )
    }
}
