package com.mrndtvndv.term.ui.workspace

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalPaneTest {
    private val canvas = Rect(0f, 0f, 400f, 800f)

    @Test
    fun defaultKeyBarReservesRowsAboveBar() {
        assertEquals(66, keyBarInsetPx(canvas, 740f, 6f, overlay = false))
    }

    @Test
    fun overlayKeyBarKeepsRowsBehindBar() {
        assertEquals(0, keyBarInsetPx(canvas, 740f, 6f, overlay = true))
    }

    @Test
    fun unmeasuredBarDoesNotReserveRows() {
        assertEquals(0, keyBarInsetPx(canvas, 0f, 6f, overlay = false))
    }
}
