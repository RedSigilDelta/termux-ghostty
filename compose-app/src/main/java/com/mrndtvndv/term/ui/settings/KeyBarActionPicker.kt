@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mrndtvndv.term.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.MacroStep
import com.mrndtvndv.term.data.prefs.isSupported

internal sealed interface KeyBarEditorSlot {
    data object Tap : KeyBarEditorSlot

    data class Swipe(val direction: KeyBarDirection) : KeyBarEditorSlot
}

internal enum class KeyBarActionPickerTab(val label: String) {
    Key("Key"),
    Snippet("Snippet"),
    Modifier("Modifier"),
    Herdr("Herdr"),
    None("None"),
}

private data class ActionPickerState(
    val tabs: List<KeyBarActionPickerTab>,
    val selectedTab: KeyBarActionPickerTab,
    val pendingKey: MacroStep.Key,
)

internal data class KeyBarActionPickerRequest(
    val slot: KeyBarEditorSlot,
    val action: KeyBarAction?,
    val itemLabel: String,
    val initialTab: KeyBarActionPickerTab?,
    val keyOnlyModifier: KeyBarActionPickerKeyModifier? = null,
)

internal data class KeyBarActionPickerCallbacks(
    val onDismiss: () -> Unit,
    val onPick: (KeyBarAction?) -> Unit,
    val onEditSnippet: () -> Unit,
    val onPickKeyStep: (MacroStep.Key) -> Unit = {},
)

internal enum class KeyBarActionPickerKeyModifier(val label: String) {
    Ctrl("Ctrl"),
    Alt("Alt"),
}

@Composable
internal fun KeyBarActionPicker(
    request: KeyBarActionPickerRequest,
    callbacks: KeyBarActionPickerCallbacks,
) {
    val tabs = remember(request.slot, request.keyOnlyModifier) {
        if (request.keyOnlyModifier == null) {
            request.slot.availableTabs()
        } else {
            listOf(KeyBarActionPickerTab.Key)
        }
    }
    var selectedTab by remember(request.slot, request.action, request.initialTab, request.keyOnlyModifier) {
        mutableStateOf(
            (request.initialTab ?: request.action.initialPickerTab()).takeIf { it in tabs } ?: tabs.first(),
        )
    }
    var pendingKey by remember(request.slot, request.action, request.keyOnlyModifier) {
        mutableStateOf(request.action.pendingKey(request.keyOnlyModifier))
    }
    var unsupportedMessage by remember { mutableStateOf<String?>(null) }
    val state = ActionPickerState(tabs, selectedTab, pendingKey)
    val onPickKey: (Int) -> Unit = { keyCode ->
        val candidate = pendingKey.copy(keyCode = keyCode)
        when {
            !candidate.isSupported() -> unsupportedMessage = "${candidate.summary()} isn't supported"
            request.keyOnlyModifier != null -> callbacks.onPickKeyStep(candidate)
            else -> callbacks.onPick(actionFromSteps(listOf(candidate)))
        }
    }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        onDismissRequest = callbacks.onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        ActionPickerSheetContent(
            request = request,
            callbacks = callbacks,
            state = state,
            unsupportedMessage = unsupportedMessage,
            onSelectTab = { selectedTab = it },
            onModifierChange = { transform ->
                pendingKey = transform(pendingKey)
                unsupportedMessage = null
            },
            onPickKey = onPickKey,
        )
    }
}

