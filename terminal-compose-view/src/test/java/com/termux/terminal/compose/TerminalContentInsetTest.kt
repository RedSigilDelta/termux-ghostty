package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalContentInsetTest {
    @Test
    fun `zero insets leave the viewport height unchanged`() {
        assertEquals(200, contentViewportHeight(200, 0, 0))
    }

    @Test
    fun `bottom inset is subtracted from the viewport height`() {
        assertEquals(150, contentViewportHeight(200, 0, 50))
    }

    @Test
    fun `top inset is subtracted from the viewport height`() {
        assertEquals(170, contentViewportHeight(200, 30, 0))
    }

    @Test
    fun `top and bottom insets are both subtracted`() {
        assertEquals(120, contentViewportHeight(200, 30, 50))
    }

    @Test
    fun `negative insets are ignored`() {
        assertEquals(200, contentViewportHeight(200, -30, 0))
        assertEquals(200, contentViewportHeight(200, 0, -30))
    }

    @Test
    fun `insets larger than the viewport yield zero height`() {
        assertEquals(0, contentViewportHeight(200, 0, 250))
        assertEquals(0, contentViewportHeight(200, 150, 100))
    }
}
