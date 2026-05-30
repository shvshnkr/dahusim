package fr.husi.ui

import androidx.compose.runtime.Composable
import fr.husi.platform.VendorBackgroundHint
import kotlinx.coroutines.flow.Flow

internal expect fun platformPluginsFlow(): Flow<List<PluginDisplay>>

@Composable
internal expect fun rememberOpenPluginCard(): (PluginDisplay) -> Unit

@Composable
internal expect fun rememberShouldRequestBatteryOptimizations(): Boolean

@Composable
internal expect fun rememberRequestIgnoreBatteryOptimizations(): () -> Unit

/** OEM launch/autostart limits when standard battery optimization is already off. */
@Composable
internal expect fun rememberVendorBackgroundHint(): VendorBackgroundHint

@Composable
internal expect fun rememberOpenVendorBackgroundSettings(): () -> Unit
