@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mrndtvndv.term.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.isSwipeable
import com.mrndtvndv.term.ui.keyboard.KeySwipe

internal data class KeyBarItemEditorState(
    val draft: KeyBarItemDraft,
    val isNew: Boolean,
    val itemExists: Boolean,
    val itemIndex: Int,
    val itemCount: Int,
    val saveStatus: String,
    val saveStatusIsError: Boolean,
    val inlineError: String?,
    val discardMessage: String?,
    val initialPickerTab: KeyBarActionPickerTab?,
    val initialComposerSlot: KeyBarEditorSlot?,
)

internal data class KeyBarItemEditorCallbacks(
    val onEdit: ((KeyBarItemDraft) -> KeyBarItemDraft) -> Unit,
    val onBack: () -> Unit,
    val onDiscard: () -> Unit,
    val onMove: (Int) -> Unit,
    val onDuplicate: () -> Unit,
    val onRemove: () -> Unit,
)

@Composable
internal fun KeyBarItemEditorScreen(
    state: KeyBarItemEditorState,
    callbacks: KeyBarItemEditorCallbacks,
) {
    var pickerSlot by remember(state.initialPickerTab) {
        mutableStateOf<KeyBarEditorSlot?>(KeyBarEditorSlot.Tap.takeIf { state.initialPickerTab != null })
    }
    var pickerInitialTab by remember(state.initialPickerTab) { mutableStateOf(state.initialPickerTab) }
    var composerSlot by remember(state.initialComposerSlot) { mutableStateOf(state.initialComposerSlot) }
    var composerKeyPickerOpen by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var tryDirection by remember { mutableStateOf<KeySwipe?>(null) }
    val requestBack = {
        handleItemEditorBack(state.discardMessage, { showDiscardDialog = true }, callbacks.onBack)
    }

    BackHandler(
        enabled = composerSlot != null && !composerKeyPickerOpen,
        onBack = { composerSlot = null },
    )
    BackHandler(
        enabled = composerSlot == null && pickerSlot == null && !showDiscardDialog && !showRemoveDialog,
        onBack = requestBack,
    )

    if (composerSlot == null) {
        ItemEditorScaffold(
            state = state,
            callbacks = callbacks,
            onBack = requestBack,
            tryDirection = tryDirection,
            onTryDirection = { tryDirection = it },
            onOpenPicker = { slot ->
                pickerSlot = slot
                pickerInitialTab = null
            },
            onRemoveRequest = { showRemoveDialog = true },
        )
    } else {
        ItemEditorSnippetComposerLayer(
            slot = composerSlot!!,
            state = state,
            callbacks = callbacks,
            onKeyPickerOpen = { composerKeyPickerOpen = it },
            onDone = {
                composerKeyPickerOpen = false
                composerSlot = null
            },
        )
    }

    ItemEditorPickerLayer(
        slot = pickerSlot.takeIf { composerSlot == null },
        initialTab = pickerInitialTab,
        state = state,
        callbacks = callbacks,
        onDismiss = { pickerSlot = null },
        onOpenSnippetComposer = { composerSlot = it },
    )
    ItemEditorDialogLayer(state, callbacks, showDiscardDialog, showRemoveDialog) {
        if (it) showDiscardDialog = false else showRemoveDialog = false
    }
}

private fun handleItemEditorBack(
    discardMessage: String?,
    onShowDiscard: () -> Unit,
    onBack: () -> Unit,
) {
    if (discardMessage != null) onShowDiscard() else onBack()
}

@Composable
private fun ItemEditorPickerLayer(
    slot: KeyBarEditorSlot?,
    initialTab: KeyBarActionPickerTab?,
    state: KeyBarItemEditorState,
    callbacks: KeyBarItemEditorCallbacks,
    onDismiss: () -> Unit,
    onOpenSnippetComposer: (KeyBarEditorSlot) -> Unit,
) {
    if (slot == null) return
    KeyBarActionPickerLayer(
        slot = slot,
        initialTab = initialTab,
        state = state,
        callbacks = callbacks,
        onDismiss = onDismiss,
        onOpenSnippetComposer = { onOpenSnippetComposer(slot) },
    )
}

