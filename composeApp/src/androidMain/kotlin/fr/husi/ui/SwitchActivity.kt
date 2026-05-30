@file:OptIn(ExperimentalLayoutApi::class)

package fr.husi.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.husi.compose.material3.Surface
import fr.husi.compose.theme.AppTheme
import fr.husi.database.AutoServerSelector
import fr.husi.database.DataStore
import fr.husi.simplemode.prepareManualProfileReload
import fr.husi.permission.LocalPermissionPlatform
import fr.husi.permission.rememberAndroidPermissionPlatform
import fr.husi.resources.Res
import fr.husi.resources.switch_warm_already_best
import fr.husi.resources.switch_warm_comparing_progress
import fr.husi.resources.switch_warm_comparing_title
import fr.husi.resources.switch_warm_none
import fr.husi.resources.switch_warm_row_fail
import fr.husi.resources.switch_warm_row_ok
import fr.husi.resources.switch_warm_row_testing
import fr.husi.resources.switch_warm_switched
import fr.husi.repository.resolveRepository
import fr.husi.simplemode.WarmReserveSwitchPolicy
import fr.husi.simplemode.WarmSwitchDecision
import fr.husi.ui.configuration.ProfilePickerContent
import fr.husi.ui.configuration.rememberProfilePickerState
import org.jetbrains.compose.resources.stringResource

class SwitchActivity : ComposeActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val platformPermission = rememberAndroidPermissionPlatform()
            CompositionLocalProvider(
                LocalPermissionPlatform provides platformPermission,
            ) {
                AppTheme {
                    SwitchActivityContent(onDismiss = ::finish, onApplySwitch = ::applySwitchAndFinish)
                }
            }
        }
    }

    private fun applySwitchAndFinish(profileId: Long) {
        AutoServerSelector.applyManualSwitch(profileId)
        prepareManualProfileReload()
        resolveRepository().reloadService()
        finish()
    }

}

@Composable
private fun SwitchActivityContent(
    onDismiss: () -> Unit,
    onApplySwitch: (Long) -> Unit,
) {
    val context = LocalContext.current
    val dismissInteractionSource = remember { MutableInteractionSource() }
    val bottomPadding = WindowInsets.navigationBarsIgnoringVisibility
        .asPaddingValues()
        .calculateBottomPadding()

    val queue = remember(DataStore.autoSelectFallbackQueue, DataStore.switchUseFullProfilePicker) {
        WarmReserveSwitchPolicy.parseQueue(DataStore.autoSelectFallbackQueue)
    }
    val warmAvailable = remember(queue, DataStore.serviceState.connected) {
        WarmReserveSwitchPolicy.isWarmSwitchAvailable(
            simpleMode = DataStore.simpleMode,
            persistenceEnabled = DataStore.probe2kPersistenceEnabled,
            vpnConnected = DataStore.serviceState.connected,
            queue = queue,
        )
    }
    val screenMode = SwitchScreenPolicy.resolveInitialMode(
        DataStore.switchUseFullProfilePicker,
        warmAvailable,
    )
    val showFullPicker = screenMode == SwitchScreenMode.FULL_PICKER

    var compareRunning by remember { mutableStateOf(screenMode == SwitchScreenMode.WARM_COMPARE) }
    var progressDone by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<WarmSwitchRowUi>>(emptyList()) }

    val comparingTitle = stringResource(Res.string.switch_warm_comparing_title)
    val rowTesting = stringResource(Res.string.switch_warm_row_testing)
    val rowFail = stringResource(Res.string.switch_warm_row_fail)
    val toastSwitched = stringResource(Res.string.switch_warm_switched)
    val toastNone = stringResource(Res.string.switch_warm_none)
    val toastAlreadyBest = stringResource(Res.string.switch_warm_already_best)

    LaunchedEffect(screenMode) {
        if (screenMode != SwitchScreenMode.WARM_COMPARE) return@LaunchedEffect
        compareRunning = true
        DataStore.simpleModeActivity = SIMPLE_MODE_ACTIVITY_COMPARING_BACKUPS
        try {
            val decision = WarmSwitchRunner.runCompareAndDecide(
                onRows = { rows = it },
                onProgress = { done, total ->
                    progressDone = done
                    progressTotal = total
                },
                onActivityLine = { line -> DataStore.simpleModeActivity = line },
            )
            val toastMessage = when (decision) {
                is WarmSwitchDecision.SwitchTo -> {
                    onApplySwitch(decision.profileId)
                    toastSwitched
                }
                WarmSwitchDecision.AlreadyOnBest -> toastAlreadyBest
                WarmSwitchDecision.NoReserves, WarmSwitchDecision.NoLiveData -> toastNone
            }
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
            onDismiss()
        } finally {
            compareRunning = false
            DataStore.simpleModeActivity = ""
        }
    }

    BackHandler(enabled = compareRunning) {
        DataStore.simpleModeActivity = ""
        onDismiss()
    }

    val pickerState = rememberProfilePickerState(preSelected = DataStore.selectedProxy)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
    ) {
        if (!compareRunning) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = dismissInteractionSource,
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(if (showFullPicker) 0.75f else 0.55f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            if (showFullPicker) {
                ProfilePickerContent(
                    state = pickerState,
                    onDismiss = onDismiss,
                    onSelected = onApplySwitch,
                    modifier = Modifier
                        .testTag("switch_full_picker")
                        .fillMaxSize(),
                    bottomPadding = bottomPadding,
                )
            } else {
                Column(
                    modifier = Modifier
                        .testTag("switch_warm_progress")
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = comparingTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (progressTotal > 0) {
                        LinearProgressIndicator(
                            progress = { progressDone.toFloat() / progressTotal.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(
                                Res.string.switch_warm_comparing_progress,
                                progressDone,
                                progressTotal,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(rows, key = { it.profileId }) { row ->
                            WarmSwitchRow(
                                row = row,
                                rowTesting = rowTesting,
                                rowFail = rowFail,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarmSwitchRow(
    row: WarmSwitchRowUi,
    rowTesting: String,
    rowFail: String,
) {
    val statusText = when (row.status) {
        WarmSwitchRowStatus.Pending -> "…"
        WarmSwitchRowStatus.Testing -> rowTesting
        WarmSwitchRowStatus.Ok -> stringResource(Res.string.switch_warm_row_ok, row.latencyMs ?: 0)
        WarmSwitchRowStatus.Failed -> rowFail
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.name, style = MaterialTheme.typography.bodyLarge)
            if (row.isCurrent) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = when (row.status) {
                WarmSwitchRowStatus.Failed -> MaterialTheme.colorScheme.error
                WarmSwitchRowStatus.Ok -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
