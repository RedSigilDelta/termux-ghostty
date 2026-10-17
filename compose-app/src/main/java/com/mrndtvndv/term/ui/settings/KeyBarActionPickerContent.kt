@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mrndtvndv.term.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarHostAction
import com.mrndtvndv.term.data.prefs.KeyBarKeyCatalog
import com.mrndtvndv.term.data.prefs.MacroKeyCatalog
import com.mrndtvndv.term.data.prefs.MacroStep
import com.mrndtvndv.term.data.prefs.isSupported

private enum class KeyPickerCategory(val label: String) {
    Special("Special"),
    Letters("Letters"),
    Digits("Digits"),
    Symbols("Symbols"),
}

private data class KeyPickerOption(val keyCode: Int, val label: String, val spokenName: String)

@Composable
internal fun KeyTab(
    pendingKey: MacroStep.Key,
    unsupportedMessage: String?,
    isSwipeSlot: Boolean,
    isKeyOnlyInsert: Boolean,
    onModifierChange: ((MacroStep.Key) -> MacroStep.Key) -> Unit,
    onPickKey: (Int) -> Unit,
) {
    var category by remember { mutableStateOf(KeyPickerCategory.Letters) }
    val comboError = unsupportedMessage ?: if (!pendingKey.isSupported()) {
        "${pendingKey.summary()} isn't supported"
    } else {
        null
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = pendingKey.summary(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "Hold down, for this ${if (isSwipeSlot) "swipe" else "tap"} only",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ModifierChoice("Ctrl", pendingKey.ctrl) { onModifierChange { it.copy(ctrl = !it.ctrl) } }
            ModifierChoice("Alt", pendingKey.alt) { onModifierChange { it.copy(alt = !it.alt) } }
            ModifierChoice("Shift", pendingKey.shift) { onModifierChange { it.copy(shift = !it.shift) } }
        }
        KeyPickerCategories(category) { category = it }
        KeyPickerGrid(category.options(), pendingKey, onPickKey)
        Text(
            text = comboError ?: if (isKeyOnlyInsert) {
                "Picking a key inserts it into the snippet and closes this sheet."
            } else {
                "Picking a key sets the action and closes this sheet."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (comboError != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ModifierChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontFamily = FontFamily.Monospace) },
        modifier = Modifier.heightIn(min = 44.dp),
    )
}

@Composable
private fun KeyPickerCategories(selected: KeyPickerCategory, onSelect: (KeyPickerCategory) -> Unit) {
    androidx.compose.material3.PrimaryTabRow(selectedTabIndex = selected.ordinal) {
        KeyPickerCategory.entries.forEach { category ->
            androidx.compose.material3.Tab(
                selected = selected == category,
                onClick = { onSelect(category) },
                text = { Text(category.label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun KeyPickerGrid(
    options: List<KeyPickerOption>,
    pendingKey: MacroStep.Key,
    onPick: (Int) -> Unit,
) {
    options.chunked(6).forEach { rowOptions ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rowOptions.forEach { option ->
                val candidate = pendingKey.copy(keyCode = option.keyCode)
                val supported = candidate.isSupported()
                val selected = pendingKey.keyCode == option.keyCode
                val background = when {
                    !supported -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f)
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
                val foreground = when {
                    !supported -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Surface(
                    onClick = { onPick(option.keyCode) },
                    enabled = supported,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .semantics {
                            contentDescription = "${candidate.summary()}, ${option.spokenName}"
                            if (selected) stateDescription = "Selected"
                        },
                    shape = RoundedCornerShape(10.dp),
                    color = background,
                    contentColor = foreground,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = option.label,
                            modifier = Modifier.padding(horizontal = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            repeat(6 - rowOptions.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

private fun KeyPickerCategory.options(): List<KeyPickerOption> = when (this) {
    KeyPickerCategory.Special -> KeyBarKeyCatalog.map { KeyPickerOption(it.keyCode, it.label, it.spokenName) }
    KeyPickerCategory.Letters -> MacroKeyCatalog.filter {
        it.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z || it.keyCode == KeyEvent.KEYCODE_SPACE
    }.map { KeyPickerOption(it.keyCode, it.label, it.spokenName) }
    KeyPickerCategory.Digits -> MacroKeyCatalog.filter {
        it.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9
    }.map { KeyPickerOption(it.keyCode, it.label, it.spokenName) }
    KeyPickerCategory.Symbols -> MacroKeyCatalog.filterNot { key ->
        key.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
            key.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
            key.keyCode == KeyEvent.KEYCODE_SPACE ||
            KeyBarKeyCatalog.any { it.keyCode == key.keyCode }
    }.map { KeyPickerOption(it.keyCode, it.label, it.spokenName) }
}

@Composable
internal fun SnippetTab(action: KeyBarAction?, onEditSnippet: () -> Unit) {
    val currentSummary = action?.summary()?.takeIf { it.isNotBlank() } ?: "No snippet is set"

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Current snippet", style = MaterialTheme.typography.labelLarge)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            Text(
                text = currentSummary,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onEditSnippet,
            modifier = Modifier.heightIn(min = 44.dp),
        ) { Text("Edit snippet") }
    }
}

@Composable
internal fun ModifierTab(onPick: (KeyBarAction?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerActionRow("Ctrl", "Arms Ctrl for the next key. Hold to lock it on.") {
            onPick(KeyBarAction.Ctrl)
        }
        PickerActionRow("Alt", "Arms Alt for the next key. Tap again to lock it on.") {
            onPick(KeyBarAction.Alt)
        }
        PickerActionRow("Arrows", "Sends arrow keys using the Arrow joystick mode in Keyboard settings.") {
            onPick(KeyBarAction.Joystick)
        }
    }
}

@Composable
internal fun HerdrTab(onPick: (KeyBarAction?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(KeyBarHostAction.Git, KeyBarHostAction.Sftp).forEach { kind ->
            val label = kind.displayName()
            Surface(
                onClick = { onPick(KeyBarAction.Host(kind)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .semantics { contentDescription = "$label. Shown only in Herdr sessions" },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = kind.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Shown only in Herdr sessions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { contentDescription = "$title. $subtitle" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun NoneTab(onPick: (KeyBarAction?) -> Unit) {
    Surface(
        onClick = { onPick(null) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { contentDescription = "Clear this swipe" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("Clear this swipe", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
