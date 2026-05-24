package fr.husi.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fr.husi.group.SubscriptionFetchProfile
import fr.husi.group.SubscriptionUserAgentPresets
import fr.husi.ktx.USER_AGENT
import fr.husi.resources.Res
import fr.husi.resources.subscription_fetch_profile_custom
import fr.husi.resources.subscription_fetch_profile_custom_hint
import fr.husi.resources.subscription_fetch_profile_default
import fr.husi.resources.subscription_fetch_profile_happ
import fr.husi.resources.subscription_fetch_profile_incy
import fr.husi.resources.subscription_fetch_profile_preview
import fr.husi.resources.subscription_fetch_profile_v2rayng
import fr.husi.resources.subscription_fetch_profile_v2raytun
import fr.husi.resources.subscription_ua_version_override
import fr.husi.resources.subscription_ua_version_override_sum
import fr.husi.resources.subscription_user_agent
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.TextFieldPreference
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubscriptionFetchProfileBlock(
    fetchProfile: Int,
    onFetchProfileChange: (Int) -> Unit,
    customUserAgent: String,
    onCustomUserAgentChange: (String) -> Unit,
    uaVersionPinned: Boolean,
    onUaVersionPinnedChange: (Boolean) -> Unit,
    uaVersionOverride: String,
    onUaVersionOverrideChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SubscriptionFetchProfile.selectablePresets.forEach { profile ->
                FilterChip(
                    selected = fetchProfile == profile,
                    onClick = { onFetchProfileChange(profile) },
                    label = { Text(stringResource(fetchProfileLabel(profile))) },
                )
            }
        }
        val preview = SubscriptionUserAgentPresets.previewUserAgent(
            fetchProfile = fetchProfile,
            customUserAgent = customUserAgent,
            userAgentVersionOverride = if (uaVersionPinned) uaVersionOverride else "",
        )
        Text(
            text = stringResource(Res.string.subscription_fetch_profile_preview, preview),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
        if (SubscriptionFetchProfile.hasClientVersionTemplate(fetchProfile)) {
            SwitchPreference(
                value = uaVersionPinned,
                onValueChange = onUaVersionPinnedChange,
                title = { Text(stringResource(Res.string.subscription_ua_version_override)) },
                summary = { Text(stringResource(Res.string.subscription_ua_version_override_sum)) },
            )
            if (uaVersionPinned) {
                TextFieldPreference(
                    value = uaVersionOverride,
                    onValueChange = onUaVersionOverrideChange,
                    title = { Text(stringResource(Res.string.subscription_ua_version_override)) },
                    textToValue = { it },
                    summary = {
                        Text(
                            SubscriptionUserAgentPresets.templateVersion(fetchProfile),
                        )
                    },
                    valueToText = { it },
                )
            }
        }
        if (fetchProfile == SubscriptionFetchProfile.CUSTOM) {
            TextFieldPreference(
                value = customUserAgent,
                onValueChange = onCustomUserAgentChange,
                title = { Text(stringResource(Res.string.subscription_user_agent)) },
                textToValue = { it },
                summary = {
                    Text(
                        customUserAgent.ifBlank { USER_AGENT },
                    )
                },
                valueToText = { it },
            )
            Text(
                text = stringResource(Res.string.subscription_fetch_profile_custom_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun fetchProfileLabel(profile: Int) = when (profile) {
    SubscriptionFetchProfile.HAPP -> Res.string.subscription_fetch_profile_happ
    SubscriptionFetchProfile.V2RAYNG -> Res.string.subscription_fetch_profile_v2rayng
    SubscriptionFetchProfile.V2RAYTUN -> Res.string.subscription_fetch_profile_v2raytun
    SubscriptionFetchProfile.INCY -> Res.string.subscription_fetch_profile_incy
    SubscriptionFetchProfile.CUSTOM -> Res.string.subscription_fetch_profile_custom
    else -> Res.string.subscription_fetch_profile_default
}
