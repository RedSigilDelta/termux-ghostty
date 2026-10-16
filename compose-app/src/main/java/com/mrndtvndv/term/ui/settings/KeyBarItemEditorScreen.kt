package com.mrndtvndv.term.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarKeyCatalog
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.KeyBarPunctuation
import com.mrndtvndv.term.data.prefs.isSwipeable

private enum class ActionKind(val label: String) {
    None("None"),
    Key("Key"),
    Text("Text"),
    Sequence("Sequence"),
    Ctrl("Ctrl"),
    Alt("Alt"),
    Joystick("Joystick"),
}

private val TapKinds = listOf(
    ActionKind.Key,
    ActionKind.Text,
    ActionKind.Sequence,
    ActionKind.Ctrl,
    ActionKind.Alt,
    ActionKind.Joystick,
)
private val SwipeKinds = listOf(ActionKind.None, ActionKind.Key, ActionKind.Text, ActionKind.Sequence)

private fun KeyBarAction?.kind(): ActionKind = when (this) {
    null -> ActionKind.None
    is KeyBarAction.Key -> ActionKind.Key
    is KeyBarAction.Text -> ActionKind.Text
    is KeyBarAction.Macro -> ActionKind.Sequence
    KeyBarAction.Ctrl -> ActionKind.Ctrl
    KeyBarAction.Alt -> ActionKind.Alt
    KeyBarAction.Joystick -> ActionKind.Joystick
}

private fun ActionKind.defaultAction(): KeyBarAction? = when (this) {
    ActionKind.None -> null
    ActionKind.Key -> KeyBarAction.Key(KeyEvent.KEYCODE_ESCAPE)
    ActionKind.Text -> KeyBarAction.Text("")
    ActionKind.Sequence -> KeyBarAction.Macro(emptyList())
    ActionKind.Ctrl -> KeyBarAction.Ctrl
    ActionKind.Alt -> KeyBarAction.Alt
    ActionKind.Joystick -> KeyBarAction.Joystick
}

@Composable
fun KeyBarItemEditorScreen(
    draft: KeyBarItemDraft,
    isNew: Boolean,
    onEdit: ((KeyBarItemDraft) -> KeyBarItemDraft) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Add key" else "Edit key") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onSave, enabled = draft.isValid()) { Text("Save") }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LabelCard(draft = draft, onEdit = onEdit)
            TapCard(draft = draft, onEdit = onEdit)
            SwipesCard(draft = draft, onEdit = onEdit)
        }
    }
}

@Composable
private fun LabelCard(draft: KeyBarItemDraft, onEdit: ((KeyBarItemDraft) -> KeyBarItemDraft) -> Unit) {
    val labelError = draft.labelError()
    EditorCard {
        OutlinedTextField(
            value = draft.label,
            onValueChange = { value -> onEdit { it.copy(label = value) } },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Label") },
            singleLine = true,
            isError = labelError != null,
            supportingText = { Text(labelError ?: "${draft.label.length}/${KeyBarLimits.MaxLabelLength}") },
        )
    }
}

@Composable
private fun TapCard(draft: KeyBarItemDraft, onEdit: ((KeyBarItemDraft) -> KeyBarItemDraft) -> Unit) {
    EditorCard {
        Text("TAP", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        ActionEditor(
            title = "Tap",
            action = draft.tap,
            kinds = TapKinds,
            onChange = { action -> if (action != null) onEdit { it.withTap(action) } },
        )
    }
}

@Composable
private fun SwipesCard(draft: KeyBarItemDraft, onEdit: ((KeyBarItemDraft) -> KeyBarItemDraft) -> Unit) {
    EditorCard {
        Text("SWIPES", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (!draft.tap.isSwipeable()) {
            Description("Swipes work on key and macro taps only. Joystick, Ctrl and Alt keep their own gestures.")
            return@EditorCard
        }
        Text(
            text = "Each swipe fires once on release. Leave a direction unassigned to do nothing.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KeyBarDirection.entries.forEachIndexed { index, direction ->
            if (index > 0) HorizontalDivider()
            ActionEditor(
                title = "Swipe ${direction.name.lowercase()}",
                action = draft.swipes[direction],
                kinds = SwipeKinds,
                onChange = { action -> onEdit { it.withSwipe(direction, action) } },
            )
        }
    }
}

@Composable
private fun ActionEditor(
    title: String,
    action: KeyBarAction?,
    kinds: List<ActionKind>,
    onChange: (KeyBarAction?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            OptionMenu(
                selected = action.kind(),
                options = kinds,
                optionLabel = { it.label },
                onSelect = { kind -> if (kind != action.kind()) onChange(kind.defaultAction()) },
            )
        }
        ActionValue(action = action, onChange = onChange)
    }
}

@Composable
private fun ActionValue(action: KeyBarAction?, onChange: (KeyBarAction?) -> Unit) {
    when (action) {
        null -> Description("Unassigned. Swiping this way does nothing.")
        is KeyBarAction.Key -> OptionMenu(
            selected = action.keyCode,
            options = KeyBarKeyCatalog.map { it.keyCode },
            optionLabel = { keyLabel(it) },
            onSelect = { onChange(KeyBarAction.Key(it)) },
        )
        is KeyBarAction.Text -> MacroField(text = action.text, onChange = onChange)
        is KeyBarAction.Macro -> SequenceEditor(
            steps = action.steps,
            onChange = { steps -> onChange(KeyBarAction.Macro(steps)) },
        )
        KeyBarAction.Ctrl -> Description("Arms Ctrl for the next key. Hold to lock it on.")
        KeyBarAction.Alt -> Description("Arms Alt for the next key. Tap again to lock it on.")
        KeyBarAction.Joystick -> Description(
            "Sends arrow keys using the Arrow joystick mode in Keyboard settings.",
        )
    }
}

@Composable
private fun MacroField(text: String, onChange: (KeyBarAction?) -> Unit) {
    val error = KeyBarAction.Text(text).validationError()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { onChange(KeyBarAction.Text(it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Text") },
            minLines = 3,
            maxLines = 8,
            isError = error != null,
            supportingText = {
                val hint = "Sent exactly as typed, line breaks included. No Enter is added. " +
                    "${text.length}/${KeyBarLimits.MaxTextLength}"
                Text(error ?: hint)
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            KeyBarPunctuation.forEach { symbol ->
                OutlinedButton(
                    onClick = { onChange(KeyBarAction.Text(text + symbol)) },
                    enabled = text.length < KeyBarLimits.MaxTextLength,
                    modifier = Modifier.semantics { contentDescription = "Insert $symbol" },
                ) {
                    Text(symbol)
                }
            }
        }
    }
}

@Composable
internal fun Description(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Dropdown button that shows [selected] and lists [options]. */
@Composable
internal fun <T> OptionMenu(
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(optionLabel(selected))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
