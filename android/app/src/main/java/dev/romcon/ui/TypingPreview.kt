package dev.romcon.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.romcon.input.TypedLine

/**
 * Shows what the phone has typed on the PC, right above the keyboard, so a typo can be spotted without looking up.
 * It is a mirror, not a text field: the keyboard keeps typing straight to the PC.
 */
@Composable
fun TypingPreview(line: TypedLine, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    val caret = MaterialTheme.colorScheme.primary
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // A landscape phone has almost no room left above the keyboard.
    val lines = if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) 1 else 3
    val maxHeight = with(LocalDensity.current) { style.lineHeight.toDp() } * lines

    // Long text wraps and scrolls; keep the line with the caret in view.
    LaunchedEffect(layout, line.cursor) {
        val rect = layout?.caretRect(line.cursor) ?: return@LaunchedEffect
        when {
            rect.top < scroll.value -> scroll.scrollTo(rect.top.toInt())
            rect.bottom > scroll.value + scroll.viewportSize -> scroll.scrollTo((rect.bottom - scroll.viewportSize).toInt() + 1)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .heightIn(max = maxHeight)
                    .verticalScroll(scroll),
            ) {
                if (line.text.isEmpty()) {
                    Text("What you type shows here", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    line.text,
                    style = style,
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Typed on the PC: ${line.text}" }
                        .drawWithContent {
                            drawContent()
                            val rect = layout?.caretRect(line.cursor) ?: return@drawWithContent
                            val width = 2.dp.toPx()
                            val x = rect.left.coerceIn(width / 2, (size.width - width / 2).coerceAtLeast(width / 2))
                            drawLine(caret, Offset(x, rect.top), Offset(x, rect.bottom), width)
                        },
                )
            }
            IconButton(onClick = onClear, enabled = line.text.isNotEmpty()) {
                Icon(Icons.Filled.Close, contentDescription = "Clear the box")
            }
        }
    }
}

/** Where the caret sits; the layout can be one keystroke behind the text. */
private fun TextLayoutResult.caretRect(cursor: Int) = getCursorRect(cursor.coerceIn(0, layoutInput.text.length))
