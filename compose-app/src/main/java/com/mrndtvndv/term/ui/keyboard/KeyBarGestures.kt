package com.mrndtvndv.term.ui.keyboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarItem
import kotlin.math.abs

/** Drag distance, in dp, that turns a press on a key into a swipe rather than a tap. */
private val KeySwipeThreshold = 24.dp

/** How far below the threshold a drag must fall before a new arming may tick again. */
private val KeyHapticHysteresis = 6.dp

/** Minimum gap between ticks for a retarget while armed, so quick direction changes do not buzz continuously. */
private const val RetargetTickIntervalMillis = 250L

internal enum class KeySwipe { Up, Down, Left, Right }

/** The dominant axis of a drag, in screen coordinates. A tie goes horizontal. */
private fun Offset.dominantSwipe(): KeySwipe =
    if (abs(x) >= abs(y)) {
        if (x < 0f) KeySwipe.Left else KeySwipe.Right
    } else {
        if (y < 0f) KeySwipe.Up else KeySwipe.Down
    }

internal fun KeyBarItem.swipeAction(swipe: KeySwipe): KeyBarAction? = when (swipe) {
    KeySwipe.Up -> swipeUp
    KeySwipe.Down -> swipeDown
    KeySwipe.Left -> swipeLeft
    KeySwipe.Right -> swipeRight
}

/** How a press on a key ended. Only [Tap] and [Swipe] execute anything. */
private sealed interface KeyPressEnd {
    data object Cancelled : KeyPressEnd

    data object Tap : KeyPressEnd

    data class Swipe(val direction: KeySwipe) : KeyPressEnd
}

/**
 * Reports a claimed drag's target to the preview, and ticks the haptic.
 *
 * The visible state uses the release rule exactly: a target is armed when its heading is assigned and the drag
 * has reached [thresholdPx]. So the chip never shows armed for a release that would do nothing. [report] gets the
 * target and armed state, and only when either changes.
 *
 * The haptic uses a latch with hysteresis. It arms at the threshold and releases only after the drag falls
 * [hysteresisPx] below it. Jitter around the threshold therefore ticks once, not on every crossing. A retarget
 * while armed also ticks, but no more than once per [RetargetTickIntervalMillis].
 */
internal class KeyDragFeedback(
    private val thresholdPx: Float,
    private val hysteresisPx: Float,
    private val report: (target: KeySwipe?, armed: Boolean) -> Unit,
    private val tick: () -> Unit,
) {
    private var target: KeySwipe? = null
    private var armed = false
    private var latched = false
    private var lastTickMillis: Long? = null

    fun update(heading: KeySwipe?, distance: Float, nowMillis: Long) {
        if (distance < thresholdPx - hysteresisPx) latched = false
        val nowArmed = heading != null && distance >= thresholdPx
        val armedTransition = nowArmed && !latched
        val retarget = nowArmed && latched && heading != target
        if (armedTransition) latched = true
        if (heading == target && nowArmed == armed) return
        target = heading
        armed = nowArmed
        report(heading, nowArmed)
        if (armedTransition || (retarget && tickDue(nowMillis))) {
            lastTickMillis = nowMillis
            tick()
        }
    }

    fun clear() {
        if (target == null) return
        target = null
        armed = false
        report(null, false)
    }

    private fun tickDue(nowMillis: Long): Boolean =
        lastTickMillis?.let { nowMillis - it >= RetargetTickIntervalMillis } ?: true
}

/**
 * Follows one press to its end on the Initial pass. Initial runs parent-first, before the strip's scroll reads
 * its drag on the Main pass, so a claim here is seen by the scroll as already consumed. A drag past touch slop
 * is claimed only when its heading has an assigned action. A claimed press consumes its events, so the scroll
 * never starts under it. An unassigned heading ends the press as cancelled at slop and leaves the drag to the
 * scroll. If another handler consumed the press first, this press cancels, so a swipe and a scroll never both
 * fire. A claimed press swipes on release when the drag passed [thresholdPx] along an assigned direction. A
 * second finger, or losing the pointer, cancels. While the press is claimed and held, [feedback] reports the
 * target. Feedback never fires an action.
 */
private suspend fun AwaitPointerEventScope.awaitKeyPress(
    down: PointerInputChange,
    touchSlop: Float,
    thresholdPx: Float,
    assigned: Set<KeySwipe>,
    feedback: KeyDragFeedback,
): KeyPressEnd {
    var claimed = false
    var pressing = true
    var position = down.position
    while (pressing) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id }
        val secondFinger = event.changes.any { it.id != down.id && it.pressed && !it.previousPressed }
        val preempted = !claimed && change?.isConsumed == true
        if (change == null || secondFinger || preempted) return KeyPressEnd.Cancelled
        position = change.position
        val drag = position - down.position
        if (!claimed && drag.getDistance() > touchSlop) {
            if (drag.dominantSwipe() !in assigned) return KeyPressEnd.Cancelled
            claimed = true
        }
        if (claimed) {
            change.consume()
            if (change.pressed) {
                val heading = drag.dominantSwipe().takeIf { it in assigned }
                feedback.update(heading, drag.getDistance(), change.uptimeMillis)
            }
        }
        pressing = change.pressed
    }
    val drag = position - down.position
    val swipe = drag.dominantSwipe()
    return when {
        !claimed -> KeyPressEnd.Tap
        drag.getDistance() < thresholdPx || swipe !in assigned -> KeyPressEnd.Cancelled
        else -> KeyPressEnd.Swipe(swipe)
    }
}

/**
 * Tap, swipe and drag feedback for one key. A press that never leaves touch slop fires [onTap] on release. A press
 * past touch slop never taps. It fires [onSwipe] with the dominant direction only when that heading is assigned and
 * the drag passes [KeySwipeThreshold]. A drag between touch slop and the threshold fires nothing, on assigned keys
 * and unassigned ones alike. [onDragTarget] reports the claimed drag's target and whether it is armed, and it is
 * cleared before the release action runs.
 */
@Composable
internal fun Modifier.keySwipeGestures(
    interactionSource: MutableInteractionSource,
    assigned: Set<KeySwipe>,
    onTap: () -> Unit,
    onSwipe: (KeySwipe) -> Unit,
    onDragTarget: (target: KeySwipe?, armed: Boolean) -> Unit,
): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentOnDragTarget by rememberUpdatedState(onDragTarget)
    val haptics = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val thresholdPx = with(LocalDensity.current) { KeySwipeThreshold.toPx() }
    val hysteresisPx = with(LocalDensity.current) { KeyHapticHysteresis.toPx() }
    return pointerInput(interactionSource, assigned, touchSlop, thresholdPx, hysteresisPx) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val press = PressInteraction.Press(down.position)
            interactionSource.tryEmit(press)
            val feedback = KeyDragFeedback(
                thresholdPx = thresholdPx,
                hysteresisPx = hysteresisPx,
                report = { target, armed -> currentOnDragTarget(target, armed) },
                tick = { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
            )
            val end = awaitKeyPress(down, touchSlop, thresholdPx, assigned, feedback)
            feedback.clear()
            interactionSource.tryEmit(
                if (end == KeyPressEnd.Cancelled) PressInteraction.Cancel(press) else PressInteraction.Release(press),
            )
            when (end) {
                KeyPressEnd.Cancelled -> Unit
                KeyPressEnd.Tap -> currentOnTap()
                is KeyPressEnd.Swipe -> currentOnSwipe(end.direction)
            }
        }
    }
}
