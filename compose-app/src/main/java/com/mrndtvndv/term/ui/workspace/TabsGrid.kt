package com.mrndtvndv.term.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.processicons.commandIconResource
import com.mrndtvndv.term.TabType
import com.mrndtvndv.term.ui.components.FloatingPill
import com.mrndtvndv.term.ui.components.PillActions
import com.mrndtvndv.term.ui.components.PillDivider
import com.mrndtvndv.term.ui.components.PillIconButton
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

enum class TabKind { Terminal, Sftp, Git, Viewer }

data class TabCard(
    val id: String,
    val kind: TabKind,
    val title: String,
    val subtitle: String?,      // cwd / dir / repo / file path
    val running: Boolean,       // terminals only; others pass true
    val exitStatus: Int,
    val current: Boolean,
)

data class WorkspaceItem(val id: String, val name: String, val root: String, val tabCount: Int, val active: Boolean)

interface TabsGridActions {
    fun onSelectTab(id: String)
    fun onCloseTab(id: String)
    fun onRenameTab(id: String)
    fun onNewTab(type: TabType)
    fun onSelectWorkspace(id: String)
    fun onNewWorkspace()
    fun onRenameWorkspace()
    fun onDeleteWorkspace()
    fun onOpenSettings()
    fun onDismiss()
}

@Composable
fun TabsGrid(
    visible: Boolean,
    workspaces: List<WorkspaceItem>,
    tabs: List<TabCard>,
    canAddSftp: Boolean,
    canAddGit: Boolean,
    actions: TabsGridActions,
    preview: @Composable (tab: TabCard, modifier: Modifier) -> Unit = { _, _ -> },
) {
    BackHandler(enabled = visible) { actions.onDismiss() }
    val hazeState = rememberHazeState()
    val gridState = rememberLazyGridState()
    LaunchedEffect(visible) { if (visible && tabs.isNotEmpty()) gridState.scrollToItem(tabs.lastIndex) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 4 },
        exit = fadeOut() + slideOutVertically { it / 4 },
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                        end = 12.dp,
                        bottom = WindowInsets.navigationBars
                            .asPaddingValues().calculateBottomPadding() +
                            56.dp + 16.dp + 12.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        SwipeableTabCard(
                            tab = tab,
                            onSelect = { actions.onSelectTab(tab.id) },
                            onClose = { actions.onCloseTab(tab.id) },
                            onRename = { if (tab.kind == TabKind.Terminal) actions.onRenameTab(tab.id) },
                            preview = preview,
                        )
                    }
                }
                TabsGridPill(
                    workspaces = workspaces,
                    canAddSftp = canAddSftp,
                    canAddGit = canAddGit,
                    actions = actions,
                    hazeState = hazeState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun TabsGridPill(
    workspaces: List<WorkspaceItem>,
    canAddSftp: Boolean,
    canAddGit: Boolean,
    actions: TabsGridActions,
    hazeState: HazeState,
    modifier: Modifier,
) {
    FloatingPill(state = Unit, hazeState = hazeState, modifier = modifier) {
        PillActions {
            WorkspaceChip(workspaces = workspaces, actions = actions)
            PillDivider()
            PillIconButton(
                icon = Icons.Rounded.Settings,
                contentDescription = "Settings",
                onClick = actions::onOpenSettings,
            )
            PillDivider()
            NewTabButton(canAddSftp = canAddSftp, canAddGit = canAddGit, onNewTab = actions::onNewTab)
        }
    }
}

@Composable
private fun WorkspaceChip(
    workspaces: List<WorkspaceItem>,
    actions: TabsGridActions,
) {
    val active = workspaces.firstOrNull { it.active }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .clickable { menuOpen = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Workspaces,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = active?.name.orEmpty(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Icon(
                imageVector = Icons.Rounded.UnfoldMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            WorkspaceMenuItems(workspaces = workspaces, actions = actions, onDismiss = { menuOpen = false })
        }
    }
}

@Composable
private fun ColumnScope.WorkspaceMenuItems(
    workspaces: List<WorkspaceItem>,
    actions: TabsGridActions,
    onDismiss: () -> Unit,
) {
    workspaces.forEach { workspace ->
        DropdownMenuItem(
            text = {
                Column {
                    Text(workspace.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = workspace.root,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            trailingIcon = {
                if (workspace.active) Icon(Icons.Rounded.Check, contentDescription = null)
            },
            onClick = {
                onDismiss()
                actions.onSelectWorkspace(workspace.id)
            },
        )
    }
    HorizontalDivider()
    DropdownMenuItem(
        text = { Text("New workspace…") },
        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
        onClick = {
            onDismiss()
            actions.onNewWorkspace()
        },
    )
    DropdownMenuItem(
        text = { Text("Rename…") },
        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
        onClick = {
            onDismiss()
            actions.onRenameWorkspace()
        },
    )
    DropdownMenuItem(
        text = { Text("Delete") },
        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
        onClick = {
            onDismiss()
            actions.onDeleteWorkspace()
        },
    )
}

@Composable
private fun NewTabButton(canAddSftp: Boolean, canAddGit: Boolean, onNewTab: (TabType) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        PillIconButton(
            icon = Icons.Rounded.Add,
            contentDescription = "New tab",
            onClick = { menuOpen = true },
        )
        AddTabMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            canAddSftp = canAddSftp,
            canAddGit = canAddGit,
            onAdd = onNewTab,
        )
    }
}

@Composable
private fun LazyGridItemScope.SwipeableTabCard(
    tab: TabCard,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onRename: () -> Unit,
    preview: @Composable (tab: TabCard, modifier: Modifier) -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) onClose()
    }
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.animateItem(),
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
            )
        },
    ) {
        TabCardContent(tab = tab, onClick = onSelect, onLongClick = onRename, onClose = onClose, preview = preview)
    }
}

@Composable
private fun TabCardContent(
    tab: TabCard,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClose: () -> Unit,
    preview: @Composable (tab: TabCard, modifier: Modifier) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val finished = !tab.running
    val contentColor = when {
        finished && tab.exitStatus != 0 -> colorScheme.error
        tab.current -> colorScheme.onPrimaryContainer
        else -> colorScheme.onSurface
    }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .alpha(if (finished) 0.6f else 1f)
            .clip(shape)
            .background(if (tab.current) colorScheme.primaryContainer else colorScheme.surfaceContainerHighest)
            .then(if (tab.current) Modifier.border(2.dp, colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TabCardHeader(tab = tab, finished = finished, contentColor = contentColor, onClose = onClose)
            preview(
                tab,
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}

@Composable
private fun TabCardHeader(tab: TabCard, finished: Boolean, contentColor: Color, onClose: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabIcon(
            kind = tab.kind,
            title = tab.title,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tab.title,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (finished) TextDecoration.LineThrough else null,
            )
            tab.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tab.current) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Close tab",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TabIcon(kind: TabKind, title: String, tint: Color, modifier: Modifier = Modifier) {
    // Programs like Claude Code name themselves in the title even when the process is a runtime.
    val processIcon = if (kind == TabKind.Terminal) commandIconResource(title.substringBefore(' ')) else null
    if (processIcon != null) {
        Icon(painterResource(processIcon), contentDescription = null, tint = tint, modifier = modifier)
    } else {
        val icon = when (kind) {
            TabKind.Terminal -> Icons.Default.Terminal
            TabKind.Sftp -> Icons.Default.Folder
            TabKind.Git -> Icons.Default.Commit
            TabKind.Viewer -> Icons.AutoMirrored.Filled.Article
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
    }
}
