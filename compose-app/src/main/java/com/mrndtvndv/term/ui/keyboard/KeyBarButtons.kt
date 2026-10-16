package com.mrndtvndv.term.ui.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarKeyCatalog
import com.mrndtvndv.term.data.prefs.MacroKeyCatalog
import com.mrndtvndv.term.data.prefs.MacroStep

/** An icon key on the key bar pill, for actions the terminal adds, such as the Herdr Git and SFTP sheets. */
@Composable
fun KeyBarIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .focusProperties { canFocus = false }
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 36.dp, minHeight = 36.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun ModifierKeyButton(
    label: String,
    state: KeyModifierState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when (state) {
        KeyModifierState.OFF -> Color.Transparent to colors.onSurface
        KeyModifierState.ARMED -> colors.secondaryContainer to colors.onSecondaryContainer
        KeyModifierState.LOCKED -> colors.primary to colors.onPrimary
    }
    KeyButton(
        label = label,
        onClick = onClick,
        onLongClick = onLongClick,
        containerColor = containerColor,
        contentColor = contentColor,
        stateLabel = when (state) {
            KeyModifierState.OFF -> "Off"
            KeyModifierState.ARMED -> "Armed"
            KeyModifierState.LOCKED -> "Locked"
        },
    )
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    stateLabel: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    KeyFace(
        label = label,
        interactionSource = interactionSource,
        gestures = Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        containerColor = containerColor,
        contentColor = contentColor,
        stateLabel = stateLabel,
    )
}

/** The key's window rect, read when a preview is shown. Held in a plain box because nothing composes from it. */
private class WindowBounds {
    var rect: Rect = Rect.Zero
}

/**
 * A tap-and-swipe key. A claimed drag reports its target to the key bar's preview. The chip shows only while some of
 * the key is on screen, and it clears when the drag ends or the key leaves composition. TalkBack gets the same
 * actions as semantics: a click for the tap and one custom action per assigned direction. Each path calls its
 * handler once, so nothing fires twice.
 */
@Composable
internal fun SwipeKeyButton(
    item: KeyBarItem,
    onTap: () -> Unit,
    onSwipe: (KeySwipe) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val preview = LocalKeySwipePreview.current
    val bounds = remember { WindowBounds() }
    val assigned = KeySwipe.entries.filterTo(HashSet()) { item.swipeAction(it) != null }
    val swipeNames = KeySwipe.entries.mapNotNull { swipe ->
        item.swipeAction(swipe)?.let { swipe to "Swipe ${swipe.name.lowercase()}: ${it.spokenName()}" }
    }.toMap()
    val tapName = "Send ${item.tap.spokenName()}"
    DisposableEffect(item.id, preview) {
        onDispose { preview?.clear(item.id) }
    }
    KeyFace(
        label = item.label,
        interactionSource = interactionSource,
        gestures = Modifier
            .onGloballyPositioned { bounds.rect = it.boundsInWindow() }
            .keySwipeGestures(interactionSource, assigned, onTap, onSwipe) { target, armed ->
                val host = preview ?: return@keySwipeGestures
                val anchor = host.visibleAnchor(bounds.rect)
                if (target == null || anchor == null) {
                    host.clear(item.id)
                } else {
                    val label = item.swipeAction(target)?.previewLabel().orEmpty()
                    host.show(KeySwipePreview(item.id, anchor, target, label, armed))
                }
            }
            .indication(interactionSource, LocalIndication.current)
            .semantics {
                onClick(label = tapName) {
                    onTap()
                    true
                }
                customActions = swipeNames.map { (swipe, name) ->
                    CustomAccessibilityAction(name) {
                        onSwipe(swipe)
                        true
                    }
                }
            },
    )
}

@Composable
private fun KeyFace(
    label: String,
    interactionSource: MutableInteractionSource,
    gestures: Modifier,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    stateLabel: String? = null,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PressedKeyScale else 1f,
        animationSpec = tween(durationMillis = PressFeedbackMillis),
        label = "keyPressScale",
    )
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
            }
        }
    }
    Box(
        Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .focusProperties { canFocus = false }
            .clip(RoundedCornerShape(percent = 50))
            .background(containerColor)
            .then(gestures)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateLabel?.let { this.stateDescription = it }
            }
            .defaultMinSize(minWidth = 44.dp, minHeight = 36.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}

/** Keys sink slightly on press, so the press reads before the action lands. */
private const val PressedKeyScale = 0.94f
private const val PressFeedbackMillis = 80

private const val SpokenTextLength = 24
private const val SpokenMacroLength = 80

/** What TalkBack says for an action: a key's spoken name, a text's first line, a macro's steps, or a modifier. */
internal fun KeyBarAction.spokenName(): String = when (this) {
    is KeyBarAction.Key -> KeyBarKeyCatalog.firstOrNull { it.keyCode == this.keyCode }?.spokenName ?: "key"
    is KeyBarAction.Text -> "text " + spokenText(text)
    is KeyBarAction.Macro -> "macro " + steps.joinToString(", ") { it.spokenName() }.take(SpokenMacroLength)
    KeyBarAction.Ctrl -> "Control"
    KeyBarAction.Alt -> "Alt"
    KeyBarAction.Joystick -> "arrow keys"
}

internal fun MacroStep.spokenName(): String = when (this) {
    is MacroStep.Text -> "text " + spokenText(text)
    is MacroStep.Key -> {
        val modifiers = listOfNotNull("Control".takeIf { ctrl }, "Alt".takeIf { alt }, "Shift".takeIf { shift })
        (modifiers + (MacroKeyCatalog.firstOrNull { it.keyCode == keyCode }?.spokenName ?: "key")).joinToString(" ")
    }
}

private fun spokenText(text: String): String =
    text.trim().lineSequence().first().take(SpokenTextLength).ifEmpty { "blank" }
