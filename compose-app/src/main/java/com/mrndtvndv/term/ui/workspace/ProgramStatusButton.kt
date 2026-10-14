@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.processicons.agentIconResource
import com.mrndtvndv.term.server.ProgramBlockKind
import com.mrndtvndv.term.server.ProgramState
import com.mrndtvndv.term.server.ProgramStatusItem
import com.mrndtvndv.term.server.ProgramStatusState
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Agent button for terminals without a Herdr binding. It sits in the Herdr button's slot and
 * shows OSC 7501 records. The button is tinted by the most urgent state.
 */
@Composable
fun ProgramStatusButton(
    state: ProgramStatusState,
    fabOpacity: Float,
    onOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAgents by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val coroutineScope = rememberCoroutineScope()
    val urgent = state.mostUrgent()

    fun dismissAgents() {
        coroutineScope.launch {
            sheetState.hide()
            showAgents = false
        }
    }

    SmallFloatingActionButton(
        onClick = {
            showAgents = true
            onOpened()
        },
        modifier = modifier.alpha(fabOpacity.coerceIn(0f, 1f)),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = urgent?.let { statusColor(it) } ?: MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Icon(
            imageVector = Icons.Default.SmartToy,
            contentDescription = "Show agent status",
        )
    }

    if (showAgents) {
        ModalBottomSheet(
            onDismissRequest = { showAgents = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            KeepSheetAboveIme()
            ProgramStatusList(state = state, onItemClick = { dismissAgents() })
        }
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

/** Brand mark for the record's app, from the same set the Herdr rows use. */
@Composable
private fun ProgramAgentIcon(app: String?, tint: Color, modifier: Modifier) {
    val iconResource = app?.let { agentIconResource(agentIconKey(it)) }
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

/** Maps OSC app names such as `claude-code` onto the Herdr agent names that have brand marks. */
private fun agentIconKey(app: String): String {
    val name = app.lowercase(Locale.ROOT)
    return when {
        name.startsWith("claude") -> "claude"
        name.startsWith("codex") -> "codex"
        else -> name
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
