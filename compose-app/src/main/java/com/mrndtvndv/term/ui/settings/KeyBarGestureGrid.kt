package com.mrndtvndv.term.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.ui.keyboard.KeySwipe
import com.mrndtvndv.term.ui.keyboard.KeySwipeThreshold
import com.mrndtvndv.term.ui.keyboard.dominantSwipe

private data class GestureGridState(
    val draft: KeyBarItemDraft,
    val swipeable: Boolean,
    val previewDirection: KeyBarDirection?,
    val thresholdPx: Float,
)

private data class GestureGridCallbacks(
    val onOpenPicker: (KeyBarEditorSlot) -> Unit,
    val onTryDirection: (KeySwipe?) -> Unit,
)

private data class GestureSlotState(
    val slot: KeyBarEditorSlot,
    val action: KeyBarAction?,
    val height: Dp,
    val enabled: Boolean,
    val highlighted: Boolean,
    val disabled: Boolean,
)

@Composable
internal fun GestureGrid(
    draft: KeyBarItemDraft,
    swipeable: Boolean,
    previewDirection: KeyBarDirection?,
    onOpenPicker: (KeyBarEditorSlot) -> Unit,
    onTryDirection: (KeySwipe?) -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { KeySwipeThreshold.toPx() }
    val state = GestureGridState(draft, swipeable, previewDirection, thresholdPx)
    val callbacks = GestureGridCallbacks(onOpenPicker, onTryDirection)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GestureGridRow(84.dp, listOf(null, KeyBarDirection.Up, null), centerTap = false, state, callbacks)
        GestureGridRow(
            104.dp,
            listOf(KeyBarDirection.Left, null, KeyBarDirection.Right),
            centerTap = true,
            state,
            callbacks,
        )
        GestureGridRow(84.dp, listOf(null, KeyBarDirection.Down, null), centerTap = false, state, callbacks)
    }
}

@Composable
private fun GestureGridRow(
    height: Dp,
    slots: List<KeyBarDirection?>,
    centerTap: Boolean,
    state: GestureGridState,
    callbacks: GestureGridCallbacks,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        slots.forEachIndexed { index, direction ->
            Box(modifier = Modifier.weight(1f)) {
                when (direction) {
                    null -> if (centerTap && index == 1) {
                        GestureSlot(
                            state = GestureSlotState(
                                slot = KeyBarEditorSlot.Tap,
                                action = state.draft.tap,
                                height = height,
                                enabled = true,
                                highlighted = false,
                                disabled = false,
                            ),
                            onClick = { callbacks.onOpenPicker(KeyBarEditorSlot.Tap) },
                            modifier = Modifier.tryItDrag(
                                enabled = state.swipeable,
                                thresholdPx = state.thresholdPx,
                                onTryDirection = callbacks.onTryDirection,
                            ),
                        )
                    } else {
                        Spacer(modifier = Modifier.fillMaxWidth().height(height))
                    }
                    else -> GestureSlot(
                        state = GestureSlotState(
                            slot = KeyBarEditorSlot.Swipe(direction),
                            action = state.draft.swipes[direction],
                            height = height,
                            enabled = state.swipeable,
                            highlighted = state.previewDirection == direction,
                            disabled = !state.swipeable,
                        ),
                        onClick = { callbacks.onOpenPicker(KeyBarEditorSlot.Swipe(direction)) },
                    )
                }
            }
        }
    }
}

private fun Modifier.tryItDrag(
    enabled: Boolean,
    thresholdPx: Float,
    onTryDirection: (KeySwipe?) -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(thresholdPx) {
        var dragOffset = Offset.Zero
        detectDragGestures(
            onDragStart = {
                dragOffset = Offset.Zero
                onTryDirection(null)
            },
            onDragEnd = { onTryDirection(null) },
            onDragCancel = { onTryDirection(null) },
            onDrag = { change, dragAmount ->
                change.consume()
                dragOffset += dragAmount
                onTryDirection(
                    dragOffset.dominantSwipe().takeIf { dragOffset.getDistance() >= thresholdPx },
                )
            },
        )
    }
}

@Composable
private fun GestureSlot(
    state: GestureSlotState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTap = state.slot == KeyBarEditorSlot.Tap
    val shape = RoundedCornerShape(16.dp)
    val description = state.description()
    Surface(
        onClick = onClick,
        enabled = state.enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(state.height)
            .then(
                if (state.highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier,
            )
            .then(if (state.action == null && !state.disabled && !isTap) Modifier.dashedOutline() else Modifier)
            .semantics { contentDescription = description },
        shape = shape,
        color = state.containerColor(isTap),
        contentColor = state.contentColor(isTap),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = state.slot.caption(),
                style = MaterialTheme.typography.labelSmall,
                color = if (isTap) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
            Text(
                text = state.actionLabel(),
                style = if (isTap) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
                fontFamily = if (state.action == null || state.disabled) null else FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun GestureSlotState.description(): String = when {
    disabled -> "${slot.accessibilityName()}: unavailable. ${slot.disabledReason()}"
    action == null -> "${slot.accessibilityName()}: not set. Add"
    else -> "${slot.accessibilityName()}: ${action.summary()}. Change"
}

private fun GestureSlotState.actionLabel(): String = when {
    disabled -> "—"
    action == null -> "+ Add"
    else -> action.summary()
}

@Composable
private fun GestureSlotState.containerColor(isTap: Boolean) = when {
    isTap -> MaterialTheme.colorScheme.primaryContainer
    disabled -> MaterialTheme.colorScheme.surfaceContainerLow
    else -> MaterialTheme.colorScheme.surfaceContainerHigh
}

@Composable
private fun GestureSlotState.contentColor(isTap: Boolean) = when {
    isTap -> MaterialTheme.colorScheme.onPrimaryContainer
    disabled -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun Modifier.dashedOutline(): Modifier {
    val outline = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        val strokeWidth = 1.5.dp.toPx()
        drawRoundRect(
            color = outline,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            ),
        )
    }
}

internal fun KeyBarEditorSlot.actionFrom(draft: KeyBarItemDraft): KeyBarAction? = when (this) {
    KeyBarEditorSlot.Tap -> draft.tap
    is KeyBarEditorSlot.Swipe -> draft.swipes[direction]
}

private fun KeyBarEditorSlot.caption(): String = when (this) {
    KeyBarEditorSlot.Tap -> "tap"
    is KeyBarEditorSlot.Swipe -> when (direction) {
        KeyBarDirection.Up -> "↑ swipe up"
        KeyBarDirection.Left -> "← left"
        KeyBarDirection.Right -> "→ right"
        KeyBarDirection.Down -> "↓ down"
    }
}

private fun KeyBarEditorSlot.accessibilityName(): String = when (this) {
    KeyBarEditorSlot.Tap -> "Tap"
    is KeyBarEditorSlot.Swipe -> "Swipe ${direction.name.lowercase()}"
}

private fun KeyBarEditorSlot.disabledReason(): String = when (this) {
    KeyBarEditorSlot.Tap -> ""
    is KeyBarEditorSlot.Swipe -> "This tap action can't have swipe gestures."
}

internal fun KeySwipe.toDirection(): KeyBarDirection = when (this) {
    KeySwipe.Up -> KeyBarDirection.Up
    KeySwipe.Down -> KeyBarDirection.Down
    KeySwipe.Left -> KeyBarDirection.Left
    KeySwipe.Right -> KeyBarDirection.Right
}
