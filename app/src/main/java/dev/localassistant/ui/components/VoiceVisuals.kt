package dev.localassistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.localassistant.ui.theme.AssistantIcons
import kotlin.math.PI
import kotlin.math.cos

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
        // The halo starts outside the inner disc, so it stays visible around a button drawn on top.
        drawCircle(color.copy(alpha = if (hearingSpeech) 0.30f else 0.18f), radius = radius * (0.74f + 0.26f * swell))
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

/** What the assistant is doing, in the terms the voice button can show. */
enum class VoiceMode { IDLE, LISTENING, WORKING, SPEAKING }

/**
 * The assistant's main button, which is also its status display: each [mode] has its own icon,
 * colour and motion, so the state reads at a glance without any text. The whole circle, halo
 * included, takes the tap.
 */
@Composable
fun VoiceOrb(
    mode: VoiceMode,
    level: Float,
    hearingSpeech: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 136.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = when (mode) {
            VoiceMode.IDLE, VoiceMode.LISTENING -> scheme.primary
            VoiceMode.WORKING -> scheme.tertiary
            VoiceMode.SPEAKING -> scheme.secondary
        },
        label = "orbColor",
    )
    val content = when (mode) {
        VoiceMode.IDLE, VoiceMode.LISTENING -> scheme.onPrimary
        VoiceMode.WORKING -> scheme.onTertiary
        VoiceMode.SPEAKING -> scheme.onSecondary
    }
    val swell = animateFloatAsState(
        targetValue = if (mode == VoiceMode.LISTENING) (level * LEVEL_GAIN).coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "level",
    )
    val phase = rememberLoopPhase(
        running = mode != VoiceMode.IDLE,
        durationMillis = when (mode) {
            VoiceMode.WORKING -> 1_100
            VoiceMode.SPEAKING -> 1_600
            else -> 1_800
        },
    )
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val outer = this.size.minDimension / 2
            val disc = outer * DISC_RATIO
            val band = outer - disc
            when (mode) {
                VoiceMode.IDLE -> Unit
                VoiceMode.LISTENING -> {
                    // Breathes while the room is quiet, so an open microphone never looks idle.
                    val breathing = 0.06f + 0.08f * (1f - cos(2f * PI.toFloat() * phase.value)) / 2f
                    val reach = maxOf(swell.value, breathing)
                    drawCircle(container.copy(alpha = if (hearingSpeech) 0.32f else 0.20f), radius = disc + band * reach)
                }
                VoiceMode.WORKING -> {
                    val radius = disc + band * 0.45f
                    val stroke = band * 0.18f
                    drawCircle(container.copy(alpha = 0.18f), radius = radius, style = Stroke(stroke))
                    drawArc(
                        color = container,
                        startAngle = 360f * phase.value - 90f,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                VoiceMode.SPEAKING -> repeat(RIPPLES) { index ->
                    val t = (phase.value + index.toFloat() / RIPPLES) % 1f
                    drawCircle(container.copy(alpha = 0.35f * (1f - t)), radius = disc + band * t)
                }
            }
            drawCircle(container, radius = disc)
        }
        Icon(mode.icon(), contentDescription = null, tint = content, modifier = Modifier.size(size * ICON_RATIO))
    }
}

/** A 0..1 loop driving the orb's motion; while it is off nothing animates or redraws. */
@Composable
private fun rememberLoopPhase(running: Boolean, durationMillis: Int): State<Float> {
    if (!running) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis, easing = LinearEasing)),
        label = "phase",
    )
}

/** A microphone while speaking is the next step, stop while there is work to cancel, a speaker while the reply plays. */
private fun VoiceMode.icon(): ImageVector = when (this) {
    VoiceMode.IDLE, VoiceMode.LISTENING -> AssistantIcons.Mic
    VoiceMode.WORKING -> AssistantIcons.Stop
    VoiceMode.SPEAKING -> AssistantIcons.VolumeUp
}

private const val LEVEL_GAIN = 7f
private const val DISC_RATIO = 0.7f
private const val ICON_RATIO = 0.28f
private const val RIPPLES = 2
