package com.mira.screening.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders the small subset of Markdown that Gemma 4 commonly emits in chat
 * and narration responses, without pulling in a third-party Markdown library:
 *
 *   `**bold**`           rendered as a bold span
 *   `*italic*` / `_it_`  rendered as an italic span
 *   `# H1`, `## H2`, `### H3`   rendered as a bold paragraph at the right weight
 *   `- item` / `* item`  rendered as a bullet line with a real • prefix
 *   blank line           rendered as a vertical gap
 *
 * Streaming-safe: an unclosed delimiter mid-stream (`**foo` while the rest of
 * the bold span is still being generated) is treated as literal characters so
 * the user sees a stable, readable prefix until the closing `**` arrives, at
 * which point the span snaps to bold on the next recomposition.
 */
@Composable
fun MarkdownText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val lines = remember(text) { text.split("\n") }
    Column(modifier = modifier) {
        lines.forEach { rawLine ->
            val trimmed = rawLine.trimStart()
            when {
                trimmed.startsWith("### ") -> Text(
                    text = parseInline(trimmed.removePrefix("### ")),
                    style = style.copy(fontWeight = FontWeight.SemiBold),
                    color = color
                )
                trimmed.startsWith("## ") -> Text(
                    text = parseInline(trimmed.removePrefix("## ")),
                    style = style.copy(fontWeight = FontWeight.Bold),
                    color = color
                )
                trimmed.startsWith("# ") -> Text(
                    text = parseInline(trimmed.removePrefix("# ")),
                    style = style.copy(fontWeight = FontWeight.Bold),
                    color = color
                )
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Row {
                    Text(text = "•  ", style = style, color = color)
                    Text(
                        text = parseInline(trimmed.drop(2)),
                        style = style,
                        color = color
                    )
                }
                trimmed.isEmpty() -> Spacer(Modifier.height(6.dp))
                else -> Text(
                    text = parseInline(rawLine),
                    style = style,
                    color = color
                )
            }
        }
    }
}

/**
 * Parse a single line for inline `**bold**`, `*italic*`, `_italic_`. Any
 * delimiter without a matching close is emitted as a literal character so
 * mid-stream partial output stays readable.
 */
private fun parseInline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        when {
            // **bold**
            ch == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                val close = text.indexOf("**", startIndex = i + 2)
                if (close == -1) {
                    append(ch)
                    i++
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, close))
                    }
                    i = close + 2
                }
            }
            // *italic* or _italic_
            ch == '*' || ch == '_' -> {
                val close = text.indexOf(ch, startIndex = i + 1)
                if (close == -1 || close == i + 1) {
                    append(ch)
                    i++
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, close))
                    }
                    i = close + 1
                }
            }
            else -> {
                append(ch)
                i++
            }
        }
    }
}