@Composable
private fun ItemEditorDialogLayer(
    state: KeyBarItemEditorState,
    callbacks: KeyBarItemEditorCallbacks,
    showDiscard: Boolean,
    showRemove: Boolean,
    onDismiss: (Boolean) -> Unit,
) {
    ItemEditorDialogs(
        state = state,
        showDiscard = showDiscard,
        showRemove = showRemove,
        onDismissDiscard = { onDismiss(true) },
        onDismissRemove = { onDismiss(false) },
        onDiscard = {
            onDismiss(true)
            callbacks.onDiscard()
            callbacks.onBack()
        },
        onRemove = {
            onDismiss(false)
            callbacks.onRemove()
        },
    )
}

@Composable
private fun ItemEditorScaffold(
    state: KeyBarItemEditorState,
    callbacks: KeyBarItemEditorCallbacks,
    onBack: () -> Unit,
    tryDirection: KeySwipe?,
    onTryDirection: (KeySwipe?) -> Unit,
    onOpenPicker: (KeyBarEditorSlot) -> Unit,
    onRemoveRequest: () -> Unit,
) {
    Scaffold(topBar = { ItemEditorTopBar(state, onBack) }) { paddingValues ->
        ItemEditorBody(
            state = state,
            callbacks = callbacks,
            paddingValues = paddingValues,
            tryDirection = tryDirection,
            onTryDirection = onTryDirection,
            onOpenPicker = onOpenPicker,
            onRemoveRequest = onRemoveRequest,
        )
    }
}

@Composable
private fun ItemEditorSnippetComposerLayer(
    slot: KeyBarEditorSlot,
    state: KeyBarItemEditorState,
    callbacks: KeyBarItemEditorCallbacks,
    onKeyPickerOpen: (Boolean) -> Unit,
    onDone: () -> Unit,
) {
    KeyBarSnippetComposer(
        slot = slot,
        action = slot.actionFrom(state.draft),
        itemLabel = state.draft.label,
        onChange = { action ->
            callbacks.onEdit { draft ->
                when (slot) {
                    KeyBarEditorSlot.Tap -> draft.withTap(action ?: KeyBarAction.Text(""))
                    is KeyBarEditorSlot.Swipe -> draft.withSwipe(slot.direction, action)
                }
            }
        },
        onKeyPickerOpen = onKeyPickerOpen,
        onDone = onDone,
    )
}

