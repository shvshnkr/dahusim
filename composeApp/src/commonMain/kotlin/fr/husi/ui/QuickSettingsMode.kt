package fr.husi.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.compose.PreferenceCategory
import fr.husi.compose.PreferenceType
import fr.husi.database.DataStore
import fr.husi.resources.Res
import fr.husi.resources.home
import fr.husi.resources.quick_settings_open_simple_mode
import fr.husi.resources.quick_settings_section_mode
import fr.husi.resources.quick_settings_simple_mode_enabled_sum
import fr.husi.resources.simple_mode_switch
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SwitchPreference
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

internal fun LazyListScope.quickSettingsMode(
    onOpenSimpleMode: () -> Unit,
) {
    item("quick_settings_section_mode", PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.quick_settings_section_mode)) })
    }
    item(Key.SIMPLE_MODE, PreferenceType.SWITCH) {
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.SIMPLE_MODE, true)
            .collectAsStateWithLifecycle(true)
        SwitchPreference(
            value = enabled,
            onValueChange = { DataStore.simpleMode = it },
            title = { Text(stringResource(Res.string.simple_mode_switch)) },
            summary = { Text(stringResource(Res.string.quick_settings_simple_mode_enabled_sum)) },
            icon = { Icon(vectorResource(Res.drawable.home), null) },
        )
    }
    item("quick_settings_open_simple_mode", PreferenceType.TEXT_FIELD) {
        Preference(
            title = { Text(stringResource(Res.string.quick_settings_open_simple_mode)) },
            icon = { Icon(vectorResource(Res.drawable.home), null) },
            onClick = onOpenSimpleMode,
        )
    }
}
