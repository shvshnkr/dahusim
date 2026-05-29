package fr.husi.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.husi.platform.PlatformInfo
import fr.husi.resources.Res
import fr.husi.resources.ignore_battery_optimizations
import fr.husi.resources.simple_mode_unclean_stop_battery_already_off
import fr.husi.resources.simple_mode_unclean_stop_battery_restricted
import fr.husi.resources.simple_mode_unclean_stop_title
import fr.husi.ui.rememberRequestIgnoreBatteryOptimizations
import fr.husi.ui.rememberShouldRequestBatteryOptimizations
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SimpleModeUncleanStopNotice(modifier: Modifier = Modifier) {
    if (!PlatformInfo.isAndroid) return
    val shouldRequestBattery = rememberShouldRequestBatteryOptimizations()
    val requestIgnoreBatteryOptimizations = rememberRequestIgnoreBatteryOptimizations()
    val body = if (shouldRequestBattery) {
        stringResource(Res.string.simple_mode_unclean_stop_battery_restricted)
    } else {
        stringResource(Res.string.simple_mode_unclean_stop_battery_already_off)
    }
    val containerColor = MaterialTheme.colorScheme.errorContainer
    val contentColor = MaterialTheme.colorScheme.onErrorContainer
    val borderColor = MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(containerColor, shape)
            .border(1.dp, borderColor, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = stringResource(Res.string.simple_mode_unclean_stop_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            textAlign = TextAlign.Start,
        )
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor,
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
        }
    }
}
