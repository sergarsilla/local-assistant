package dev.localassistant.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.localassistant.ui.theme.Spacing

/**
 * The title of a tab, with one line saying what the tab is for. Every screen opens the same way,
 * so the type scale tells the user where they are before they read anything.
 *
 * Horizontal padding is left to the caller, whose list already pads its content.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(top = Spacing.l, bottom = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A titled group of related rows. A screen built from a handful of these reads much faster than
 * the same rows in one list, because each card is a single subject the eye can skip over.
 */
@Composable
fun GroupCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = container) {
        // Rows appearing and disappearing inside a card move it; sliding beats jumping.
        Column(Modifier.padding(vertical = Spacing.m).animateContentSize()) {
            Row(
                Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                icon?.let {
                    Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** Splits a card into named parts, for a group that holds two lists of the same kind of thing. */
@Composable
fun CardSubheader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.m, bottom = Spacing.xs),
    )
}

/** A line of small print inside a card: a caveat, never anything the user has to act on. */
@Composable
fun CardNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = Spacing.l, end = Spacing.l, top = Spacing.s),
    )
}

/** Secondary detail: sizes, speeds, how long ago. */
@Composable
fun MetaText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * A progress value that slides to each new figure. Downloads report in jumps of several
 * megabytes, and a bar that teleports reads as a glitch rather than as movement.
 */
@Composable
fun animatedProgress(fraction: Float): () -> Float {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(PROGRESS_MILLIS),
        label = "progress",
    )
    return { animated }
}

private const val PROGRESS_MILLIS = 220
