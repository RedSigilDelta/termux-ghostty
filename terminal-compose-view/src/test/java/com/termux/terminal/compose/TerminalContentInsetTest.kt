package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalContentInsetTest {
    @Test
    fun `zero inset leaves the viewport height unchanged`() {
        assertEquals(200, contentViewportHeight(200, 0))
    }

    @Test
    fun `inset is subtracted from the viewport height`() {
        assertEquals(150, contentViewportHeight(200, 50))
    }

    @Test
    fun `negative inset is ignored`() {
        assertEquals(200, contentViewportHeight(200, -30))
    }

    @Test
    fun `inset larger than the viewport yields zero height`() {
        assertEquals(0, contentViewportHeight(200, 250))
    }
}
