package fr.husi.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.husi.platform.PlatformInfo
import fr.husi.platform.VendorBackgroundHint
import fr.husi.resources.Res
import fr.husi.resources.ignore_battery_optimizations
import fr.husi.resources.simple_mode_open_huawei_launch_manager
import fr.husi.resources.simple_mode_open_xiaomi_autostart
import fr.husi.resources.simple_mode_unclean_stop_battery_already_off
import fr.husi.resources.simple_mode_unclean_stop_battery_restricted
import fr.husi.resources.simple_mode_unclean_stop_huawei_launch
import fr.husi.resources.simple_mode_unclean_stop_title
import fr.husi.resources.simple_mode_unclean_stop_xiaomi_autostart
import fr.husi.ui.rememberOpenVendorBackgroundSettings
import fr.husi.ui.rememberRequestIgnoreBatteryOptimizations
import fr.husi.ui.rememberShouldRequestBatteryOptimizations
import fr.husi.ui.rememberVendorBackgroundHint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SimpleModeUncleanStopNotice(modifier: Modifier = Modifier) {
    if (!PlatformInfo.isAndroid) return
    val shouldRequestBattery = rememberShouldRequestBatteryOptimizations()
    val vendorHint = rememberVendorBackgroundHint()
    val requestIgnoreBatteryOptimizations = rememberRequestIgnoreBatteryOptimizations()
    val openVendorBackgroundSettings = rememberOpenVendorBackgroundSettings()
    val body = when {
        shouldRequestBattery -> stringResource(Res.string.simple_mode_unclean_stop_battery_restricted)
        vendorHint == VendorBackgroundHint.HuaweiLaunchManager ->
            stringResource(Res.string.simple_mode_unclean_stop_huawei_launch)
        vendorHint == VendorBackgroundHint.XiaomiAutostart ->
            stringResource(Res.string.simple_mode_unclean_stop_xiaomi_autostart)
        else -> stringResource(Res.string.simple_mode_unclean_stop_battery_already_off)
    }
    val containerColor = Color(0xFFC58A00).copy(alpha = 0.12f)
    val contentColor = Color(0xFFC58A00)
    val borderColor = contentColor
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(containerColor, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(Res.string.simple_mode_unclean_stop_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            textAlign = TextAlign.Start,
        )
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.9f),
            textAlign = TextAlign.Start,
        )
        if (shouldRequestBattery) {
            OutlinedButton(
                onClick = requestIgnoreBatteryOptimizations,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = contentColor,
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = SolidColor(borderColor),
                ),
            ) {
                Text(text = stringResource(Res.string.ignore_battery_optimizations))
            }
        } else if (vendorHint != VendorBackgroundHint.None) {
            val buttonLabel = when (vendorHint) {
                VendorBackgroundHint.HuaweiLaunchManager ->
                    stringResource(Res.string.simple_mode_open_huawei_launch_manager)
                VendorBackgroundHint.XiaomiAutostart ->
                    stringResource(Res.string.simple_mode_open_xiaomi_autostart)
                VendorBackgroundHint.None -> return@Column
            }
            OutlinedButton(
                onClick = openVendorBackgroundSettings,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = contentColor,
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    brush = SolidColor(borderColor),
                ),
            ) {
                Text(text = buttonLabel)
            }
        }
    }
}
