package com.termux.app.terminal.io

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.ui.glassPill
import com.termux.app.ui.tintedGlassPill
import com.termux.terminal.compose.session.ProgramState
import dev.chrisbanes.haze.HazeState
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

    fun metaState(): Int {
        val ctrlMeta = if (readCtrl()) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0
        val altMeta = if (readAlt()) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0
        clearConsumed()
        return ctrlMeta or altMeta
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

enum class ArrowDirection(val keyCode: Int) {
    UP(KeyEvent.KEYCODE_DPAD_UP),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
}

private val JoystickTrackSize = 40.dp
private val JoystickKnobSize = 18.dp
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

private fun Offset.limitedTo(maxLength: Float): Offset {
    val length = getDistance()
    return if (length > maxLength) this * (maxLength / length) else this
}

@Composable
fun ArrowJoystick(onArrow: (ArrowDirection) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val currentOnArrow by rememberUpdatedState(onArrow)
    val knob = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var direction by remember { mutableStateOf<ArrowDirection?>(null) }

    val radius = with(density) { JoystickTrackSize.toPx() / 2f }
    val knobTravel = with(density) { (JoystickTrackSize - JoystickKnobSize).toPx() / 2f }
    val deadZone = radius * JoystickDeadZoneFraction

    LaunchedEffect(direction) {
        val arrow = direction ?: return@LaunchedEffect
        var repeatIndex = 0
        while (true) {
            currentOnArrow(arrow)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            delay(repeatDelayMillis(repeatIndex++))
        }
    }

    Box(
        modifier
            .size(JoystickTrackSize)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
            .pointerInput(radius) {
                val centre = Offset(radius, radius)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var pointer = down
                    while (pointer.pressed) {
                        pointer.consume()
                        val delta = pointer.position - centre
                        val target = delta.limitedTo(knobTravel)
                        scope.launch { knob.snapTo(target) }
                        direction = joystickDirection(delta.x, delta.y, deadZone)
                        pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    }
                    direction = null
                    scope.launch {
                        knob.animateTo(Offset.Zero, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .offset { IntOffset(knob.value.x.roundToInt(), knob.value.y.roundToInt()) }
                .size(JoystickKnobSize)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

private val BarHeight = 48.dp

/**
 * Floating bar shared by every tab type: the tab supplies its actions, the screen supplies [tabsButton].
 * A non-null [hazeState] makes the bar frosted glass over that source; null keeps the solid bar.
 */
@Composable
fun FloatingBar(
    tabsButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    backdropBlurred: Boolean = false,
    pillModifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val glass = when {
        hazeState != null -> Modifier.glassPill(hazeState, CircleShape)
        backdropBlurred -> Modifier.tintedGlassPill(CircleShape)
        else -> null
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = pillModifier.then(glass ?: Modifier),
            shape = CircleShape,
            color = if (glass != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = if (glass != null) 0.dp else 3.dp,
        ) {
            Row(
                modifier = Modifier.height(BarHeight).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
        tabsButton()
    }
}

/** Opens the tabs grid; shows the current tab's icon and the tab count. */
@Composable
fun TabsButton(tabCount: Int, tabIcon: @Composable () -> Unit, onOpenTabs: () -> Unit) {
    val tabsDescription = stringResource(R.string.action_open_tabs)
    Surface(
        onClick = onOpenTabs,
        modifier = Modifier.size(BarHeight).semantics { contentDescription = tabsDescription },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            BadgedBox(badge = { Badge { Text(tabCount.toString()) } }) {
                tabIcon()
            }
        }
    }
}

/** [state] is the most urgent agent state, or null when no agent needs attention. */
data class AgentsButtonState(val state: ProgramState?, val onClick: () -> Unit)

@Composable
fun KeyBar(
    modifiers: ModifierKeys,
    onKey: (keyCode: Int) -> Unit,
    tabsButton: @Composable () -> Unit,
    agents: AgentsButtonState?,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
) {
    // The terminal renderer blurs the area under the pill (see TerminalComposeView.setBlurRegions).
    FloatingBar(tabsButton = tabsButton, modifier = modifier, backdropBlurred = true, pillModifier = pillModifier) {
        KeyButton(label = "Esc", onClick = { onKey(KeyEvent.KEYCODE_ESCAPE) })
        ModifierKeyButton(label = "Ctrl", state = modifiers.ctrl, onClick = modifiers::tapCtrl)
        ModifierKeyButton(label = "Alt", state = modifiers.alt, onClick = modifiers::tapAlt)
        ArrowJoystick(onArrow = { onKey(it.keyCode) })
        KeyButton(label = "Tab", onClick = { onKey(KeyEvent.KEYCODE_TAB) })
        agents?.let { AgentsButton(state = it.state, onClick = it.onClick) }
    }
}

@Composable
private fun AgentsButton(state: ProgramState?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val iconTint = if (state == ProgramState.Blocked || state == ProgramState.Error) colors.error else colors.onSurface
    val dotColor = when (state) {
        ProgramState.Blocked -> colors.error
        ProgramState.Done -> colors.primary
        else -> null
    }
    val description = stringResource(R.string.action_open_agents)
    Box(
        Modifier
            .focusProperties { canFocus = false }
            .size(36.dp)
            .semantics { contentDescription = description }
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (state == ProgramState.Working) {
            CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
        }
        Icon(imageVector = Icons.Rounded.SmartToy, contentDescription = null, tint = iconTint)
        dotColor?.let { color ->
            Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(color, CircleShape))
        }
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
