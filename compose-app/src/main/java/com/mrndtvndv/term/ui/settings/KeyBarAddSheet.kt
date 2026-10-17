@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mrndtvndv.term.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarHostAction
import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarKeyCatalog
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.KeyBarPunctuation
import com.mrndtvndv.term.navigation.KeyBarItemStart
import kotlinx.coroutines.launch

private data class AddTile(
    val action: KeyBarAction,
    val label: String,
    val spokenLabel: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
)

private data class AddSheetChoices(
    val keys: List<AddTile>,
    val symbols: List<AddTile>,
    val tools: List<AddTile>,
    val showCombo: Boolean,
    val showSnippet: Boolean,
)

private data class AddSheetUiState(
    val searchQuery: String,
    val choices: AddSheetChoices,
    val usedActions: Set<KeyBarAction>,
    val full: Boolean,
    val addedItem: AddedKeyBarItem?,
    val addFailed: Boolean,
)

private data class AddSheetCallbacks(
    val onSearchChange: (String) -> Unit,
    val onAdd: (KeyBarAction) -> Unit,
    val onUndo: () -> Unit,
    val onCreateCustom: (KeyBarItemStart) -> Unit,
)

private fun addSheetChoices(query: String): AddSheetChoices {
    val keys = KeyBarKeyCatalog.filter { matchesSearch(query, it.label, it.spokenName) }.map { key ->
        AddTile(KeyBarAction.Key(key.keyCode), key.label, key.spokenName)
    }
    val symbols = KeyBarPunctuation.filter { matchesSearch(query, it, symbolName(it)) }.map { symbol ->
        AddTile(KeyBarAction.Text(symbol), symbol, "$symbol, ${symbolName(symbol)}")
    }
    val tools = listOf(
        AddTile(KeyBarAction.Ctrl, "Ctrl", "Ctrl"),
        AddTile(KeyBarAction.Alt, "Alt", "Alt"),
        AddTile(KeyBarAction.Joystick, "Arrows", "Arrow joystick", Icons.Default.OpenWith),
        AddTile(KeyBarAction.Host(KeyBarHostAction.Git), "Git", "Git, Herdr only"),
        AddTile(KeyBarAction.Host(KeyBarHostAction.Sftp), "SFTP", "SFTP, Herdr only"),
    ).filter { matchesSearch(query, it.label, it.spokenLabel) }

    return AddSheetChoices(
        keys = keys,
        symbols = symbols,
        tools = tools,
        showCombo = matchesSearch(query, "Key combo", "Ctrl, Alt or Shift with any key"),
        showSnippet = matchesSearch(query, "Snippet", "Text, keys and Enter, in order"),
    )
}

private fun matchesSearch(query: String, vararg values: String): Boolean =
    query.isBlank() || values.any { it.contains(query, ignoreCase = true) }

@Composable
internal fun KeyBarAddSheet(
    items: List<KeyBarItem>,
    onDismiss: () -> Unit,
    onAddAction: (KeyBarAction) -> AddedKeyBarItem?,
    onUndoAdded: (String) -> Unit,
    onCreateCustom: (KeyBarItemStart) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var addedItem by remember { mutableStateOf<AddedKeyBarItem?>(null) }
    var addFailed by remember { mutableStateOf(false) }
    val full = items.size >= KeyBarLimits.MaxItems
    val usedActions = remember(items) { items.mapTo(HashSet()) { it.tap } }
    val choices = remember(searchQuery) { addSheetChoices(searchQuery.trim()) }
    val uiState = AddSheetUiState(searchQuery, choices, usedActions, full, addedItem, addFailed)
    val callbacks = AddSheetCallbacks(
        onSearchChange = { searchQuery = it },
        onAdd = { action ->
            if (!full) {
                val result = onAddAction(action)
                addedItem = result
                addFailed = result == null
            }
        },
        onUndo = {
            addedItem?.let { onUndoAdded(it.id) }
            addedItem = null
            addFailed = false
        },
        onCreateCustom = { start ->
            coroutineScope.launch {
                sheetState.hide()
                onCreateCustom(start)
            }
        },
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        AddSheetBody(uiState, callbacks)
    }
}

