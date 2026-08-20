package fr.husi.ui.simple

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.husi.resources.Res
import fr.husi.resources.check
import fr.husi.resources.power
import fr.husi.resources.simple_mode_connect
import fr.husi.resources.sync
import fr.husi.resources.simple_mode_disconnect
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

internal enum class StatusTone {
    STOPPED,
    PREPARING,
    CONNECTING,
    CONNECTED,
}

@Composable
internal fun StatusTone.color(): Color {
    return when (this) {
        StatusTone.STOPPED -> MaterialTheme.colorScheme.error
        StatusTone.PREPARING -> Color(0xFF5C6BC0)
        StatusTone.CONNECTING -> Color(0xFFC58A00)
        StatusTone.CONNECTED -> Color(0xFF2E7D32)
    }
}

private val BUTTON_SIZE = 140.dp
private val INNER_BUTTON_SIZE = 92.dp
private val RING_STROKE_WIDTH = 6.dp
private const val ARC_START = -90f

/**
 * Round power button with a status ring (design: simple-screen-redesign mockup).
 * Ring states: stopped = plain outline, preparing = pulsing progress arc (live
 * [scanProgress] when provided), connecting/permission = rotating arc,
 * connected = full ring + breathing glow. Click handler and enabled flag are
 * owned by the caller so connect/disconnect logic stays in SimpleHomeScreen.
 */
@Composable
internal fun SimplePowerButton(
    tone: StatusTone,
    scanProgress: Float?,
    permissionPending: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toneColor = tone.color()
    val infiniteTransition = rememberInfiniteTransition(label = "power_button")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring_pulse",
    )
    val spinRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "ring_spin",
    )
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring_breathe",
    )

    val scanning = scanProgress != null
    val arcSweep = when {
        tone == StatusTone.CONNECTED -> 360f
        tone == StatusTone.CONNECTING || permissionPending -> 100f
        scanning -> (scanProgress!!.coerceIn(0f, 1f) * 360f).coerceAtLeast(14f)
        else -> 100f
    }
    val arcAlpha = when {
        tone == StatusTone.CONNECTED -> breatheAlpha
        tone == StatusTone.PREPARING -> pulseAlpha
        else -> 1f
    }
    val ringColor = when (tone) {
        StatusTone.STOPPED -> MaterialTheme.colorScheme.outlineVariant
        else -> toneColor
    }

    val actionDescription = stringResource(
        if (tone == StatusTone.CONNECTED) {
            Res.string.simple_mode_disconnect
        } else {
            Res.string.simple_mode_connect
        },
    )
    Box(
        modifier = modifier.size(BUTTON_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = RING_STROKE_WIDTH.toPx()
            val inset = strokeWidth / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                width = size.width - strokeWidth,
                height = size.height - strokeWidth,
            )
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(
                color = ringColor.copy(alpha = 0.12f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth),
            )
            if (tone != StatusTone.STOPPED) {
                rotate(
                    degrees = if (tone == StatusTone.CONNECTING || permissionPending) spinRotation else 0f,
                ) {
                    drawArc(
                        color = ringColor.copy(alpha = arcAlpha),
                        startAngle = ARC_START,
                        sweepAngle = arcSweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(INNER_BUTTON_SIZE)
                .background(
                    color = if (tone == StatusTone.STOPPED) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        toneColor.copy(alpha = 0.12f)
                    },
                    shape = CircleShape,
                )
                .then(
                    if (tone == StatusTone.STOPPED) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    } else {
                        Modifier
                    },
                )
                .clickable(enabled = enabled, onClick = onClick)
                .semantics { contentDescription = actionDescription },
            contentAlignment = Alignment.Center,
        ) {
            val iconColor = if (enabled) toneColor else toneColor.copy(alpha = 0.4f)
            val iconRes = when (tone) {
                StatusTone.STOPPED -> Res.drawable.power
                StatusTone.PREPARING -> Res.drawable.sync
                StatusTone.CONNECTING -> Res.drawable.power
                StatusTone.CONNECTED -> Res.drawable.check
            }
            Icon(
                imageVector = vectorResource(iconRes),
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}
