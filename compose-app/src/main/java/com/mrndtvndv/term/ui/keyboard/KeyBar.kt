package com.mrndtvndv.term.ui.keyboard

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.ArrowJoystickMode
import com.mrndtvndv.term.ui.components.tintedGlassPill
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class KeyModifierState { OFF, ARMED, LOCKED }

class ModifierKeys {
    var ctrl by mutableStateOf(KeyModifierState.OFF)
        private set
    var alt by mutableStateOf(KeyModifierState.OFF)
        private set

    @Volatile private var ctrlConsumed = false
    @Volatile private var altConsumed = false

    fun tapCtrl() {
        ctrlConsumed = false
        ctrl = nextTapState(ctrl)
    }

    fun tapAlt() {
        altConsumed = false
        alt = nextTapState(alt)
    }

    /**
     * Reads the dispatch state without mutating Compose state in the input hot path.
     * The visual state is cleared by [clearConsumed] after dispatch.
     */
    fun readCtrl(): Boolean = readModifier(ctrl) { ctrlConsumed = true }

    fun readAlt(): Boolean = readModifier(alt) { altConsumed = true }

    fun clearConsumed() {
        if (ctrlConsumed) {
            ctrlConsumed = false
            if (ctrl == KeyModifierState.ARMED) ctrl = KeyModifierState.OFF
        }
        if (altConsumed) {
            altConsumed = false
            if (alt == KeyModifierState.ARMED) alt = KeyModifierState.OFF
        }
    }

    private fun readModifier(state: KeyModifierState, markConsumed: () -> Unit): Boolean {
        if (state == KeyModifierState.ARMED) markConsumed()
        return state != KeyModifierState.OFF
    }

    private fun nextTapState(state: KeyModifierState): KeyModifierState = when (state) {
        KeyModifierState.OFF -> KeyModifierState.ARMED
        KeyModifierState.ARMED -> KeyModifierState.LOCKED
        KeyModifierState.LOCKED -> KeyModifierState.OFF
    }
}

/**
 * Writes [keyCode] to [session] encoded with its cursor, keypad and kitty modes. Armed or locked
 * Ctrl and Alt apply, then [ModifierKeys.clearConsumed] drops the one-shot state.
 */
fun sendKeyCode(session: TerminalSession, keyCode: Int, modifiers: ModifierKeys) {
    session.setGhosttyTopRow(0)
    try {
        var keyModifiers = 0
        if (modifiers.readCtrl()) keyModifiers = keyModifiers or KeyHandler.KEYMOD_CTRL
        if (modifiers.readAlt()) keyModifiers = keyModifiers or KeyHandler.KEYMOD_ALT
        val sequence = KeyHandler.getCode(
            keyCode,
            keyModifiers,
            session.isCursorKeysApplicationMode,
            session.isKeypadApplicationMode,
            session.getKittyKeyboardFlags(),
        ).orEmpty()
        if (sequence.isNotEmpty()) {
            session.setCursorBlinkState(true)
            session.write(sequence)
        }
    } finally {
        modifiers.clearConsumed()
    }
}

enum class ArrowDirection(val keyCode: Int) {
    UP(KeyEvent.KEYCODE_DPAD_UP),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
}

private val JoystickTrackSize = 40.dp
private val JoystickKnobSize = 18.dp
private val JoystickStepX = 24.dp
private val JoystickStepY = 36.dp
private const val SwipeHoldDelayMillis = 400L
private const val JoystickDeadZoneFraction = 0.35f
private const val FirstRepeatDelayMillis = 350L
private const val RepeatDelayMillis = 120.0
private const val RepeatAcceleration = 0.85
private const val MinRepeatDelayMillis = 40.0

/** Returns the dominant axis of a screen-space drag vector, or null inside the dead zone. */
internal fun joystickDirection(dx: Float, dy: Float, deadZone: Float): ArrowDirection? {
    if (sqrt(dx * dx + dy * dy) < deadZone) return null
    return if (abs(dx) >= abs(dy)) {
        if (dx < 0) ArrowDirection.LEFT else ArrowDirection.RIGHT
    } else {
        if (dy < 0) ArrowDirection.UP else ArrowDirection.DOWN
    }
}

