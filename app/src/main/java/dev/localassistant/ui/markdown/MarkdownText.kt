package dev.localassistant.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.localassistant.ui.theme.Spacing

/** Renders a reply written in Markdown with the app's typography. */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        for (block in blocks) {
            when (block) {
                is Markdown.Block.Paragraph ->
                    Text(annotate(block.inlines, codeBackground, linkColor), style = style, color = color)
                is Markdown.Block.Heading -> Text(
                    annotate(block.inlines, codeBackground, linkColor),
                    style = if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = color,
                )
                is Markdown.Block.ListItem -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                    Text(block.marker, style = style, color = color, modifier = Modifier.width(24.dp))
                    Text(annotate(block.inlines, codeBackground, linkColor), style = style, color = color)
                }
                is Markdown.Block.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        annotate(block.inlines, codeBackground, linkColor),
                        style = style.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.m),
                    )
                }
                is Markdown.Block.Code -> Surface(
                    color = codeBackground,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        block.text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(Spacing.m),
                    )
                }
            }
        }
    }
}

private fun annotate(inlines: List<Markdown.Inline>, codeBackground: Color, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        for (inline in inlines) {
            val style = SpanStyle(
                fontWeight = if (inline.bold) FontWeight.SemiBold else null,
                fontStyle = if (inline.italic) FontStyle.Italic else null,
                fontFamily = if (inline.code) FontFamily.Monospace else null,
                background = if (inline.code) codeBackground else Color.Unspecified,
            )
            val url = inline.url
            if (url != null) {
                val linkStyle = TextLinkStyles(style.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(inline.text) }
            } else {
                withStyle(style) { append(inline.text) }
            }
        }
    }
