package com.mrndtvndv.term.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.MacroKeyCatalog
import com.mrndtvndv.term.data.prefs.MacroStep
import com.mrndtvndv.term.ui.components.tintedGlassPill
import kotlin.math.roundToInt

/** The one drag target on screen: its direction, the action's compact label, and whether release fires it. */
internal data class KeySwipePreview(
    val owner: String,
    val anchor: Rect,
    val swipe: KeySwipe,
    val label: String,
    val armed: Boolean,
)

/**
 * Holds the preview on screen. The key bar owns it, so the chip sits outside the scrolling strip and the strip's
 * clipping never cuts it off. [barBounds] and [stripBounds] are window rects, read when a preview is shown or laid
 * out. [stripBounds] is the visible viewport of the scrolling strip.
 */
internal class KeySwipePreviewHost {
    var target: KeySwipePreview? by mutableStateOf(null)
        private set

    var barBounds: Rect = Rect.Zero
    var stripBounds: Rect = Rect.Zero

    fun show(preview: KeySwipePreview) {
        target = preview
    }

    /**
     * The part of a key the strip is showing, or null when none of it is on screen. A key scrolled out of view gets
     * no chip, so the chip never points at nothing.
     */
    fun visibleAnchor(keyBounds: Rect): Rect? = keyBounds.intersect(stripBounds).takeUnless { it.isEmpty }

    /** Clears only when [owner] still holds the preview, so a key that is no longer active cannot wipe another's. */
    fun clear(owner: String) {
        if (target?.owner == owner) target = null
    }
}

internal val LocalKeySwipePreview = staticCompositionLocalOf<KeySwipePreviewHost?> { null }

private val PreviewGap = 8.dp
private val PreviewScreenMargin = 8.dp
private val PreviewHeight = 32.dp
private const val NotReadyAlpha = 0.72f
private const val PreviewTextLength = 14
private const val PreviewMacroLength = 22
private const val PreviewMacroStepLength = 10

/**
 * The chip above the key being dragged. It has no pointer input, so the drag stays on the key, and it clears its
 * semantics because TalkBack already has the same actions. It is clamped inside the window on both axes, and inside
 * the system bars and display cutouts, so it is never cut off. The key bar sits above the IME, so a chip above the
 * key stays above the IME too.
 */
@Composable
internal fun KeySwipePreviewOverlay(preview: KeySwipePreview, host: KeySwipePreviewHost) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    val gapPx = with(density) { PreviewGap.toPx() }
    val marginPx = with(density) { PreviewScreenMargin.toPx() }
    val safe = WindowInsets.safeDrawing
    val minLeft = safe.getLeft(density, layoutDirection) + marginPx
    val maxRight = windowSize.width - safe.getRight(density, layoutDirection) - marginPx
    val minTop = safe.getTop(density) + marginPx
    Layout(content = { KeySwipeChip(preview) }) { measurables, _ ->
        val chip = measurables.first().measure(
            Constraints(maxWidth = (maxRight - minLeft).roundToInt().coerceAtLeast(1)),
        )
        val maxLeft = maxOf(minLeft, maxRight - chip.width)
        val left = (preview.anchor.center.x - chip.width / 2f).coerceIn(minLeft, maxLeft)
        val top = maxOf(minTop, preview.anchor.top - gapPx - chip.height)
        val bar = host.barBounds
        layout(0, 0) {
            chip.place((left - bar.left).roundToInt(), (top - bar.top).roundToInt())
        }
    }
}

/**
 * Armed chips use the primary container, and not-ready chips are faded. Both are the glass pill, so the chip keeps
 * the pill's look.
 */
@Composable
private fun KeySwipeChip(preview: KeySwipePreview) {
    val colors = MaterialTheme.colorScheme
    val textColor = if (preview.armed) colors.onPrimaryContainer else colors.onSurface
    val fill = if (preview.armed) colors.primaryContainer.copy(alpha = 0.9f) else Color.Transparent
    Row(
        modifier = Modifier
            .clearAndSetSemantics {}
            .alpha(if (preview.armed) 1f else NotReadyAlpha)
            .tintedGlassPill(CircleShape)
            .background(fill, CircleShape)
            .height(PreviewHeight)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = preview.swipe.glyph, style = MaterialTheme.typography.labelLarge, color = textColor)
        Text(
            text = preview.label,
            style = MaterialTheme.typography.labelLarge,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal val KeySwipe.glyph: String
    get() = when (this) {
        KeySwipe.Up -> "↑"
        KeySwipe.Down -> "↓"
        KeySwipe.Left -> "←"
        KeySwipe.Right -> "→"
    }

/** A compact summary of [action] for the chip: a key's label, the start of a text, or a macro's steps. */
internal fun KeyBarAction.previewLabel(): String = when (this) {
    is KeyBarAction.Key -> keyLabel(keyCode)
    is KeyBarAction.Text -> quoted(text, PreviewTextLength)
    is KeyBarAction.Macro -> steps.joinToString(" ") { it.previewLabel() }.truncated(PreviewMacroLength)
    KeyBarAction.Ctrl -> "Ctrl"
    KeyBarAction.Alt -> "Alt"
    KeyBarAction.Joystick -> "Arrows"
}

private fun MacroStep.previewLabel(): String = when (this) {
    is MacroStep.Text -> quoted(text, PreviewMacroStepLength)
    is MacroStep.Key -> {
        val modifiers = listOfNotNull("Ctrl".takeIf { ctrl }, "Alt".takeIf { alt }, "Shift".takeIf { shift })
        (modifiers + keyLabel(keyCode)).joinToString("+")
    }
}

private fun keyLabel(keyCode: Int): String = MacroKeyCatalog.firstOrNull { it.keyCode == keyCode }?.label ?: "Key"

/** Make whitespace visible instead of displaying an empty label for a whitespace-only macro. */
private fun quoted(text: String, maxLength: Int): String =
    "“" + text.replace(" ", "␣").replace("\t", "⇥").replace("\r\n", "↵")
        .replace("\n", "↵").replace("\r", "↵").truncated(maxLength) + "”"

private fun String.truncated(maxLength: Int): String = if (length > maxLength) take(maxLength) + "…" else this