internal fun repeatDelayMillis(repeatIndex: Int): Long {
    if (repeatIndex == 0) return FirstRepeatDelayMillis
    return (RepeatDelayMillis * RepeatAcceleration.pow(repeatIndex - 1))
        .coerceAtLeast(MinRepeatDelayMillis)
        .toLong()
}

internal data class ArrowSteps(val direction: ArrowDirection, val count: Int)

/**
 * Turns a drag into arrow steps like HeliBoard's spacebar swipe: every step of travel is one
 * arrow, the remainder carries over, and the first step locks the axis until the finger lifts.
 * Vertical steps are longer ([stepY]) because vertical arrows jump whole lines.
 */
internal class ArrowDragTracker(private val stepX: Float, private val stepY: Float = stepX) {
    private var startX = 0f
    private var startY = 0f
    private var originX = 0f
    private var originY = 0f
    private var horizontal: Boolean? = null

    /** The axis locked by the first step, or null while no step has been emitted. */
    val lockedHorizontal: Boolean? get() = horizontal

    fun start(x: Float, y: Float) {
        startX = x
        startY = y
        originX = x
        originY = y
        horizontal = null
    }

    fun move(x: Float, y: Float): ArrowSteps? {
        val dx = x - originX
        val dy = y - originY
        val onX = horizontal ?: (abs(dx) >= abs(dy))
        val step = if (onX) stepX else stepY
        val steps = ((if (onX) dx else dy) / step).toInt()
        if (steps == 0) return null
        horizontal = onX
        if (onX) originX += steps * step else originY += steps * step
        val direction = when {
            onX -> if (steps < 0) ArrowDirection.LEFT else ArrowDirection.RIGHT
            else -> if (steps < 0) ArrowDirection.UP else ArrowDirection.DOWN
        }
        return ArrowSteps(direction, abs(steps))
    }

    /** True while the finger is still at least half a step from where it went down towards [direction]. */
    fun isPulled(direction: ArrowDirection, x: Float, y: Float): Boolean = when (direction) {
        ArrowDirection.LEFT -> startX - x >= stepX / 2
        ArrowDirection.RIGHT -> x - startX >= stepX / 2
        ArrowDirection.UP -> startY - y >= stepY / 2
        ArrowDirection.DOWN -> y - startY >= stepY / 2
    }
}

/** Identity-compared so each emitted step restarts the hold delay. */
private class SwipeHold(val direction: ArrowDirection)

private fun Offset.limitedTo(maxLength: Float): Offset {
    val length = getDistance()
    return if (length > maxLength) this * (maxLength / length) else this
}

/** In swipe mode the knob only follows the drag along the axis the swipe locked to. */
private fun swipeKnobTarget(lockedHorizontal: Boolean?, drag: Offset): Offset = when (lockedHorizontal) {
    true -> Offset(drag.x, 0f)
    false -> Offset(0f, drag.y)
    null -> drag
}

/** Repeats the held arrow, or the swipe's arrow once its hold delay has passed, until the finger moves on. */
@Composable
private fun ArrowRepeat(held: ArrowDirection?, swipeHold: SwipeHold?, onArrow: (ArrowDirection) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val currentOnArrow by rememberUpdatedState(onArrow)

    LaunchedEffect(held) {
        val arrow = held ?: return@LaunchedEffect
        var repeatIndex = 0
        while (true) {
            currentOnArrow(arrow)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(repeatDelayMillis(repeatIndex++))
        }
    }

    LaunchedEffect(swipeHold) {
        val arrow = swipeHold?.direction ?: return@LaunchedEffect
        delay(SwipeHoldDelayMillis)
        var repeatIndex = 1
        while (true) {
            currentOnArrow(arrow)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(repeatDelayMillis(repeatIndex++))
        }
    }
}

