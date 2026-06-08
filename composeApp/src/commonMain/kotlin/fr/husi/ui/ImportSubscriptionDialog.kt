package fr.husi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.husi.compose.ScrollableDialog
import fr.husi.compose.TextButton
import fr.husi.compose.material3.Icon
import fr.husi.compose.material3.Text
import fr.husi.database.DataStore
import fr.husi.database.ProxyGroup
import fr.husi.database.SubscriptionBean
import fr.husi.group.SubscriptionFetchProfile
import fr.husi.group.SubscriptionUserAgentPresets
import fr.husi.ktx.blankAsNull
import fr.husi.resources.Res
import fr.husi.resources.cancel
import fr.husi.resources.ok
import fr.husi.resources.question_mark
import fr.husi.resources.subscription_import
import fr.husi.resources.subscription_import_preview
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

@Composable
fun ImportSubscriptionDialog(
    state: ImportSubscriptionDialogState,
    onConfirm: (ProxyGroup) -> Unit,
    onDismiss: () -> Unit,
) {
    val group = state.group
    val subscription = group.subscription ?: SubscriptionBean()
    var fetchProfile by remember(state) {
        mutableStateOf(state.fetchProfile)
    }
    var customUserAgent by remember(state) { mutableStateOf(subscription.customUserAgent) }
    var uaVersionPinned by remember(state) {
        mutableStateOf(subscription.userAgentVersionOverride.isNotBlank())
    }
    var uaVersionOverride by remember(state) { mutableStateOf(subscription.userAgentVersionOverride) }
    var showAdvanced by remember(state) { mutableStateOf(state.needsUaPicker && DataStore.isExpert) }

    ScrollableDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(stringResource(Res.string.ok)) {
                val updated = group.apply {
                    this.subscription = (this.subscription ?: SubscriptionBean()).apply {
                        this.fetchProfile = fetchProfile
                        this.customUserAgent = customUserAgent
                        this.userAgentVersionOverride =
                            if (uaVersionPinned) uaVersionOverride else ""
                    }
                }
                onConfirm(updated)
            }
        },
        dismissButton = {
            TextButton(stringResource(Res.string.cancel), onDismiss)
        },
        icon = { Icon(vectorResource(Res.drawable.question_mark), null) },
        title = { Text(stringResource(Res.string.subscription_import)) },
        text = {
            ProvidePreferenceLocals {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    val detail = buildString {
                        group.name.blankAsNull()?.let { append(it).append('\n') }
                        subscription.link.blankAsNull()?.let { append(it).append('\n') }
                        subscription.token.blankAsNull()?.let { append(it) }
                    }
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail.trim(),
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    state.previewProxyCount?.let { count ->
                        Text(
                            text = stringResource(Res.string.subscription_import_preview, count),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (state.needsUaPicker || (DataStore.isExpert && showAdvanced)) {
                        SubscriptionFetchProfileBlock(
                            fetchProfile = fetchProfile,
                            onFetchProfileChange = { fetchProfile = it },
                            customUserAgent = customUserAgent,
                            onCustomUserAgentChange = { customUserAgent = it },
                            uaVersionPinned = uaVersionPinned,
                            onUaVersionPinnedChange = { uaVersionPinned = it },
                            uaVersionOverride = uaVersionOverride,
                            onUaVersionOverrideChange = { uaVersionOverride = it },
                        )
                    } else if (DataStore.isExpert) {
                        TextButton("Advanced") { showAdvanced = true }
                    } else if (fetchProfile != SubscriptionFetchProfile.DEFAULT) {
                        Text(
                            text = SubscriptionUserAgentPresets.previewUserAgent(fetchProfile),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        },
    )
}