@Composable
private fun ItemEditorTopBar(state: KeyBarItemEditorState, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(if (state.isNew) "New key" else "Edit key") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            Text(
                text = state.saveStatus,
                modifier = Modifier.padding(end = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (state.saveStatusIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun ItemEditorBody(
    state: KeyBarItemEditorState,
    callbacks: KeyBarItemEditorCallbacks,
    paddingValues: PaddingValues,
    tryDirection: KeySwipe?,
    onTryDirection: (KeySwipe?) -> Unit,
    onOpenPicker: (KeyBarEditorSlot) -> Unit,
    onRemoveRequest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        LabelSection(state, callbacks.onEdit)
        GestureSection(state.draft, tryDirection, onTryDirection, onOpenPicker)
        if (state.itemExists) {
            PositionSection(state.itemIndex, state.itemCount, callbacks.onMove, callbacks.onDuplicate)
            OutlinedButton(
                onClick = onRemoveRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .semantics { contentDescription = "Remove ${state.draft.label} from the bar" },
                shape = CircleShape,
            ) {
                Text("Remove from bar", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun KeyBarActionPickerLayer(
    slot: KeyBarEditorSlot,
    initialTab: KeyBarActionPickerTab?,
    state: KeyBarItemEditorState,
    callbacks: KeyBarItemEditorCallbacks,
    onDismiss: () -> Unit,
    onOpenSnippetComposer: () -> Unit,
) {
    KeyBarActionPicker(
        request = KeyBarActionPickerRequest(
            slot = slot,
            action = slot.actionFrom(state.draft),
            itemLabel = state.draft.label,
            initialTab = initialTab,
        ),
        callbacks = KeyBarActionPickerCallbacks(
            onDismiss = onDismiss,
            onPick = { action ->
                when (slot) {
                    KeyBarEditorSlot.Tap -> if (action != null) callbacks.onEdit { it.withTap(action) }
                    is KeyBarEditorSlot.Swipe -> callbacks.onEdit { it.withSwipe(slot.direction, action) }
                }
                onDismiss()
            },
            onEditSnippet = {
                onDismiss()
                onOpenSnippetComposer()
            },
        ),
    )
}

@Composable
private fun ItemEditorDialogs(
    state: KeyBarItemEditorState,
    showDiscard: Boolean,
    showRemove: Boolean,
    onDismissDiscard: () -> Unit,
    onDismissRemove: () -> Unit,
    onDiscard: () -> Unit,
    onRemove: () -> Unit,
) {
    if (showDiscard) {
        AlertDialog(
            onDismissRequest = onDismissDiscard,
            title = { Text("Discard this change?") },
            text = { Text(state.discardMessage.orEmpty()) },
            confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = onDismissDiscard) { Text("Keep editing") } },
        )
    }
    if (showRemove) {
        AlertDialog(
            onDismissRequest = onDismissRemove,
            title = { Text("Remove ${state.draft.label}?") },
            text = { Text("This key will be removed from the bar.") },
            confirmButton = {
                TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = onDismissRemove) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LabelSection(state: KeyBarItemEditorState, onEdit: ((KeyBarItemDraft) -> KeyBarItemDraft) -> Unit) {
    val labelError = state.draft.labelError()
    val supportingText = labelError
        ?: "Follows the tap action until you change it · ${state.draft.label.length}/${KeyBarLimits.MaxLabelLength}"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = state.draft.label,
            onValueChange = { value -> onEdit { it.copy(label = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Label on the bar") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            singleLine = true,
            isError = labelError != null || state.inlineError != null,
            supportingText = {
                Text(
                    supportingText,
                    color = if (labelError != null || state.inlineError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
        )
        state.inlineError?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun GestureSection(
    draft: KeyBarItemDraft,
    tryDirection: KeySwipe?,
    onTryDirection: (KeySwipe?) -> Unit,
    onOpenPicker: (KeyBarEditorSlot) -> Unit,
) {
    val swipeable = draft.tap.isSwipeable()
    val previewDirection = tryDirection?.toDirection().takeIf { swipeable }
    val previewAction = previewDirection?.let(draft.swipes::get)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Gestures", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Box(
            modifier = Modifier.fillMaxWidth().height(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (previewDirection != null) {
                Surface(
                    modifier = Modifier.zIndex(1f),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        text = "${previewDirection.arrow} ${previewAction?.summary() ?: "nothing set"}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        GestureGrid(
            draft = draft,
            swipeable = swipeable,
            previewDirection = previewDirection,
            onOpenPicker = onOpenPicker,
            onTryDirection = onTryDirection,
        )
        if (!swipeable) {
            Text(
                text = when (draft.tap) {
                    KeyBarAction.Ctrl -> "Ctrl keeps its own gestures."
                    KeyBarAction.Alt -> "Alt keeps its own gestures."
                    else -> "Arrows keeps its own gestures."
                },
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Tap a slot to change it. Drag the middle tile to try a swipe. Nothing is sent.",
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PositionSection(
    index: Int,
    itemCount: Int,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Position", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { onMove(-1) },
                enabled = index > 0,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("← Move left", maxLines = 1) }
            FilledTonalButton(
                onClick = { onMove(1) },
                enabled = index < itemCount - 1,
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("Move right →", maxLines = 1) }
        }
        FilledTonalButton(
            onClick = onDuplicate,
            enabled = itemCount < KeyBarLimits.MaxItems,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Duplicate") }
    }
}