@Composable
private fun ArrowKnob(knob: Animatable<Offset, AnimationVector2D>) {
    Box(
        Modifier
            .offset { IntOffset(knob.value.x.roundToInt(), knob.value.y.roundToInt()) }
            .size(JoystickKnobSize)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}

@Composable
fun ArrowJoystick(mode: ArrowJoystickMode, onArrow: (ArrowDirection) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnArrow by rememberUpdatedState(onArrow)
    val knob = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var held by remember { mutableStateOf<ArrowDirection?>(null) }
    var swipeHold by remember { mutableStateOf<SwipeHold?>(null) }

    val stepXPx = with(density) { JoystickStepX.toPx() }
    val stepYPx = with(density) { JoystickStepY.toPx() }
    val radius = with(density) { JoystickTrackSize.toPx() / 2f }
    val knobTravel = with(density) { (JoystickTrackSize - JoystickKnobSize).toPx() / 2f }

    ArrowRepeat(held = held, swipeHold = swipeHold, onArrow = onArrow)

    Box(
        modifier
            .size(JoystickTrackSize)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .pointerInput(mode, stepXPx, stepYPx) {
                val tracker = ArrowDragTracker(stepXPx, stepYPx)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    tracker.start(down.position.x, down.position.y)
                    var pointer = down
                    while (pointer.pressed) {
                        pointer.consume()
                        val position = pointer.position
                        val knobTarget = when (mode) {
                            ArrowJoystickMode.Swipe -> {
                                tracker.move(position.x, position.y)?.let { steps ->
                                    repeat(steps.count) { currentOnArrow(steps.direction) }
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    swipeHold = SwipeHold(steps.direction)
                                }
                                swipeHold?.let {
                                    if (!tracker.isPulled(it.direction, position.x, position.y)) swipeHold = null
                                }
                                swipeKnobTarget(tracker.lockedHorizontal, position - down.position)
                            }
                            ArrowJoystickMode.Hold -> {
                                val delta = position - Offset(radius, radius)
                                held = joystickDirection(delta.x, delta.y, radius * JoystickDeadZoneFraction)
                                delta
                            }
                        }
                        scope.launch { knob.snapTo(knobTarget.limitedTo(knobTravel)) }
                        pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    }
                    held = null
                    swipeHold = null
                    scope.launch {
                        knob.animateTo(Offset.Zero, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        ArrowKnob(knob)
    }
}

private val BarHeight = 48.dp

/**
 * Floating glass bar with the key row and the tabs button beside it. The terminal renderer blurs
 * the area under [pillModifier], so the pill is tinted rather than sampled.
 */
@Composable
private fun FloatingBar(
    tabsButton: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = pillModifier.tintedGlassPill(CircleShape),
            shape = CircleShape,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Row(
                modifier = Modifier.height(BarHeight).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
        tabsButton?.invoke()
    }
}

/**
 * Bottom key bar for the terminal: Esc, Ctrl, Alt, the arrow joystick, Tab, then [agents]. [tabsButton]
 * sits beside the pill.
 */
@Composable
fun KeyBar(
    modifiers: ModifierKeys,
    onKey: (keyCode: Int) -> Unit,
    joystickMode: ArrowJoystickMode,
    tabsButton: (@Composable () -> Unit)?,
    agents: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
) {
    FloatingBar(tabsButton = tabsButton, modifier = modifier, pillModifier = pillModifier) {
        KeyButton(label = "Esc", onClick = { onKey(KeyEvent.KEYCODE_ESCAPE) })
        ModifierKeyButton(label = "Ctrl", state = modifiers.ctrl, onClick = modifiers::tapCtrl)
        ModifierKeyButton(label = "Alt", state = modifiers.alt, onClick = modifiers::tapAlt)
        ArrowJoystick(mode = joystickMode, onArrow = { onKey(it.keyCode) })
        KeyButton(label = "Tab", onClick = { onKey(KeyEvent.KEYCODE_TAB) })
        agents?.invoke()
    }
}

@Composable
private fun ModifierKeyButton(label: String, state: KeyModifierState, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when (state) {
        KeyModifierState.OFF -> Color.Transparent to colors.onSurface
        KeyModifierState.ARMED -> colors.secondaryContainer to colors.onSecondaryContainer
        KeyModifierState.LOCKED -> colors.primary to colors.onPrimary
    }
    KeyButton(label = label, onClick = onClick, containerColor = containerColor, contentColor = contentColor)
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            }
        }
    }
    Box(
        Modifier
            .focusProperties { canFocus = false }
            .clip(RoundedCornerShape(percent = 50))
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .defaultMinSize(minWidth = 44.dp, minHeight = 36.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}
