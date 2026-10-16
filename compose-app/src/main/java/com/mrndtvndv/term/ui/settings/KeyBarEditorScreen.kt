package com.mrndtvndv.term.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarLimits

@Composable
fun KeyBarEditorScreen(
    items: List<KeyBarItem>,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDelete: (String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Key bar") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { confirmReset = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset to defaults")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tap an item to edit it. A swipe fires its action once when you lift your finger. " +
                    "Drag horizontally on the empty part of the bar to scroll it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PreviewCard(items)
            ItemsCard(
                items = items,
                onEdit = onEdit,
                onMove = onMove,
                onDelete = onDelete,
            )
            AddSection(itemCount = items.size, onAdd = onAdd)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (confirmReset) {
        ResetDialog(
            onConfirm = {
                confirmReset = false
                onReset()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun ResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset key bar?") },
        text = { Text("Restore the default keys. Your custom items will be removed.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Reset") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun EditorCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun PreviewCard(items: List<KeyBarItem>) {
    EditorCard {
        Text("PREVIEW", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (items.isEmpty()) {
            Text("No keys. The bar shows nothing.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items.forEach { PreviewItem(it) }
            }
        }
        Text(
            text = "Preview only. Nothing is sent to the terminal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewItem(item: KeyBarItem) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        item.swipeHints().forEach { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ItemsCard(
    items: List<KeyBarItem>,
    onEdit: (String) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onDelete: (String) -> Unit,
) {
    EditorCard {
        Text("ITEMS", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (items.isEmpty()) {
            Text(
                text = "No items yet. Add one, or reset to the defaults.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        items.forEachIndexed { index, item ->
            HorizontalDivider()
            ItemRow(
                item = item,
                canMoveUp = index > 0,
                canMoveDown = index < items.lastIndex,
                onEdit = { onEdit(item.id) },
                onMoveUp = { onMove(index, index - 1) },
                onMoveDown = { onMove(index, index + 1) },
                onDelete = { onDelete(item.id) },
            )
        }
    }
}

@Composable
private fun ItemRow(
    item: KeyBarItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    val summary = (listOf("Tap ${item.tap.shortLabel()}") + item.swipeHints()).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Edit ${item.label}", onClick = onEdit)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${item.label} up")
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${item.label} down")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${item.label}")
        }
    }
}

@Composable
private fun AddSection(itemCount: Int, onAdd: () -> Unit) {
    Button(onClick = onAdd, enabled = itemCount < KeyBarLimits.MaxItems) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Add key")
    }
    if (itemCount >= KeyBarLimits.MaxItems) {
        Text(
            text = "The key bar holds up to ${KeyBarLimits.MaxItems} items.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
