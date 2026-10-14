@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.processicons.programIconResource
import com.termux.terminal.compose.session.ProgramBlockKind
import com.termux.terminal.compose.session.ProgramState
import com.termux.terminal.compose.session.ProgramStatusItem
import com.termux.terminal.compose.session.ProgramStatusState
import kotlinx.coroutines.launch

/**
 * Agents button for the key bar: the most urgent record's state as a tinted icon, with a ring while
 * working and a dot when blocked or done. Only shown while the terminal has records.
 */
@Composable
fun AgentsButton(state: ProgramStatusState, onClick: () -> Unit) {
    val urgent = state.mostUrgent()?.state
    val colors = MaterialTheme.colorScheme
    val alarm = urgent == ProgramState.Blocked || urgent == ProgramState.Error
    val iconTint = if (alarm) colors.error else colors.onSurface
    val dotColor = when (urgent) {
        ProgramState.Blocked -> colors.error
        ProgramState.Done -> colors.primary
        else -> null
    }
    Box(
        Modifier
            .focusProperties { canFocus = false }
            .size(36.dp)
            .semantics { contentDescription = "Show agent status" }
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (urgent == ProgramState.Working) {
            CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 2.dp)
        }
        Icon(imageVector = Icons.Rounded.SmartToy, contentDescription = null, tint = iconTint)
        dotColor?.let { color ->
            Box(Modifier.align(Alignment.TopEnd).size(8.dp).background(color, CircleShape))
        }
    }
}

/** Bottom sheet listing every agent record for the terminal. Animates closed before calling [onDismiss]. */
@Composable
fun ProgramStatusSheet(state: ProgramStatusState, visible: Boolean, onDismiss: () -> Unit) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val coroutineScope = rememberCoroutineScope()
    if (!visible) return

    fun dismiss() {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        KeepSheetAboveIme()
        ProgramStatusList(state = state, onItemClick = { dismiss() })
    }
}

@Composable
private fun ProgramStatusList(state: ProgramStatusState, onItemClick: () -> Unit) {
    val rows = remember(state) { state.orderedRows() }
    if (rows.isEmpty()) {
        Text(
            text = "No agents reporting",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        items(rows, key = { it.item.id }) { row ->
            ProgramStatusRowView(row = row, onClick = onItemClick)
        }
    }
}

@Composable
private fun ProgramStatusRowView(row: ProgramStatusRow, onClick: () -> Unit) {
    val item = row.item
    val color = statusColor(item)
    val headline = item.title ?: item.app ?: "Program"
    val supporting = item.msg ?: item.app?.takeIf { it != headline }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp + (row.depth * 16).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgramAgentIcon(app = item.app, tint = color, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = headline,
                color = if (item.state == ProgramState.Working) color else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            supporting?.let { text ->
                Text(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val progress = item.progress
            if (progress != null && (item.state == ProgramState.Working || item.state == ProgramState.Blocked)) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = statusLabel(item),
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Brand mark for the record's app. */
@Composable
private fun ProgramAgentIcon(app: String?, tint: Color, modifier: Modifier) {
    val iconResource = programIconResource(app)
    if (iconResource != null) {
        Icon(
            painter = painterResource(iconResource),
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
    } else {
        Icon(
            imageVector = if (app.isNullOrBlank()) Icons.Default.Terminal else Icons.Default.AccountTree,
            contentDescription = null,
            tint = tint,
            modifier = modifier,
        )
    }
}

private data class ProgramStatusRow(val item: ProgramStatusItem, val depth: Int)

/** Records nested under their parent path. Each level is ordered by urgency, then recency. */
private fun ProgramStatusState.orderedRows(): List<ProgramStatusRow> {
    val ids = items.mapTo(HashSet()) { it.id }
    val childrenByParent = mutableMapOf<String, MutableList<ProgramStatusItem>>()
    val topLevel = mutableListOf<ProgramStatusItem>()
    for (item in items) {
        val parent = item.id.substringBeforeLast('/', missingDelimiterValue = "")
        if (item.id.contains('/') && parent in ids) {
            childrenByParent.getOrPut(parent) { mutableListOf() }.add(item)
        } else {
            topLevel.add(item)
        }
    }

    val byUrgency = compareByDescending<ProgramStatusItem> { rank(it) }.thenByDescending { it.updated }
    val rows = mutableListOf<ProgramStatusRow>()
    fun visit(item: ProgramStatusItem, depth: Int) {
        rows.add(ProgramStatusRow(item, depth))
        childrenByParent[item.id]?.sortedWith(byUrgency)?.forEach { visit(it, depth + 1) }
    }
    topLevel.sortedWith(byUrgency).forEach { visit(it, 0) }
    return rows
}

private fun statusLabel(item: ProgramStatusItem): String = when (item.state) {
    ProgramState.Idle -> "Idle"
    ProgramState.Working -> "Working"
    ProgramState.Done -> "Done"
    ProgramState.Error -> "Failed"
    ProgramState.Blocked -> when (item.blockKind) {
        ProgramBlockKind.Permission -> "Needs approval"
        ProgramBlockKind.Question -> "Needs an answer"
        ProgramBlockKind.Auth -> "Needs sign-in"
        null -> "Needs you"
    }
}

@Composable
private fun statusColor(item: ProgramStatusItem): Color = when (item.state) {
    ProgramState.Blocked, ProgramState.Error -> MaterialTheme.colorScheme.error
    ProgramState.Done -> MaterialTheme.colorScheme.tertiary
    ProgramState.Working -> MaterialTheme.colorScheme.primary
    ProgramState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
}
