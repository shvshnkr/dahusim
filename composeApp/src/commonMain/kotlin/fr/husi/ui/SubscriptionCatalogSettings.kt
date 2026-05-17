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
import fr.husi.ktx.contentOrUnset
import fr.husi.ktx.onDefaultDispatcher
import fr.husi.resources.Res
import fr.husi.resources.subscription_catalog_check_enabled
import fr.husi.resources.subscription_catalog_check_interval_hours
import fr.husi.resources.subscription_catalog_check_now
import fr.husi.resources.subscription_catalog_error
import fr.husi.resources.subscription_catalog_settings
import fr.husi.resources.subscription_catalog_summary
import fr.husi.resources.subscription_catalog_sync_blocked
import fr.husi.resources.subscription_catalog_sync_skipped
import fr.husi.resources.subscription_catalog_sync_success
import fr.husi.resources.security
import fr.husi.resources.subscription_catalog_url
import fr.husi.subscription.catalog.SubscriptionCatalogCoordinator
import fr.husi.subscription.catalog.SubscriptionCatalogSyncResult
import kotlinx.coroutines.launch
import me.zhanghai.compose.preference.Preference
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.TextFieldPreference
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

internal fun LazyListScope.subscriptionCatalogSettings(
    showMessage: (String) -> Unit,
) {
    item(Key.SUBSCRIPTION_CATALOG_SETTINGS, PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.subscription_catalog_settings)) })
    }
    item(Key.SUBSCRIPTION_CATALOG_ENABLED, PreferenceType.SWITCH) {
        val enabled by DataStore.configurationStore
            .booleanFlow(Key.SUBSCRIPTION_CATALOG_ENABLED, true)
            .collectAsStateWithLifecycle(true)
        SwitchPreference(
            value = enabled,
            onValueChange = { DataStore.subscriptionCatalogEnabled = it },
            title = { Text(stringResource(Res.string.subscription_catalog_check_enabled)) },
            summary = { Text(stringResource(Res.string.subscription_catalog_summary)) },
            icon = { Icon(vectorResource(Res.drawable.security), null) },
        )
    }
    item(Key.SUBSCRIPTION_CATALOG_URL, PreferenceType.TEXT_FIELD) {
        val value by DataStore.configurationStore
            .stringFlow(Key.SUBSCRIPTION_CATALOG_URL, DataStore.subscriptionCatalogUrl)
            .collectAsStateWithLifecycle(DataStore.subscriptionCatalogUrl)
        TextFieldPreference(
            value = value,
            onValueChange = { DataStore.subscriptionCatalogUrl = it },
            title = { Text(stringResource(Res.string.subscription_catalog_url)) },
            textToValue = { it },
            icon = { Icon(vectorResource(Res.drawable.security), null) },
            summary = { Text(contentOrUnset(value)) },
            valueToText = { it },
        )
    }
    item(Key.SUBSCRIPTION_CATALOG_CHECK_INTERVAL_HOURS, PreferenceType.TEXT_FIELD) {
        val hours by DataStore.configurationStore
            .intFlow(Key.SUBSCRIPTION_CATALOG_CHECK_INTERVAL_HOURS, 12)
            .collectAsStateWithLifecycle(12)
        var preview by remember(hours) { mutableStateOf(hours.toFloat()) }
        me.zhanghai.compose.preference.SliderPreference(
            value = hours.toFloat(),
            onValueChange = {
                DataStore.subscriptionCatalogCheckIntervalHours = it.toInt().coerceIn(6, 12)
            },
            sliderValue = preview,
            onSliderValueChange = { preview = it },
            title = { Text(stringResource(Res.string.subscription_catalog_check_interval_hours)) },
            valueRange = 6f..12f,
            valueSteps = 5,
            icon = { Icon(vectorResource(Res.drawable.security), null) },
            valueText = { Text(preview.toInt().toString()) },
        )
    }
    item(Key.SUBSCRIPTION_CATALOG_CHECK_NOW, PreferenceType.TEXT_FIELD) {
        val scope = rememberCoroutineScope()
        var checking by remember { mutableStateOf(false) }
        Preference(
            title = { Text(stringResource(Res.string.subscription_catalog_check_now)) },
            enabled = !checking,
            icon = { Icon(vectorResource(Res.drawable.security), null) },
            onClick = {
                if (checking) return@Preference
                checking = true
                scope.launch {
                    val result = onDefaultDispatcher {
                        SubscriptionCatalogCoordinator.syncIfDue(manual = true)
                    }
                    val message = when (result) {
                        SubscriptionCatalogSyncResult.Skipped ->
                            getStringOrRes(StringOrRes.Res(Res.string.subscription_catalog_sync_skipped))
                        is SubscriptionCatalogSyncResult.Success ->
                            getStringOrRes(
                                StringOrRes.ResWithParams(
                                    Res.string.subscription_catalog_sync_success,
                                    result.created,
                                    result.updated,
                                    result.removed,
                                    result.stagedRemoval,
                                ),
                            )
                        is SubscriptionCatalogSyncResult.Blocked ->
                            getStringOrRes(
                                StringOrRes.ResWithParams(
                                    Res.string.subscription_catalog_sync_blocked,
                                    result.reason,
                                ),
                            )
                        is SubscriptionCatalogSyncResult.Error ->
                            getStringOrRes(
                                StringOrRes.ResWithParams(
                                    Res.string.subscription_catalog_error,
                                    result.reason,
                                ),
                            )
                    }
                    showMessage(message)
                    checking = false
                }
            },
        )
    }
}
