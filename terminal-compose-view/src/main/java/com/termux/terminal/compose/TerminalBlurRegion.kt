package com.termux.terminal.compose

import androidx.compose.ui.geometry.Rect

/**
 * A rounded rectangle whose terminal backdrop the renderer blurs, so a host overlay only
 * needs to draw a translucent tint on top of it.
 *
 * [bounds] are in the terminal view's pixel coordinates with a top-left origin.
 */
data class TerminalBlurRegion(
    val bounds: Rect,
    val cornerRadiusPx: Float
)
