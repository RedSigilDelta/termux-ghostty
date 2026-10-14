package com.termux.terminal.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.termux.terminal.compose.internal.resolveEffectiveColors

/** Small read-only rendering of [frame]: the bottom of the screen content as tiny coloured monospace text. */
@Composable
fun TerminalThumbnail(frame: TerminalFrame?, modifier: Modifier = Modifier, fontSize: TextUnit = 6.sp) {
    if (frame == null) {
        Box(modifier.background(Color.Black))
        return
    }
    val background = Color(frame.palette.color(defaultBackgroundIndex(frame)))
    val lines = remember(frame) {
        frame.rows.take(thumbnailLastRow(frame) + 1).map { row -> row.toThumbnailText(frame) }
    }
    Box(modifier.background(background).clipToBounds()) {
        // Unbounded so rows that don't fit are cut at the top, not squeezed out at the bottom.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .wrapContentHeight(Alignment.Bottom, unbounded = true)
        ) {
            for (line in lines) {
                BasicText(
                    text = line,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize,
                        lineHeight = fontSize * 1.15f
                    ),
                    softWrap = false,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

/**
 * Last viewport row to draw: the cursor row or the last row with text, whichever is lower.
 * The cursor row is absolute, so it is converted to a viewport row first.
 */
internal fun thumbnailLastRow(frame: TerminalFrame): Int {
    val lastTextRow = frame.rows.indexOfLast { it.textString().isNotBlank() }
    return maxOf(frame.viewportRow(frame.cursor.row), lastTextRow)
        .coerceAtMost(frame.rowsVisible - 1)
        .coerceAtLeast(0)
}

/** Palette index of the colour behind cells without an explicit background. Reverse video swaps it. */
private fun defaultBackgroundIndex(frame: TerminalFrame): Int =
    if (frame.reverseVideo) TerminalPalette.COLOR_INDEX_FOREGROUND else TerminalPalette.COLOR_INDEX_BACKGROUND

private fun TerminalRow.toThumbnailText(frame: TerminalFrame): AnnotatedString {
    val end = (columns - 1 downTo 0).firstOrNull { cellTextRange(it) != null } ?: return AnnotatedString("")
    val chars = text()
    val defaultBackground = frame.palette.color(defaultBackgroundIndex(frame))
    return buildAnnotatedString {
        var inRun = false
        var runColors = 0L
        for (column in 0..end) {
            if (cellDisplayWidth(column) == 0) continue
            val colors = resolveEffectiveColors(frame.palette, style(column), frame.reverseVideo)
            if (!inRun || colors != runColors) {
                if (inRun) pop()
                pushStyle(thumbnailSpanStyle(colors, defaultBackground))
                runColors = colors
                inRun = true
            }
            val range = cellTextRange(column)
            if (range == null) append(' ') else append(String(chars, range.first, range.count()))
        }
        if (inRun) pop()
    }
}

/** Unpacks the colours from [resolveEffectiveColors]: foreground in the high 32 bits, background in the low 32. */
private fun thumbnailSpanStyle(colors: Long, defaultBackground: Int): SpanStyle {
    val background = colors.toInt()
    return SpanStyle(
        color = Color((colors ushr 32).toInt()),
        background = if (background == defaultBackground) Color.Unspecified else Color(background)
    )
}
