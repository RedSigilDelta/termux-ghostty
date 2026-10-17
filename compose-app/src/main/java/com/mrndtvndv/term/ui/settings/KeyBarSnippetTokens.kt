@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.mrndtvndv.term.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key as ComposeKey
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.MacroStep

internal data class SnippetStepTokenState(
    val step: MacroStep,
    val index: Int,
    val active: Boolean,
    val menuExpanded: Boolean,
    val canMoveLeft: Boolean,
    val canMoveRight: Boolean,
)

internal data class SnippetStepTokenCallbacks(
    val onActivate: () -> Unit,
    val onLongPress: () -> Unit,
    val onTextChange: (String) -> Unit,
    val onHardwareBackspace: () -> Boolean,
    val onDismissMenu: () -> Unit,
    val onMove: (Int) -> Unit,
    val onRemove: () -> Unit,
)

@Composable
internal fun SnippetTokenStrip(
    state: KeyBarSnippetComposerState,
    focusRequester: FocusRequester,
    onChange: (KeyBarAction?) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.steps.forEachIndexed { index, step ->
                SnippetStepToken(
                    state = SnippetStepTokenState(
                        step = step,
                        index = index,
                        active = index == state.activeTextIndex,
                        menuExpanded = state.menuStepIndex == index,
                        canMoveLeft = index > 0,
                        canMoveRight = index < state.steps.lastIndex,
                    ),
                    focusRequester = focusRequester,
                    callbacks = SnippetStepTokenCallbacks(
                        onActivate = { state.activateText(index) },
                        onLongPress = { state.menuStepIndex = index },
                        onTextChange = { state.updateText(index, it, onChange) },
                        onHardwareBackspace = { state.removePreviousKeyForEmptyText(index, onChange) },
                        onDismissMenu = { state.menuStepIndex = null },
                        onMove = { state.moveStep(index, it, onChange) },
                        onRemove = { state.removeStep(index, onChange) },
                    ),
                )
            }
        }
    }
}

@Composable
internal fun SnippetComposerStepCount(count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Type to add text · tap a chip to edit or remove it",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "$count/${KeyBarLimits.MaxMacroSteps}",
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun SnippetStepTokenState.contentDescription(): String = when (val token = step) {
    is MacroStep.Key -> "Key step ${token.summary()}. Options"
    is MacroStep.Text -> if (active) {
        "Active text step ${index + 1}"
    } else {
        "Text step ${index + 1}: ${token.text.displayText()}. Edit; long press for options"
    }
}

@Composable
private fun SnippetStepToken(
    state: SnippetStepTokenState,
    focusRequester: FocusRequester,
    callbacks: SnippetStepTokenCallbacks,
) {
    Box {
        SnippetStepTokenSurface(state, focusRequester, callbacks)
        DropdownMenu(expanded = state.menuExpanded, onDismissRequest = callbacks.onDismissMenu) {
            DropdownMenuItem(
                text = { Text("Move left") },
                enabled = state.canMoveLeft,
                onClick = { callbacks.onMove(-1) },
            )
            DropdownMenuItem(
                text = { Text("Move right") },
                enabled = state.canMoveRight,
                onClick = { callbacks.onMove(1) },
            )
            DropdownMenuItem(text = { Text("Remove") }, onClick = callbacks.onRemove)
        }
    }
}

@Composable
private fun SnippetStepTokenSurface(
    state: SnippetStepTokenState,
    focusRequester: FocusRequester,
    callbacks: SnippetStepTokenCallbacks,
) {
    val keyStep = state.step as? MacroStep.Key
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .combinedClickable(
                onClick = {
                    if (keyStep != null) callbacks.onLongPress() else callbacks.onActivate()
                },
                onLongClick = callbacks.onLongPress,
                role = Role.Button,
            )
            .semantics { contentDescription = state.contentDescription() },
        shape = RoundedCornerShape(10.dp),
        color = if (keyStep != null) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (keyStep != null) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(
            width = if (state.active) 1.dp else 0.dp,
            color = if (state.active) colors.primary else colors.surfaceContainerHigh,
        ),
    ) {
        SnippetStepTokenContent(state, focusRequester, callbacks)
    }
}

@Composable
private fun SnippetStepTokenContent(
    state: SnippetStepTokenState,
    focusRequester: FocusRequester,
    callbacks: SnippetStepTokenCallbacks,
) {
    when {
        state.step is MacroStep.Key -> KeyStepToken(state.step)
        state.step is MacroStep.Text && state.active -> ActiveTextStepToken(
            state.step,
            focusRequester,
            callbacks.onTextChange,
            callbacks.onHardwareBackspace,
        )
        state.step is MacroStep.Text -> InactiveTextStepToken(state.step)
    }
}

@Composable
private fun KeyStepToken(step: MacroStep.Key) {
    Text(
        step.tokenLabel(),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
    )
}

@Composable
private fun ActiveTextStepToken(
    step: MacroStep.Text,
    focusRequester: FocusRequester,
    onTextChange: (String) -> Unit,
    onHardwareBackspace: () -> Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val state = rememberTextFieldState(step.text)
    // The composer state is authoritative: it clamps text length and can reject an edit.
    LaunchedEffect(step.text) {
        if (state.text.toString() != step.text) state.setTextAndPlaceCursorAtEnd(step.text)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text != step.text) onTextChange(text)
        }
    }
    BasicTextField(
        state = state,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown &&
                    event.key == ComposeKey.Backspace &&
                    step.text.isEmpty() &&
                    onHardwareBackspace()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .widthIn(min = 24.dp, max = 280.dp)
            .heightIn(min = 24.dp),
        textStyle = TextStyle(
            color = colors.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = MaterialTheme.typography.labelLarge.fontSize,
        ),
        cursorBrush = SolidColor(colors.primary),
        outputTransformation = SnippetNewlineOutputTransformation,
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
    )
}

@Composable
private fun InactiveTextStepToken(step: MacroStep.Text) {
    Text(
        step.text.displayText(),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Renders stored newlines as a visible glyph without changing the composer's text. */
private object SnippetNewlineOutputTransformation : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        for (index in length - 1 downTo 0) {
            if (charAt(index) == '\n' || charAt(index) == '\r') replace(index, index + 1, "↵")
        }
    }
}

private fun MacroStep.Key.tokenLabel(): String =
    if (keyCode == KeyEvent.KEYCODE_ENTER && !hasModifier) "⏎ Enter" else summary()

private val MacroStep.Key.hasModifier: Boolean
    get() = ctrl || alt || shift

private fun String.displayText(): String = replace('\n', '↵').replace('\r', '↵')
