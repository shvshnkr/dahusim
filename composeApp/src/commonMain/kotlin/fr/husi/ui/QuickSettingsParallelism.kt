package fr.husi.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.compose.PreferenceCategory
import fr.husi.compose.PreferenceType
import fr.husi.compose.material3.Icon
import fr.husi.database.DataStore
import fr.husi.resources.Res
import fr.husi.resources.fast_forward
import fr.husi.resources.quick_settings_probe_parallelism_category
import fr.husi.resources.quick_settings_server_probe_parallelism
import fr.husi.resources.quick_settings_server_probe_parallelism_summary
import fr.husi.resources.quick_settings_sub_parallelism_category
import fr.husi.resources.quick_settings_subscription_update_parallelism_active
import fr.husi.resources.quick_settings_subscription_update_parallelism_active_summary
import fr.husi.resources.quick_settings_subscription_update_parallelism_background
import fr.husi.resources.quick_settings_subscription_update_parallelism_background_summary
import fr.husi.resources.security
import me.zhanghai.compose.preference.SliderPreference
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

internal fun LazyListScope.connectionTestConcurrentPreference() {
    item(Key.CONNECTION_TEST_CONCURRENT, PreferenceType.TEXT_FIELD) {
        val value by DataStore.configurationStore
            .intFlow(Key.CONNECTION_TEST_CONCURRENT, 20)
            .collectAsStateWithLifecycle(20)
        var previewValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
        SliderPreference(
            value = value.toFloat(),
            onValueChange = { DataStore.connectionTestConcurrent = it.toInt() },
            sliderValue = previewValue,
            onSliderValueChange = { previewValue = it },
            title = { Text(stringResource(Res.string.quick_settings_server_probe_parallelism)) },
            summary = { Text(stringResource(Res.string.quick_settings_server_probe_parallelism_summary)) },
            valueRange = 1f..32f,
            valueSteps = 31,
            icon = { Icon(vectorResource(Res.drawable.fast_forward), null) },
            valueText = { Text(previewValue.toInt().toString()) },
        )
    }
}

internal fun LazyListScope.subscriptionUpdateParallelismPreferences() {
    item("quick_settings_sub_parallelism_category", PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.quick_settings_sub_parallelism_category)) })
    }
    item(Key.SUBSCRIPTION_UPDATE_PARALLELISM_FOREGROUND, PreferenceType.TEXT_FIELD) {
        val value by DataStore.configurationStore
            .intFlow(Key.SUBSCRIPTION_UPDATE_PARALLELISM_FOREGROUND, 3)
            .collectAsStateWithLifecycle(3)
        var previewValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
        SliderPreference(
            value = value.toFloat(),
            onValueChange = {
                DataStore.subscriptionUpdateParallelismForeground = it.toInt().coerceIn(1, 6)
            },
            sliderValue = previewValue,
            onSliderValueChange = { previewValue = it },
            title = { Text(stringResource(Res.string.quick_settings_subscription_update_parallelism_active)) },
            summary = { Text(stringResource(Res.string.quick_settings_subscription_update_parallelism_active_summary)) },
            valueRange = 1f..6f,
            valueSteps = 5,
            icon = { Icon(vectorResource(Res.drawable.security), null) },
            valueText = { Text(previewValue.toInt().toString()) },
        )
    }
    item(Key.SUBSCRIPTION_UPDATE_PARALLELISM_BACKGROUND, PreferenceType.TEXT_FIELD) {
        val value by DataStore.configurationStore
            .intFlow(Key.SUBSCRIPTION_UPDATE_PARALLELISM_BACKGROUND, 1)
            .collectAsStateWithLifecycle(1)
        var previewValue by remember(value) { mutableFloatStateOf(value.toFloat()) }
        SliderPreference(
            value = value.toFloat(),
            onValueChange = {
                DataStore.subscriptionUpdateParallelismBackground = it.toInt().coerceIn(1, 2)
            },
            sliderValue = previewValue,
            onSliderValueChange = { previewValue = it },
            title = { Text(stringResource(Res.string.quick_settings_subscription_update_parallelism_background)) },
            summary = { Text(stringResource(Res.string.quick_settings_subscription_update_parallelism_background_summary)) },
            valueRange = 1f..2f,
            valueSteps = 1,
            icon = { Icon(vectorResource(Res.drawable.security), null) },
            valueText = { Text(previewValue.toInt().toString()) },
        )
    }
}

internal fun LazyListScope.probeParallelismCategory() {
    item("quick_settings_probe_parallelism_category", PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.quick_settings_probe_parallelism_category)) })
    }
}
