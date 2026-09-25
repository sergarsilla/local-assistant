package dev.localassistant.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A ring that swells with the microphone level: it tells the user at a glance that the phone
 * hears them, and how loud they are, without reading anything.
 */
@Composable
fun VoiceLevelRing(
    level: Float,
    hearingSpeech: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    // Speech RMS rarely exceeds ~0.15, so it is scaled up to use the whole range.
    val target = (level * LEVEL_GAIN).coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "level",
    )
    val breathing = rememberInfiniteTransition(label = "breathing")
    val idle by breathing.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "idle",
    )
    Canvas(modifier.size(size)) {
        val radius = this.size.minDimension / 2
        val swell = maxOf(animated, idle)
        drawCircle(color.copy(alpha = if (hearingSpeech) 0.28f else 0.16f), radius = radius * (0.55f + 0.45f * swell))
        drawCircle(color.copy(alpha = 0.9f), radius = radius * 0.42f)
    }
}

/** Three dots that pulse in turn while the first words of a reply are on their way. */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 450, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Canvas(Modifier.size(8.dp).alpha(alpha)) { drawCircle(color) }
        }
    }
}

private const val LEVEL_GAIN = 7f
