package com.termux.terminal.compose

import com.termux.terminal.compose.gpu.testPalette
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalThumbnailTest {

    @Test
    fun blankFrameStartsAtTopRow() {
        assertEquals(0, thumbnailLastRow(frame(listOf("", "", ""), cursorRow = 0)))
    }

    @Test
    fun lastRowIsLastRowWithText() {
        val frame = frame(listOf("", "", "", "text"), cursorRow = 1)

        assertEquals(3, thumbnailLastRow(frame))
    }

    @Test
    fun cursorBelowTextExtendsRows() {
        val frame = frame(listOf("", "", "text", "", "", ""), cursorRow = 5)

        assertEquals(5, thumbnailLastRow(frame))
    }

    @Test
    fun lastRowIsClampedToViewport() {
        val frame = frame(listOf("", "", "", ""), cursorRow = 9)

        assertEquals(3, thumbnailLastRow(frame))
    }

    @Test
    fun cursorRowIsConvertedToViewportRow() {
        val frame = frame(listOf("", "", "", ""), cursorRow = 1, topRow = -2)

        assertEquals(3, thumbnailLastRow(frame))
    }

    private fun frame(
        rows: List<String>,
        cursorRow: Int,
        topRow: Int = 0
    ): TerminalFrame = TerminalFrame(
        sequence = 1L,
        viewport = TerminalViewport(topRow = topRow, rows = rows.size, columns = Columns, transcriptRows = 0),
        cursor = TerminalCursor(column = 0, row = cursorRow, visible = true, style = TerminalCursor.STYLE_BLOCK),
        modes = TerminalModes(false, false, false, false, false),
        palette = testPalette(),
        rows = rows.map(::row),
        linkLayout = null
    )

    private fun row(text: String): TerminalRow {
        val chars = CharArray(Columns)
        text.toCharArray().copyInto(chars)
        return TerminalRow(
            columns = Columns,
            text = chars,
            charsUsed = text.length,
            styles = LongArray(Columns),
            contentHash = 0L,
            cellLayout = null,
            isLineWrap = false
        )
    }

    private companion object {
        const val Columns = 10
    }
}
