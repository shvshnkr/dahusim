package fr.husi.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.husi.Key
import fr.husi.compose.PreferenceCategory
import fr.husi.compose.PreferenceType
import fr.husi.database.DataStore
import fr.husi.group.SubscriptionFetchProfile
import fr.husi.group.SubscriptionUserAgentPresets
import fr.husi.ktx.USER_AGENT
import fr.husi.resources.Res
import fr.husi.resources.subscription_fetch_profile_default
import fr.husi.resources.subscription_fetch_profile_happ
import fr.husi.resources.subscription_fetch_profile_incy
import fr.husi.resources.subscription_fetch_profile_v2rayng
import fr.husi.resources.subscription_fetch_profile_v2raytun
import fr.husi.resources.subscription_ua_template_dahusim_sum
import fr.husi.resources.subscription_ua_template_happ_sum
import fr.husi.resources.subscription_ua_template_incy_sum
import fr.husi.resources.subscription_ua_template_reset
import fr.husi.resources.subscription_ua_template_v2rayng_sum
import fr.husi.resources.subscription_ua_template_v2raytun_sum
import fr.husi.resources.subscription_ua_template_version
import fr.husi.resources.subscription_ua_templates_section
import fr.husi.resources.subscription_ua_templates_warning
import me.zhanghai.compose.preference.TextFieldPreference
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.subscriptionUserAgentTemplatesSettings() {
    item("subscription_ua_templates_warning", PreferenceType.TEXT_FIELD) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        ) {
            Text(
                text = stringResource(Res.string.subscription_ua_templates_warning),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    item("subscription_ua_templates_clients", PreferenceType.CATEGORY) {
        PreferenceCategory(text = { Text(stringResource(Res.string.subscription_ua_templates_section)) })
    }
    clientUaTemplate(
        key = Key.SUBSCRIPTION_UA_VERSION_HAPP,
        titleRes = Res.string.subscription_fetch_profile_happ,
        summaryRes = Res.string.subscription_ua_template_happ_sum,
        profile = SubscriptionFetchProfile.HAPP,
        versionFlow = {
            DataStore.configurationStore.stringFlow(
                Key.SUBSCRIPTION_UA_VERSION_HAPP,
                SubscriptionUserAgentPresets.FactoryVersions.HAPP,
            )
        },
        onVersionChange = { DataStore.subscriptionUaVersionHapp = it },
        reset = { SubscriptionUserAgentPresets.resetTemplateToDefault(SubscriptionFetchProfile.HAPP) },
    )
    clientUaTemplate(
        key = Key.SUBSCRIPTION_UA_VERSION_V2RAYNG,
        titleRes = Res.string.subscription_fetch_profile_v2rayng,
        summaryRes = Res.string.subscription_ua_template_v2rayng_sum,
        profile = SubscriptionFetchProfile.V2RAYNG,
        versionFlow = {
            DataStore.configurationStore.stringFlow(
                Key.SUBSCRIPTION_UA_VERSION_V2RAYNG,
                SubscriptionUserAgentPresets.FactoryVersions.V2RAYNG,
            )
        },
        onVersionChange = { DataStore.subscriptionUaVersionV2rayNg = it },
        reset = { SubscriptionUserAgentPresets.resetTemplateToDefault(SubscriptionFetchProfile.V2RAYNG) },
    )
    clientUaTemplate(
        key = Key.SUBSCRIPTION_UA_VERSION_V2RAYTUN,
        titleRes = Res.string.subscription_fetch_profile_v2raytun,
        summaryRes = Res.string.subscription_ua_template_v2raytun_sum,
        profile = SubscriptionFetchProfile.V2RAYTUN,
        versionFlow = {
            DataStore.configurationStore.stringFlow(
                Key.SUBSCRIPTION_UA_VERSION_V2RAYTUN,
                SubscriptionUserAgentPresets.FactoryVersions.V2RAYTUN,
            )
        },
        onVersionChange = { DataStore.subscriptionUaVersionV2rayTun = it },
        reset = { SubscriptionUserAgentPresets.resetTemplateToDefault(SubscriptionFetchProfile.V2RAYTUN) },
    )
    clientUaTemplate(
        key = Key.SUBSCRIPTION_UA_VERSION_INCY,
        titleRes = Res.string.subscription_fetch_profile_incy,
        summaryRes = Res.string.subscription_ua_template_incy_sum,
        profile = SubscriptionFetchProfile.INCY,
        versionFlow = {
            DataStore.configurationStore.stringFlow(
                Key.SUBSCRIPTION_UA_VERSION_INCY,
                SubscriptionUserAgentPresets.FactoryVersions.INCY,
            )
        },
        onVersionChange = { DataStore.subscriptionUaVersionIncy = it },
        reset = { SubscriptionUserAgentPresets.resetTemplateToDefault(SubscriptionFetchProfile.INCY) },
    )
    item("subscription_ua_template_dahusim", PreferenceType.TEXT_FIELD) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            colors = CardDefaults.outlinedCardColors(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(Res.string.subscription_fetch_profile_default),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.subscription_ua_template_dahusim_sum),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = USER_AGENT,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

private fun LazyListScope.clientUaTemplate(
    key: String,
    titleRes: org.jetbrains.compose.resources.StringResource,
    summaryRes: org.jetbrains.compose.resources.StringResource,
    profile: Int,
    versionFlow: () -> kotlinx.coroutines.flow.Flow<String>,
    onVersionChange: (String) -> Unit,
    reset: () -> Unit,
) {
    item("${key}_version", PreferenceType.TEXT_FIELD) {
        val version by versionFlow().collectAsStateWithLifecycle(
            SubscriptionUserAgentPresets.templateVersion(profile),
        )
        var draft by remember(version) { mutableStateOf(version) }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextFieldPreference(
                    value = draft,
                    onValueChange = {
                        draft = it
                        onVersionChange(it)
                    },
                    title = { Text(stringResource(titleRes)) },
                    textToValue = { it },
                    summary = { Text(stringResource(summaryRes)) },
                    valueToText = { it },
                )
                Text(
                    text = stringResource(
                        Res.string.subscription_ua_template_version,
                        SubscriptionUserAgentPresets.formatPresetUserAgent(profile, draft),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .horizontalScroll(rememberScrollState()),
                )
                TextButton(
                    onClick = {
                        reset()
                        draft = SubscriptionUserAgentPresets.templateVersion(profile)
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(stringResource(Res.string.subscription_ua_template_reset))
                }
            }
        }
    }
}
