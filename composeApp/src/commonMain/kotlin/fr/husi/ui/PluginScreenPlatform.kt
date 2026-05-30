package fr.husi.ui

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.Flow

internal expect fun platformPluginsFlow(): Flow<List<PluginDisplay>>

@Composable
internal expect fun rememberOpenPluginCard(): (PluginDisplay) -> Unit

@Composable
internal expect fun rememberShouldRequestBatteryOptimizations(): Boolean

@Composable
internal expect fun rememberRequestIgnoreBatteryOptimizations(): () -> Unit

/** True when standard battery optimization is off but OEM launch/background limits likely apply. */
@Composable
internal expect fun rememberShowHuaweiLaunchManagerHint(): Boolean

@Composable
internal expect fun rememberOpenHuaweiLaunchManagerSettings(): () -> Unit
