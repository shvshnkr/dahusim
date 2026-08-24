package fr.husi.ui.simple

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.husi.resources.Res
import fr.husi.resources.simple_mode_trail_network
import fr.husi.resources.simple_mode_trail_server
import fr.husi.resources.simple_mode_trail_subs
import fr.husi.resources.simple_mode_trail_vpn
import org.jetbrains.compose.resources.stringResource

/** Ordered stages of the simple-screen step trail (mockup v2, approved 2026-08-24). */
internal enum class SimpleTrailStage { NETWORK, SUBS, SERVER, VPN }

internal enum class SimpleTrailStepState { DONE, CURRENT, FAIL, PENDING }

/**
 * The stage an activity line belongs to. Mirrors the raw prefixes
 * [fr.husi.ui.simple.displaySimpleModeActivity] already translates, so the trail and the
 * detail line always agree; blank or unknown activities map to no stage.
 */
internal fun simpleTrailStage(activityText: String): SimpleTrailStage? = when {
    activityText.isBlank() -> null
    activityText.startsWith("Checking network") -> SimpleTrailStage.NETWORK
    activityText.startsWith("Refreshing subscriptions") ||
        activityText.startsWith("Updating") -> SimpleTrailStage.SUBS
    activityText.startsWith("Finding best server") ||
        activityText.startsWith("Verifying last server") ||
        activityText.startsWith("Ranking ") ||
        activityText.startsWith("Testing TCP ") ||
        activityText.startsWith("Testing URL ") ||
        activityText.startsWith("Waiting for") -> SimpleTrailStage.SERVER
    else -> SimpleTrailStage.VPN
}

/**
 * Step trail states for the current screen tone. Always shown: Connected → all DONE,
 * FAILED → the failing stage red (network for no-internet, server for all-dead), a live
 * activity → stages before it DONE and the active stage CURRENT, idle → all PENDING.
 */
internal fun simpleTrailSteps(
    tone: StatusTone,
    activityText: String,
    noInternet: Boolean,
    allServersDead: Boolean,
): List<Pair<SimpleTrailStage, SimpleTrailStepState>> {
    val stages = SimpleTrailStage.entries
    if (tone == StatusTone.CONNECTED) return stages.map { it to SimpleTrailStepState.DONE }
    val failStage = when {
        noInternet -> SimpleTrailStage.NETWORK
        allServersDead -> SimpleTrailStage.SERVER
        else -> null
    }
    val currentStage = failStage ?: simpleTrailStage(activityText) ?: return stages.map { it to SimpleTrailStepState.PENDING }
    val failIndex = stages.indexOf(failStage)
    val currentIndex = stages.indexOf(currentStage)
    return stages.mapIndexed { i, stage ->
        val state = when {
            failStage != null && i == failIndex -> SimpleTrailStepState.FAIL
            failStage != null -> if (i < failIndex) SimpleTrailStepState.DONE else SimpleTrailStepState.PENDING
            i < currentIndex -> SimpleTrailStepState.DONE
            i == currentIndex -> SimpleTrailStepState.CURRENT
            else -> SimpleTrailStepState.PENDING
        }
        stage to state
    }
}

/** Fixed green for completed stages (mockup: done dots are green regardless of tone). */
private val DONE_COLOR = Color(0xFF2E7D32)

/**
 * Always-visible step trail Сеть → Подписки → Сервер → VPN. The current step pulses with the
 * screen tone, failed steps are error red, done steps green, pending steps neutral gray.
 */
@Composable
internal fun SimpleStepTrail(
    steps: List<Pair<SimpleTrailStage, SimpleTrailStepState>>,
    toneColor: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "trail_pulse")
    val currentAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "trail_current_dot",
    )
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        steps.forEachIndexed { index, (stage, state) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(2.dp)
                                .background(
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                ),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(dotColor(state, toneColor, currentAlpha), CircleShape),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = trailLabel(stage),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (state == SimpleTrailStepState.CURRENT ||
                        state == SimpleTrailStepState.FAIL
                    ) {
                        FontWeight.Medium
                    } else {
                        FontWeight.Normal
                    },
                    color = labelColor(state, toneColor),
                )
            }
        }
    }
}

@Composable
private fun dotColor(state: SimpleTrailStepState, toneColor: Color, currentAlpha: Float): Color =
    when (state) {
        SimpleTrailStepState.DONE -> DONE_COLOR
        SimpleTrailStepState.CURRENT -> toneColor.copy(alpha = currentAlpha)
        SimpleTrailStepState.FAIL -> MaterialTheme.colorScheme.error
        SimpleTrailStepState.PENDING -> MaterialTheme.colorScheme.outlineVariant
    }

@Composable
private fun labelColor(state: SimpleTrailStepState, toneColor: Color): Color =
    when (state) {
        SimpleTrailStepState.DONE -> DONE_COLOR
        SimpleTrailStepState.CURRENT -> toneColor
        SimpleTrailStepState.FAIL -> MaterialTheme.colorScheme.error
        SimpleTrailStepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    }

@Composable
private fun trailLabel(stage: SimpleTrailStage): String = when (stage) {
    SimpleTrailStage.NETWORK -> stringResource(Res.string.simple_mode_trail_network)
    SimpleTrailStage.SUBS -> stringResource(Res.string.simple_mode_trail_subs)
    SimpleTrailStage.SERVER -> stringResource(Res.string.simple_mode_trail_server)
    SimpleTrailStage.VPN -> stringResource(Res.string.simple_mode_trail_vpn)
}