@Composable
private fun ActionPickerSheetContent(
    request: KeyBarActionPickerRequest,
    callbacks: KeyBarActionPickerCallbacks,
    state: ActionPickerState,
    unsupportedMessage: String?,
    onSelectTab: (KeyBarActionPickerTab) -> Unit,
    onModifierChange: ((MacroStep.Key) -> MacroStep.Key) -> Unit,
    onPickKey: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ActionPickerHeader(
            slot = request.slot,
            itemLabel = request.itemLabel,
            title = request.keyOnlyModifier?.let { "Insert ${it.label} key" },
        )
        if (request.keyOnlyModifier == null) {
            ActionPickerTabs(state.tabs, state.selectedTab, onSelectTab)
        }
        when (state.selectedTab) {
            KeyBarActionPickerTab.Key -> KeyTab(
                pendingKey = state.pendingKey,
                unsupportedMessage = unsupportedMessage,
                isSwipeSlot = request.slot is KeyBarEditorSlot.Swipe,
                isKeyOnlyInsert = request.keyOnlyModifier != null,
                onModifierChange = onModifierChange,
                onPickKey = onPickKey,
            )
            KeyBarActionPickerTab.Snippet -> SnippetTab(request.action, callbacks.onEditSnippet)
            KeyBarActionPickerTab.Modifier -> ModifierTab(callbacks.onPick)
            KeyBarActionPickerTab.Herdr -> HerdrTab(callbacks.onPick)
            KeyBarActionPickerTab.None -> NoneTab(callbacks.onPick)
        }
    }
}

private fun KeyBarEditorSlot.availableTabs(): List<KeyBarActionPickerTab> = when (this) {
    KeyBarEditorSlot.Tap -> listOf(
        KeyBarActionPickerTab.Key,
        KeyBarActionPickerTab.Snippet,
        KeyBarActionPickerTab.Modifier,
        KeyBarActionPickerTab.Herdr,
    )
    is KeyBarEditorSlot.Swipe -> listOf(
        KeyBarActionPickerTab.Key,
        KeyBarActionPickerTab.Snippet,
        KeyBarActionPickerTab.Herdr,
        KeyBarActionPickerTab.None,
    )
}

private fun KeyBarAction?.initialPickerTab(): KeyBarActionPickerTab = when (this) {
    null -> KeyBarActionPickerTab.Key
    is KeyBarAction.Key -> KeyBarActionPickerTab.Key
    is KeyBarAction.Macro -> if (steps.singleOrNull() is MacroStep.Key) {
        KeyBarActionPickerTab.Key
    } else {
        KeyBarActionPickerTab.Snippet
    }
    is KeyBarAction.Text -> KeyBarActionPickerTab.Snippet
    KeyBarAction.Ctrl, KeyBarAction.Alt, KeyBarAction.Joystick -> KeyBarActionPickerTab.Modifier
    is KeyBarAction.Host -> KeyBarActionPickerTab.Herdr
}

private fun KeyBarAction?.pendingKey(keyOnlyModifier: KeyBarActionPickerKeyModifier?): MacroStep.Key {
    if (keyOnlyModifier != null) {
        return MacroStep.Key(
            keyCode = KeyEvent.KEYCODE_C,
            ctrl = keyOnlyModifier == KeyBarActionPickerKeyModifier.Ctrl,
            alt = keyOnlyModifier == KeyBarActionPickerKeyModifier.Alt,
        )
    }

    return when (this) {
        is KeyBarAction.Key -> MacroStep.Key(keyCode)
        is KeyBarAction.Macro ->
            steps.singleOrNull() as? MacroStep.Key ?: MacroStep.Key(KeyEvent.KEYCODE_ESCAPE)
        else -> MacroStep.Key(KeyEvent.KEYCODE_ESCAPE)
    }
}

@Composable
private fun ActionPickerHeader(slot: KeyBarEditorSlot, itemLabel: String, title: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title ?: slot.title(), style = MaterialTheme.typography.titleLarge)
        Text(
            text = "on $itemLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun KeyBarEditorSlot.title(): String = when (this) {
    KeyBarEditorSlot.Tap -> "Tap"
    is KeyBarEditorSlot.Swipe -> "${direction.arrow} Swipe ${direction.name.lowercase()}"
}

@Composable
private fun ActionPickerTabs(
    tabs: List<KeyBarActionPickerTab>,
    selectedTab: KeyBarActionPickerTab,
    onSelect: (KeyBarActionPickerTab) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        tabs.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = tab == selectedTab,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(tab.label, maxLines = 1)
            }
        }
    }
}
