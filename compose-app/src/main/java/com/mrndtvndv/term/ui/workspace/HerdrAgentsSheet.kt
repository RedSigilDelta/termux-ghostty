@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.mrndtvndv.processicons.agentIconResource
import com.mrndtvndv.term.herdr.HerdrAgentStatus
import com.mrndtvndv.term.herdr.HerdrPane
import com.mrndtvndv.term.herdr.HerdrState
import com.mrndtvndv.term.herdr.HerdrSnapshot
import com.mrndtvndv.term.herdr.HerdrTab
import com.mrndtvndv.term.herdr.panesIn
import com.mrndtvndv.term.herdr.tabsIn
import java.util.Locale
import kotlinx.coroutines.launch

/** Normal Herdr workspace/tab/pane browser, backed by the shared socket cache rather than CLI polling. */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun HerdrAgentsSheet(
    state: HerdrState,
    onNewTab: (workspaceId: String) -> Unit,
    onNewWorkspace: (sourceWorkspaceId: String) -> Unit,
    onSplitPane: (paneId: String, direction: String) -> Unit,
    onFocusPane: (HerdrPane) -> Unit,
    onFocusTab: (HerdrTab) -> Unit,
    onClosePane: (HerdrPane) -> Unit,
    onDismiss: () -> Unit,
) {
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pendingClose by remember { mutableStateOf<HerdrPane?>(null) }
    val snapshot = state.snapshot
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    var selecting by remember { mutableStateOf(false) }
    val workspaceId = snapshot?.focusedWorkspaceId
    val paneId = snapshot?.focusedPaneId

    fun dismissAndFocus(action: () -> Unit) {
        if (selecting) return
        selecting = true
        action()
        scope.launch {
            try {
                sheetState.hide()
                // Focus runs in the route's scope while this dialog finishes its exit animation.
                onDismiss()
            } finally {
                selecting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        KeepSheetAboveIme()
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Agents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                AgentsSplitButton(
                    enabled = workspaceId != null,
                    workspaceId = workspaceId,
                    paneId = paneId,
                    onNewTab = { id -> dismissAndFocus { onNewTab(id) } },
                    onNewWorkspace = { id -> dismissAndFocus { onNewWorkspace(id) } },
                    onSplitPane = { id, direction -> dismissAndFocus { onSplitPane(id, direction) } },
                )
            }
            state.error?.message?.let {
                Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            when {
                snapshot == null && state.error == null -> Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                snapshot != null && snapshot.workspaces.isEmpty() -> Text(
                    "No Herdr workspaces or agents", modifier = Modifier.padding(16.dp),
                )
                snapshot != null -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    snapshot.workspaces.sortedBy { it.number }.forEach { workspace ->
                        val expanded = workspace.id !in collapsed
                        item(key = "workspace:${workspace.id}") {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    collapsed = if (expanded) collapsed + workspace.id else collapsed - workspace.id
                                }.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(workspace.label, modifier = Modifier.weight(1f), maxLines = 1)
                                Text("${workspace.tabCount} tabs", style = MaterialTheme.typography.labelMedium)
                                Icon(
                                    if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                                    contentDescription = if (expanded) "Collapse workspace" else "Expand workspace",
                                )
                            }
                        }
                        if (expanded) snapshot.tabsIn(workspace.id).forEach { tab ->
                            item(key = "tab:${tab.id}") {
                                HerdrTabGroup(
                                    snapshot = snapshot,
                                    tab = tab,
                                    onFocusTab = { dismissAndFocus { onFocusTab(tab) } },
                                    onFocusPane = { pane -> dismissAndFocus { onFocusPane(pane) } },
                                    onClosePane = { pendingClose = it },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    pendingClose?.let { pane ->
        AlertDialog(
            onDismissRequest = { pendingClose = null },
            title = { Text("Close pane?") },
            text = { Text("This terminates whatever is running in ${pane.title ?: pane.id}.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingClose = null
                    onClosePane(pane)
                }) { Text("Close", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClose = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
@Suppress("LongMethod")
private fun AgentsSplitButton(
    enabled: Boolean,
    workspaceId: String?,
    paneId: String?,
    onNewTab: (workspaceId: String) -> Unit,
    onNewWorkspace: (sourceWorkspaceId: String) -> Unit,
    onSplitPane: (paneId: String, direction: String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val height = SplitButtonDefaults.SmallContainerHeight
    SplitButtonLayout(
        leadingButton = {
            SplitButtonDefaults.TonalLeadingButton(
                onClick = { workspaceId?.let { id -> onNewTab(id) } },
                enabled = enabled,
                shapes = SplitButtonDefaults.leadingButtonShapesFor(height),
                contentPadding = SplitButtonDefaults.leadingButtonContentPaddingFor(height),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "New tab",
                    modifier = Modifier.size(SplitButtonDefaults.leadingButtonIconSizeFor(height)),
                )
            }
        },
        trailingButton = {
            Box {
                SplitButtonDefaults.TonalTrailingButton(
                    checked = menuOpen,
                    onCheckedChange = { menuOpen = it },
                    enabled = enabled,
                    shapes = SplitButtonDefaults.trailingButtonShapesFor(height),
                    contentPadding = SplitButtonDefaults.trailingButtonContentPaddingFor(height),
                ) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = "More actions",
                        modifier = Modifier.size(SplitButtonDefaults.trailingButtonIconSizeFor(height)),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    // Keep terminal IME focus while showing actions above the sheet.
                    properties = PopupProperties(focusable = false),
                ) {
                    DropdownMenuItem(text = { Text("New workspace") }, onClick = {
                        menuOpen = false
                        workspaceId?.let { id -> onNewWorkspace(id) }
                    })
                    DropdownMenuItem(
                        text = { Text("Split right") },
                        enabled = paneId != null,
                        onClick = {
                            menuOpen = false
                            paneId?.let { id -> onSplitPane(id, "right") }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Split down") },
                        enabled = paneId != null,
                        onClick = {
                            menuOpen = false
                            paneId?.let { id -> onSplitPane(id, "down") }
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun HerdrTabGroup(
    snapshot: HerdrSnapshot,
    tab: HerdrTab,
    onFocusTab: () -> Unit,
    onFocusPane: (HerdrPane) -> Unit,
    onClosePane: (HerdrPane) -> Unit,
) {
    val panes = snapshot.panesIn(tab.id)
    val shape = RoundedCornerShape(12.dp)
    val groupModifier = if (panes.size > 1) {
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), shape)
    } else {
        Modifier.fillMaxWidth()
    }
    Column(modifier = groupModifier) {
        if (panes.isEmpty()) Text(
            tab.label.ifBlank { "Terminal" },
            modifier = Modifier.fillMaxWidth().clickable(onClick = onFocusTab)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        panes.forEach { pane ->
            val agent = snapshot.agents.firstOrNull { it.terminalId == pane.terminalId }
            HerdrAgentRow(
                pane = pane,
                title = agent?.name ?: pane.title ?: pane.terminalTitle ?: pane.label
                    ?: tab.label.ifBlank { "Terminal" },
                onFocus = { onFocusPane(pane) },
                onClose = { onClosePane(pane) },
            )
        }
    }
}

@Composable
private fun HerdrAgentRow(pane: HerdrPane, title: String, onFocus: () -> Unit, onClose: () -> Unit) {
    var menuVisible by remember(pane.terminalId) { mutableStateOf(false) }
    val statusColor = when (pane.agentStatus) {
        HerdrAgentStatus.Working -> MaterialTheme.colorScheme.primary
        HerdrAgentStatus.Blocked -> MaterialTheme.colorScheme.error
        HerdrAgentStatus.Done -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val background = if (pane.focused) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().background(background)
                .combinedClickable(onClick = onFocus, onLongClick = { menuVisible = true })
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val icon = agentIconResource(pane.agent)
            if (icon != null) {
                Icon(
                    painterResource(icon), contentDescription = null,
                    tint = statusColor, modifier = Modifier.size(24.dp),
                )
            } else {
                Icon(
                    if (pane.agent == null) Icons.Default.Terminal else Icons.Default.SmartToy,
                    contentDescription = null, tint = statusColor, modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                (pane.displayAgent ?: pane.agent)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (pane.agent != null) Text(
                pane.agentStatus.name.lowercase(Locale.ROOT),
                color = statusColor, style = MaterialTheme.typography.labelMedium,
            )
        }
        DropdownMenu(
            expanded = menuVisible,
            onDismissRequest = { menuVisible = false },
            properties = PopupProperties(focusable = false),
        ) {
            DropdownMenuItem(text = { Text("Close pane") }, onClick = {
                menuVisible = false
                onClose()
            })
        }
    }
}
