package com.termux.terminal.compose.gpu

import androidx.compose.ui.geometry.Rect
import com.termux.terminal.compose.TerminalBlurRegion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlesPresentationTest {
    @Test
    fun identicalCallbackIsClassifiedRedundantButResetStillRequiresPresentation() {
        val snapshot = testSnapshot(1L)

        val redundant = glesPresentationDecision(
                snapshot = snapshot,
                presentedSnapshot = snapshot,
                animationTime = 2f,
                lastAnimationTime = 2f,
                atlasReset = false
            )
        assertTrue(redundant.redundant)
        assertTrue(redundant.requiresCompleteFramebuffer)

        val reset = glesPresentationDecision(
                snapshot = snapshot,
                presentedSnapshot = snapshot,
                animationTime = 2f,
                lastAnimationTime = 2f,
                atlasReset = true
            )
        assertFalse(reset.redundant)
        assertTrue(reset.requiresCompleteFramebuffer)
    }

    @Test
    fun visualOffsetChangeIsNotRedundant() {
        val settled = testSnapshot(1L)
        val moved = settled.copy(visualOffsetPx = 4f)

        assertFalse(
            glesPresentationDecision(
                snapshot = moved,
                presentedSnapshot = settled,
                animationTime = 1f,
                lastAnimationTime = 1f,
                atlasReset = false
            ).redundant
        )
    }

    @Test
    fun contentOffsetChangeIsNotRedundant() {
        val settled = testSnapshot(1L)
        val moved = settled.copy(contentOffsetYPx = 12f)

        assertFalse(
            glesPresentationDecision(
                snapshot = moved,
                presentedSnapshot = settled,
                animationTime = 1f,
                lastAnimationTime = 1f,
                atlasReset = false
            ).redundant
        )
    }

    @Test
    fun blurRegionChangeIsNotRedundant() {
        val settled = testSnapshot(1L)
        val blurred = settled.copy(
            visual = settled.visual.copy(
                blurRegions = listOf(TerminalBlurRegion(Rect(0f, 0f, 10f, 10f), 4f))
            )
        )

        assertFalse(
            glesPresentationDecision(
                snapshot = blurred,
                presentedSnapshot = settled,
                animationTime = 1f,
                lastAnimationTime = 1f,
                atlasReset = false
            ).redundant
        )
    }

    @Test
    fun newSnapshotOrAnimationTickIsNotRedundant() {
        val first = testSnapshot(1L)
        val second = testSnapshot(2L)

        assertFalse(
            glesPresentationDecision(
                snapshot = second,
                presentedSnapshot = first,
                animationTime = 1f,
                lastAnimationTime = 1f,
                atlasReset = false
            ).redundant
        )
        assertFalse(
            glesPresentationDecision(
                snapshot = first,
                presentedSnapshot = first,
                animationTime = 2f,
                lastAnimationTime = 1f,
                atlasReset = false
            ).redundant
        )
    }
}
