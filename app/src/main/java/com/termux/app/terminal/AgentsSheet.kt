@file:OptIn(ExperimentalMaterial3Api::class)

package com.termux.app.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.processicons.programIconResource
import com.termux.terminal.compose.session.ProgramBlockKind
import com.termux.terminal.compose.session.ProgramState
import com.termux.terminal.compose.session.ProgramStatusItem

@Composable
fun AgentsSheet(state: AgentsUiState, onSelect: (AgentEntry) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    if (state.sheetVisible) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Text(
                text = "Agents",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (state.entries.isEmpty()) {
                Text(
                    text = "No agents",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            } else {
                AgentList(entries = state.entries, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun AgentList(entries: List<AgentEntry>, onSelect: (AgentEntry) -> Unit) {
    val multipleWorkspaces = entries.map { it.workspaceId }.distinct().size > 1
    LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        entries.groupBy { it.workspaceId }.values.forEach { group ->
            if (multipleWorkspaces) {
                item {
                    Text(
                        text = group.first().workspaceName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
            items(group) { entry ->
                AgentRow(entry = entry, onClick = { onSelect(entry) })
            }
        }
    }
}

@Composable
private fun AgentRow(entry: AgentEntry, onClick: () -> Unit) {
    val item = entry.item
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        supportingContent = {
            Column {
                Text(
                    text = item.msg ?: entry.tabTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.progress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        },
        leadingContent = { AgentIcon(app = item.app) },
        trailingContent = { StateChip(item = item) },
    ) {
        Text(
            text = item.title ?: item.app ?: entry.tabTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AgentIcon(app: String?) {
    val iconResource = programIconResource(app)
    if (iconResource != null) {
        Icon(painter = painterResource(iconResource), contentDescription = null, modifier = Modifier.size(24.dp))
    } else {
        Icon(imageVector = Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun StateChip(item: ProgramStatusItem) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (item.state) {
        ProgramState.Blocked, ProgramState.Error -> colors.errorContainer to colors.onErrorContainer
        ProgramState.Done -> colors.primaryContainer to colors.onPrimaryContainer
        ProgramState.Working -> colors.secondaryContainer to colors.onSecondaryContainer
        ProgramState.Idle -> colors.surfaceContainerHighest to colors.onSurface
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            text = stateLabel(item),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun stateLabel(item: ProgramStatusItem): String = when (item.state) {
    ProgramState.Idle -> "Idle"
    ProgramState.Working -> "Working"
    ProgramState.Done -> "Done"
    ProgramState.Error -> "Error"
    ProgramState.Blocked -> when (item.blockKind) {
        ProgramBlockKind.Permission -> "Needs you · permission"
        ProgramBlockKind.Question -> "Needs you · question"
        ProgramBlockKind.Auth -> "Needs you · auth"
        null -> "Needs you"
    }
}
