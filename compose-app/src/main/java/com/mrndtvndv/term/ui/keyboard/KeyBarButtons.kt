package com.mrndtvndv.term.ui.keyboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarHostAction
import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarKeyCatalog
import com.mrndtvndv.term.data.prefs.MacroKeyCatalog
import com.mrndtvndv.term.data.prefs.MacroStep

@Composable
internal fun ModifierKeyButton(
    label: String,
    state: KeyModifierState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    item: KeyBarItem? = null,
    onSwipe: ((KeySwipe) -> Unit)? = null,
) {
    val assigned = item?.assignedSwipes().orEmpty()
    if (item == null || onSwipe == null || assigned.isEmpty()) {
        ModifierKeyButtonFace(label, state, onClick, onLongClick)
        return
    }

    SwipeModifierKeyButton(item, label, state, onClick, onLongClick, assigned, onSwipe)
}

@Composable
private fun ModifierKeyButtonFace(
    label: String,
    state: KeyModifierState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
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
private fun SwipeModifierKeyButton(
    item: KeyBarItem,
    label: String,
    state: KeyModifierState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    assigned: Set<KeySwipe>,
    onSwipe: (KeySwipe) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when (state) {
        KeyModifierState.OFF -> Color.Transparent to colors.onSurface
        KeyModifierState.ARMED -> colors.secondaryContainer to colors.onSecondaryContainer
        KeyModifierState.LOCKED -> colors.primary to colors.onPrimary
    }
    val interactionSource = remember { MutableInteractionSource() }
    val preview = LocalKeySwipePreview.current
    val bounds = remember { WindowBounds() }
    val swipeNames = KeySwipe.entries.mapNotNull { swipe ->
        item.swipeAction(swipe)?.let { swipe to "Swipe ${swipe.name.lowercase()}: ${it.spokenName()}" }
    }

    DisposableEffect(item.id, preview) {
        onDispose { preview?.clear(item.id) }
    }
    KeyFace(
        label = label,
        interactionSource = interactionSource,
        gestures = Modifier
            .onGloballyPositioned { bounds.rect = it.boundsInWindow() }
            .keySwipeGestures(
                interactionSource = interactionSource,
                assigned = assigned,
                onTap = onClick,
                onSwipe = onSwipe,
                onDragTarget = { target, armed ->
                    reportModifierSwipePreview(preview, item, bounds, target, armed)
                },
                onLongPress = onLongClick,
            )
            .indication(interactionSource, LocalIndication.current)
            .modifierSwipeSemantics(item.tap.spokenName(), swipeNames, onClick, onLongClick, onSwipe),
        containerColor = containerColor,
        contentColor = contentColor,
        stateLabel = when (state) {
            KeyModifierState.OFF -> "Off"
            KeyModifierState.ARMED -> "Armed"
            KeyModifierState.LOCKED -> "Locked"
        },
    )
}

private fun reportModifierSwipePreview(
    preview: KeySwipePreviewHost?,
    item: KeyBarItem,
    bounds: WindowBounds,
    target: KeySwipe?,
    armed: Boolean,
) {
    val host = preview ?: return
    val anchor = target?.let { host.visibleAnchor(bounds.rect) }
    if (target == null || anchor == null) {
        host.clear(item.id)
        return
    }

    val label = item.swipeAction(target)?.previewLabel().orEmpty()
    host.show(KeySwipePreview(item.id, anchor, target, label, armed))
}

private fun Modifier.modifierSwipeSemantics(
    tapName: String,
    swipeNames: List<Pair<KeySwipe, String>>,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onSwipe: (KeySwipe) -> Unit,
): Modifier = semantics {
    this.onClick(label = "Tap $tapName") {
        onClick()
        true
    }
    onLongClick?.let { longClick ->
        this.onLongClick(label = "Hold $tapName") {
            longClick()
            true
        }
    }
    customActions = swipeNames.map { (swipe, name) ->
        CustomAccessibilityAction(name) {
            onSwipe(swipe)
            true
        }
    }
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
    val assigned = item.assignedSwipes()
    val swipeNames = KeySwipe.entries.mapNotNull { swipe ->
        item.swipeAction(swipe)?.let { swipe to "Swipe ${swipe.name.lowercase()}: ${it.spokenName()}" }
    }.toMap()
    val hostKind = (item.tap as? KeyBarAction.Host)?.kind
    val tapName = hostKind?.spokenName() ?: "Send ${item.tap.spokenName()}"
    DisposableEffect(item.id, preview) {
        onDispose { preview?.clear(item.id) }
    }
    KeyFace(
        label = item.label,
        hostAction = hostKind,
        interactionSource = interactionSource,
        gestures = Modifier
            .onGloballyPositioned { bounds.rect = it.boundsInWindow() }
            .keySwipeGestures(
                interactionSource = interactionSource,
                assigned = assigned,
                onTap = onTap,
                onSwipe = onSwipe,
                onDragTarget = { target, armed ->
                    val host = preview ?: return@keySwipeGestures
                    val anchor = host.visibleAnchor(bounds.rect)
                    if (target == null || anchor == null) {
                        host.clear(item.id)
                    } else {
                        val label = item.swipeAction(target)?.previewLabel().orEmpty()
                        host.show(KeySwipePreview(item.id, anchor, target, label, armed))
                    }
                },
            )
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
    hostAction: KeyBarHostAction? = null,
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
        if (hostAction != null) {
            Icon(
                imageVector = hostAction.icon,
                contentDescription = hostAction.spokenName(),
                modifier = Modifier.size(20.dp),
                tint = contentColor,
            )
        } else {
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = contentColor)
        }
    }
}

/** Keys sink slightly on press, so the press reads before the action lands. */
private const val PressedKeyScale = 0.94f
private const val PressFeedbackMillis = 80

private const val SpokenTextLength = 24
private const val SpokenMacroLength = 80

/** The icon for a host action. The fixed host icons and the key-bar items that run them share it. */
internal val KeyBarHostAction.icon: ImageVector
    get() = when (this) {
        KeyBarHostAction.Git -> Icons.Default.Commit
        KeyBarHostAction.Sftp -> Icons.Default.Folder
    }

/** The accessible name of a host action, which also names its fixed icon. */
internal fun KeyBarHostAction.spokenName(): String = when (this) {
    KeyBarHostAction.Git -> "Open Git sheet"
    KeyBarHostAction.Sftp -> "Open SFTP browser"
}

/** What TalkBack says for an action: a key's spoken name, a text's first line, a macro's steps, or a modifier. */
internal fun KeyBarAction.spokenName(): String = when (this) {
    is KeyBarAction.Key -> KeyBarKeyCatalog.firstOrNull { it.keyCode == this.keyCode }?.spokenName ?: "key"
    is KeyBarAction.Text -> "text " + spokenText(text)
    is KeyBarAction.Macro -> "macro " + steps.joinToString(", ") { it.spokenName() }.take(SpokenMacroLength)
    KeyBarAction.Ctrl -> "Control"
    KeyBarAction.Alt -> "Alt"
    is KeyBarAction.Host -> kind.spokenName()
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
