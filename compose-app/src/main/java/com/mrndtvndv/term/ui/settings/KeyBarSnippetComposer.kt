package com.mrndtvndv.term.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.MacroStep

@Composable
internal fun KeyBarSnippetComposer(
    slot: KeyBarEditorSlot,
    action: KeyBarAction?,
    itemLabel: String,
    onChange: (KeyBarAction?) -> Unit,
    onKeyPickerOpen: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    val state = remember(slot) { KeyBarSnippetComposerState(action.initialSnippetSteps()) }
    val focusRequester = remember(slot) { FocusRequester() }
    var keyPickerMode by remember(slot) { mutableStateOf<KeyBarActionPickerKeyModifier?>(null) }

    LaunchedEffect(state.activeTextIndex) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxSize()) {
        SnippetComposerTopBar(slot, onDone)
        SnippetComposerBody(
            modifier = Modifier.weight(1f),
            slot = slot,
            state = state,
            focusRequester = focusRequester,
            onChange = onChange,
        )
        SnippetInsertToolbar(
            enabled = state.meaningfulSteps.size < KeyBarLimits.MaxMacroSteps,
            onInsertKey = { state.insertKey(it, onChange) },
            onOpenKeyPicker = {
                keyPickerMode = it
                onKeyPickerOpen(true)
            },
        )
    }

    keyPickerMode?.let { mode ->
        KeyBarActionPicker(
            request = KeyBarActionPickerRequest(
                slot = slot,
                action = action,
                itemLabel = itemLabel,
                initialTab = KeyBarActionPickerTab.Key,
                keyOnlyModifier = mode,
            ),
            callbacks = KeyBarActionPickerCallbacks(
                onDismiss = {
                    keyPickerMode = null
                    onKeyPickerOpen(false)
                },
                onPick = {},
                onEditSnippet = {},
                onPickKeyStep = { step ->
                    keyPickerMode = null
                    onKeyPickerOpen(false)
                    state.insertKey(step, onChange)
                },
            ),
        )
    }
}

@Composable
private fun SnippetComposerTopBar(slot: KeyBarEditorSlot, onDone: () -> Unit) {
    TopAppBar(
        title = { Text("${slot.composerTitle()} · Snippet", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            FilledTonalButton(
                onClick = onDone,
                modifier = Modifier.padding(end = 8.dp).heightIn(min = 44.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("Done")
            }
        },
    )
}

@Composable
private fun SnippetComposerBody(
    modifier: Modifier,
    slot: KeyBarEditorSlot,
    state: KeyBarSnippetComposerState,
    focusRequester: FocusRequester,
    onChange: (KeyBarAction?) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Sends, in order", style = MaterialTheme.typography.labelMedium)
        SnippetTokenStrip(state, focusRequester, onChange)
        SnippetComposerStepCount(state.meaningfulSteps.size)
        state.validationErrors(slot).forEach { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        SnippetRawReadout(state.rawInput(), state.endsWithEnter())
    }
}

@Composable
private fun SnippetInsertToolbar(
    enabled: Boolean,
    onInsertKey: (MacroStep.Key) -> Unit,
    onOpenKeyPicker: (KeyBarActionPickerKeyModifier) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SnippetInsertButton("⏎ Enter", enabled) {
                onInsertKey(MacroStep.Key(KeyEvent.KEYCODE_ENTER))
            }
            SnippetInsertButton("Tab", enabled) { onInsertKey(MacroStep.Key(KeyEvent.KEYCODE_TAB)) }
            SnippetInsertButton("Esc", enabled) { onInsertKey(MacroStep.Key(KeyEvent.KEYCODE_ESCAPE)) }
            SnippetInsertButton("Ctrl+…", enabled) {
                onOpenKeyPicker(KeyBarActionPickerKeyModifier.Ctrl)
            }
            SnippetInsertButton("Alt+…", enabled) {
                onOpenKeyPicker(KeyBarActionPickerKeyModifier.Alt)
            }
            SnippetInsertButton("↑", enabled) { onInsertKey(MacroStep.Key(KeyEvent.KEYCODE_DPAD_UP)) }
            SnippetInsertButton("↓", enabled) { onInsertKey(MacroStep.Key(KeyEvent.KEYCODE_DPAD_DOWN)) }
            SnippetInsertButton("←", enabled) { onInsertKey(MacroStep.Key(KeyEvent.KEYCODE_DPAD_LEFT)) }
            SnippetInsertButton("→", enabled) { onInsertKey(MacroStep.Key(KeyEvent.KEYCODE_DPAD_RIGHT)) }
        }
    }
}

@Composable
private fun SnippetInsertButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 44.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(label, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

private fun KeyBarEditorSlot.composerTitle(): String = when (this) {
    KeyBarEditorSlot.Tap -> "Tap"
    is KeyBarEditorSlot.Swipe -> "${direction.arrow} Swipe ${direction.name.lowercase()}"
}
