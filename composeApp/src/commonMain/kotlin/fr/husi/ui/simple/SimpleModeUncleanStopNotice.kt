package fr.husi.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.husi.platform.PlatformInfo
import fr.husi.resources.Res
import fr.husi.resources.ignore_battery_optimizations
import fr.husi.resources.simple_mode_unclean_stop_battery_already_off
import fr.husi.resources.simple_mode_unclean_stop_battery_restricted
import fr.husi.ui.rememberRequestIgnoreBatteryOptimizations
import fr.husi.ui.rememberShouldRequestBatteryOptimizations
import org.jetbrains.compose.resources.stringResource

private val UncleanNoticeBackground = Color(0xFFFFF3E0)
private val UncleanNoticeBorder = Color(0xFFFF9800)

@Composable
internal fun SimpleModeUncleanStopNotice(modifier: Modifier = Modifier) {
    if (!PlatformInfo.isAndroid) return
    val shouldRequestBattery = rememberShouldRequestBatteryOptimizations()
    val requestIgnoreBatteryOptimizations = rememberRequestIgnoreBatteryOptimizations()
    val message = if (shouldRequestBattery) {
        stringResource(Res.string.simple_mode_unclean_stop_battery_restricted)
    } else {
        stringResource(Res.string.simple_mode_unclean_stop_battery_already_off)
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(UncleanNoticeBackground, shape)
            .border(1.dp, UncleanNoticeBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            modifier = Modifier.fillMaxWidth(),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (shouldRequestBattery) {
            TextButton(onClick = requestIgnoreBatteryOptimizations) {
                Text(text = stringResource(Res.string.ignore_battery_optimizations))
            }
        }
    }
}
