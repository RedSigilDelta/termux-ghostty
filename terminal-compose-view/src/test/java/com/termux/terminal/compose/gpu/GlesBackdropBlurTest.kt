package com.termux.terminal.compose.gpu

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlesBackdropBlurTest {
    @Test
    fun `region inside the surface flips y to the GL bottom origin`() {
        val pixels = blurRegionPixels(Rect(10f, 20f, 40f, 50f), 4f, 100, 80)!!

        assertEquals(10, pixels.x)
        assertEquals(30, pixels.yGl)
        assertEquals(30, pixels.width)
        assertEquals(30, pixels.height)
        assertEquals(4f, pixels.cornerRadiusPx, 0f)
    }

    @Test
    fun `region overflowing the surface is clamped to its visible part`() {
        val pixels = blurRegionPixels(Rect(-10f, 60f, 120f, 100f), 4f, 100, 80)!!

        assertEquals(0, pixels.x)
        assertEquals(0, pixels.yGl)
        assertEquals(100, pixels.width)
        assertEquals(20, pixels.height)
    }

    @Test
    fun `regions narrower than two pixels or outside the surface are skipped`() {
        assertNull(blurRegionPixels(Rect(10f, 10f, 11f, 50f), 4f, 100, 80))
        assertNull(blurRegionPixels(Rect(10f, 10f, 50f, 11.5f), 4f, 100, 80))
        assertNull(blurRegionPixels(Rect(200f, 0f, 300f, 10f), 4f, 100, 80))
    }

    @Test
    fun `corner radius is clamped to half the smaller edge and at least zero`() {
        val large = blurRegionPixels(Rect(0f, 0f, 20f, 10f), 50f, 100, 80)!!
        assertEquals(5f, large.cornerRadiusPx, 0f)

        val negative = blurRegionPixels(Rect(0f, 0f, 20f, 10f), -3f, 100, 80)!!
        assertEquals(0f, negative.cornerRadiusPx, 0f)
    }
}
