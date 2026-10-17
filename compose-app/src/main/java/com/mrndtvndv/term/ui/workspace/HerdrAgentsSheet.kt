@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.mrndtvndv.term.ui.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.mrndtvndv.processicons.agentIconResource
import com.mrndtvndv.term.herdr.HerdrAgentStatus
import com.mrndtvndv.term.herdr.HerdrPane
import com.mrndtvndv.term.herdr.HerdrState
import com.mrndtvndv.term.herdr.HerdrSnapshot
import com.mrndtvndv.term.herdr.HerdrTab
import com.mrndtvndv.term.herdr.HerdrWorkspace
import com.mrndtvndv.term.herdr.panesIn
import com.mrndtvndv.term.herdr.tabsIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Normal Herdr workspace/tab/pane browser, backed by the shared socket cache rather than CLI polling. */
@Suppress("LongMethod", "LongParameterList")
@Composable
fun HerdrAgentsSheet(
    state: HerdrState,
    collapsedWorkspaceIds: Set<String>,
    onWorkspaceCollapsedChange: (workspaceId: String, collapsed: Boolean) -> Unit,
    overlayAgentsFab: Boolean,
    onNewTab: (workspaceId: String) -> Unit,
    onNewWorkspace: (sourceWorkspaceId: String) -> Unit,
    onSplitPane: (paneId: String, direction: String) -> Unit,
    onFocusPane: (HerdrPane) -> Unit,
    onFocusTab: (HerdrTab) -> Unit,
    onClosePane: suspend (HerdrPane) -> Boolean,
    onDismiss: () -> Unit,
) {
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
        @Composable
        fun splitButton(modifier: Modifier) {
            AgentsSplitButton(
                enabled = workspaceId != null,
                workspaceId = workspaceId,
                paneId = paneId,
                elevated = overlayAgentsFab,
                onNewTab = { id -> dismissAndFocus { onNewTab(id) } },
                onNewWorkspace = { id -> dismissAndFocus { onNewWorkspace(id) } },
                onSplitPane = { id, direction -> dismissAndFocus { onSplitPane(id, direction) } },
                modifier = modifier,
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                state.error?.message?.let {
                    Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                when {
                    snapshot == null && state.error == null -> Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    snapshot != null && snapshot.workspaces.isEmpty() -> Text(
                        "No Herdr workspaces or agents",
                        modifier = Modifier.padding(16.dp),
                    )
                    snapshot != null -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    ) {
                        snapshot.workspaces.sortedBy { it.number }.forEach { workspace ->
                            val expanded = workspace.id !in collapsedWorkspaceIds
                            item(key = "workspace:${workspace.id}") {
                                WorkspaceGroupItem(
                                    workspace = workspace,
                                    expanded = expanded,
                                    snapshot = snapshot,
                                    onToggle = {
                                        onWorkspaceCollapsedChange(workspace.id, expanded)
                                    },
                                    onFocusTab = { tab -> dismissAndFocus { onFocusTab(tab) } },
                                    onFocusPane = { pane -> dismissAndFocus { onFocusPane(pane) } },
                                    onClosePane = onClosePane,
                                )
                            }
                        }
                    }
                }
                if (!overlayAgentsFab) {
                    splitButton(
                        Modifier
                            .align(Alignment.End)
                            .padding(end = 16.dp),
                    )
                }
            }
            if (overlayAgentsFab) {
                splitButton(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun LazyItemScope.WorkspaceGroupItem(
    workspace: HerdrWorkspace,
    expanded: Boolean,
    snapshot: HerdrSnapshot,
    onToggle: () -> Unit,
    onFocusTab: (HerdrTab) -> Unit,
    onFocusPane: (HerdrPane) -> Unit,
    onClosePane: suspend (HerdrPane) -> Boolean,
) {
    val motion = MaterialTheme.motionScheme
    Column(modifier = itemMotion()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(workspace.label, modifier = Modifier.weight(1f), maxLines = 1)
            if (!expanded) {
                // Collapsed groups still surface their agents, most urgent first.
                val statuses = snapshot.panes.filter { it.workspaceId == workspace.id && it.agent != null }
                    .map { it.agentStatus }.toSet()
                Row(
                    modifier = Modifier.padding(end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(HerdrAgentStatus.Blocked, HerdrAgentStatus.Working, HerdrAgentStatus.Done)
                        .filter { it in statuses }
                        .forEach { AgentStatusIndicator(it, agentStatusColor(it)) }
                }
            }
            Text("${workspace.tabCount} tabs", style = MaterialTheme.typography.labelMedium)
            Icon(
                if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                contentDescription = if (expanded) "Collapse workspace" else "Expand workspace",
            )
        }
        // The group's own height animates, so the sheet's anchor tracks it every frame instead of
        // snapping when the rows leave the list. Rows stay put while the clip window reveals them.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(motion.defaultSpatialSpec(), expandFrom = Alignment.Top) +
                fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec(), shrinkTowards = Alignment.Top) +
                fadeOut(motion.defaultEffectsSpec()),
        ) {
            Column {
                snapshot.tabsIn(workspace.id).forEach { tab ->
                    key(tab.id) {
                        HerdrTabGroup(
                            snapshot = snapshot,
                            tab = tab,
                            onFocusTab = { onFocusTab(tab) },
                            onFocusPane = onFocusPane,
                            onClosePane = onClosePane,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun AgentsSplitButton(
    enabled: Boolean,
    workspaceId: String?,
    paneId: String?,
    elevated: Boolean,
    onNewTab: (workspaceId: String) -> Unit,
    onNewWorkspace: (sourceWorkspaceId: String) -> Unit,
    onSplitPane: (paneId: String, direction: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val height = SplitButtonDefaults.MediumContainerHeight
    val elevation = if (elevated) {
        ButtonDefaults.buttonElevation(
            defaultElevation = 6.dp,
            pressedElevation = 6.dp,
            focusedElevation = 6.dp,
            hoveredElevation = 8.dp,
        )
    } else {
        null
    }
    SplitButtonLayout(
        modifier = modifier,
        leadingButton = {
            SplitButtonDefaults.TonalLeadingButton(
                onClick = { workspaceId?.let { id -> onNewTab(id) } },
                enabled = enabled,
                shapes = SplitButtonDefaults.leadingButtonShapesFor(height),
                elevation = elevation,
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
                    elevation = elevation,
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
    onClosePane: suspend (HerdrPane) -> Boolean,
) {
    // Rows still animating out stay at their last index after the snapshot drops them, so the group never jumps.
    val closing = remember { mutableStateMapOf<String, IndexedValue<HerdrPane>>() }
    val panes = snapshot.panesIn(tab.id).toMutableList().apply {
        closing.values.sortedBy { it.index }.forEach { (index, pane) ->
            if (none { it.terminalId == pane.terminalId }) add(index.coerceAtMost(size), pane)
        }
    }
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
        panes.forEachIndexed { index, pane ->
            val agent = snapshot.agents.firstOrNull { it.terminalId == pane.terminalId }
            key(pane.terminalId) {
                HerdrAgentRow(
                    pane = pane,
                    title = agent?.name ?: pane.title ?: pane.terminalTitle ?: pane.label
                        ?: tab.label.ifBlank { "Terminal" },
                    onFocus = { onFocusPane(pane) },
                    onClose = { onClosePane(pane) },
                    onClosingChange = { isClosing ->
                        if (isClosing) {
                            closing[pane.terminalId] = IndexedValue(index, pane)
                        } else {
                            closing -= pane.terminalId
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HerdrAgentRow(
    pane: HerdrPane,
    title: String,
    onFocus: () -> Unit,
    onClose: suspend () -> Boolean,
    onClosingChange: (Boolean) -> Unit,
) {
    val swipeState = rememberSwipeToDismissBoxState()
    val visibility = remember { MutableTransitionState(true) }
    val scope = rememberCoroutineScope()
    // Optimistic: the row collapses while the close is in flight, and expands and slides back if Herdr refuses it.
    LaunchedEffect(swipeState.settledValue) {
        if (swipeState.settledValue == SwipeToDismissBoxValue.Settled) return@LaunchedEffect
        onClosingChange(true)
        visibility.targetState = false
        if (onClose()) {
            snapshotFlow { visibility.isIdle }.first { it }
            onClosingChange(false)
        } else {
            visibility.targetState = true
            onClosingChange(false)
            swipeState.reset()
        }
    }
    val motion = MaterialTheme.motionScheme
    val dragging = swipeState.dismissDirection != SwipeToDismissBoxValue.Settled
    val cornerRadius by animateDpAsState(if (dragging) 24.dp else 0.dp, motion.defaultEffectsSpec())
    AnimatedVisibility(
        visibleState = visibility,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.defaultEffectsSpec()),
    ) {
        SwipeToDismissBox(
            state = swipeState,
            gesturesEnabled = visibility.targetState,
            backgroundContent = { HerdrAgentRowCloseBackground(swipeState.dismissDirection) },
        ) {
            Box(modifier = Modifier.clip(RoundedCornerShape(cornerRadius))) {
                HerdrAgentRowContent(pane, title, onFocus) {
                    scope.launch { swipeState.dismiss(SwipeToDismissBoxValue.EndToStart) }
                }
            }
        }
    }
}

@Composable
private fun HerdrAgentRowCloseBackground(direction: SwipeToDismissBoxValue) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
            Alignment.CenterStart
        } else {
            Alignment.CenterEnd
        },
    ) {
        Icon(
            Icons.Default.Close, contentDescription = "Close pane",
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun LazyItemScope.itemMotion(): Modifier {
    val motion = MaterialTheme.motionScheme
    return Modifier.animateItem(
        fadeInSpec = motion.defaultEffectsSpec(),
        placementSpec = motion.defaultSpatialSpec(),
        fadeOutSpec = motion.defaultEffectsSpec(),
    )
}

@Composable
private fun HerdrAgentRowContent(pane: HerdrPane, title: String, onFocus: () -> Unit, onClose: () -> Unit) {
    var menuVisible by remember(pane.terminalId) { mutableStateOf(false) }
    val statusColor = agentStatusColor(pane.agentStatus)
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
            if (pane.agent != null) AgentStatusIndicator(pane.agentStatus, statusColor)
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

@Composable
private fun agentStatusColor(status: HerdrAgentStatus): Color = when (status) {
    HerdrAgentStatus.Working -> MaterialTheme.colorScheme.primary
    HerdrAgentStatus.Blocked -> MaterialTheme.colorScheme.error
    HerdrAgentStatus.Done -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun AgentStatusIndicator(status: HerdrAgentStatus, tint: Color) {
    when (status) {
        HerdrAgentStatus.Working -> WorkingDots(tint)
        HerdrAgentStatus.Blocked -> Icon(
            Icons.Default.PanTool,
            contentDescription = "Needs input",
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        HerdrAgentStatus.Done -> Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Done",
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        HerdrAgentStatus.Idle, HerdrAgentStatus.Unknown -> Unit
    }
}

@Composable
private fun WorkingDots(tint: Color) {
    var highlightedIndex by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            highlightedIndex = (highlightedIndex + 1) % 3
        }
    }
    Row(
        modifier = Modifier.semantics { contentDescription = "Working" },
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier.size(5.dp).background(
                    color = if (index == highlightedIndex) tint else tint.copy(alpha = 0.24f),
                    shape = CircleShape,
                ),
            )
        }
    }
}