@Composable
private fun AddSheetBody(state: AddSheetUiState, callbacks: AddSheetCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AddSheetTitle()
        AddSheetStatus(state.addedItem, state.full || state.addFailed, callbacks.onUndo)
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = callbacks.onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text("Search keys") },
            shape = RoundedCornerShape(24.dp),
        )
        AddSheetSections(state.choices, state.usedActions, state.full, callbacks)
    }
}

@Composable
private fun AddSheetTitle() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Add key", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Tap adds it to the end",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddSheetStatus(addedItem: AddedKeyBarItem?, full: Boolean, onUndo: () -> Unit) {
    when {
        addedItem != null -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (full) {
                    "Added ${addedItem.label} · The key bar is full (${KeyBarLimits.MaxItems} keys)"
                } else {
                    "Added ${addedItem.label}"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onUndo) { Text("Undo") }
        }
        full -> Text(
            text = "The key bar is full (${KeyBarLimits.MaxItems} keys)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddSheetSections(
    choices: AddSheetChoices,
    usedActions: Set<KeyBarAction>,
    full: Boolean,
    callbacks: AddSheetCallbacks,
) {
    if (choices.keys.isNotEmpty()) {
        AddSection("Keys") { AddTileGrid(choices.keys, usedActions, full, callbacks.onAdd) }
    }
    if (choices.symbols.isNotEmpty()) {
        AddSection("Symbols") { AddTileGrid(choices.symbols, usedActions, full, callbacks.onAdd) }
    }
    if (choices.tools.isNotEmpty()) {
        AddSection("Modifiers and tools") {
            AddTileGrid(choices.tools, usedActions, full, callbacks.onAdd)
            Text(
                text = "Git and SFTP show only in Herdr sessions",
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
    if (choices.showCombo || choices.showSnippet) {
        AddSection("Custom") {
            if (choices.showCombo) {
                CustomAddRow("^C", "Key combo", "Ctrl, Alt or Shift with any key") {
                    callbacks.onCreateCustom(KeyBarItemStart.Combo)
                }
            }
            if (choices.showSnippet) {
                CustomAddRow("\"ab\"", "Snippet", "Text, keys and Enter, in order") {
                    callbacks.onCreateCustom(KeyBarItemStart.Snippet)
                }
            }
        }
    }
}

@Composable
private fun AddSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    })
}

@Composable
private fun AddTileGrid(
    tiles: List<AddTile>,
    usedActions: Set<KeyBarAction>,
    full: Boolean,
    onAdd: (KeyBarAction) -> Unit,
) {
    tiles.chunked(5).forEach { rowTiles ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rowTiles.forEach { tile ->
                AddActionTile(
                    tile = tile,
                    alreadyUsed = tile.action in usedActions,
                    enabled = !full,
                    onClick = { onAdd(tile.action) },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(5 - rowTiles.size) { Spacer(modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun AddActionTile(
    tile: AddTile,
    alreadyUsed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
        alreadyUsed -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        alreadyUsed -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(44.dp)
            .semantics {
                contentDescription = "Add ${tile.spokenLabel}"
                if (alreadyUsed) stateDescription = "Already on the bar"
            },
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                tile.icon?.let { icon ->
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = tile.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (alreadyUsed) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 3.dp)
                        .size(10.dp),
                )
            }
        }
    }
}

@Composable
private fun CustomAddRow(
    leading: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .semantics { contentDescription = "$title. $subtitle" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = leading,
                modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun symbolName(symbol: String): String = when (symbol) {
    "|" -> "pipe, vertical bar"
    "~" -> "tilde"
    "/" -> "slash"
    "\\" -> "backslash"
    "-" -> "hyphen, minus"
    "`" -> "backtick, grave accent"
    "*" -> "asterisk, star"
    "&" -> "ampersand"
    ";" -> "semicolon"
    "\$" -> "dollar sign"
    else -> symbol
}
