package com.termux.app.workspace.ui

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.processicons.commandIconResource
import com.termux.R

enum class TabKind { TERMINAL, FILES, VIEWER }

data class TabCard(
    val id: String,
    val kind: TabKind,
    val title: String,
    val subtitle: String?,      // cwd / dir / file name
    val processName: String?,   // terminals only, for the icon
    val running: Boolean,       // terminals only; others pass true
    val exitStatus: Int,
    val current: Boolean,
)

data class WorkspaceItem(val id: String, val name: String, val root: String, val tabCount: Int, val active: Boolean)

interface TabsGridActions {
    fun onSelectTab(id: String)
    fun onCloseTab(id: String)
    fun onRenameTab(id: String, name: String)
    fun onNewTerminal()
    fun onNewFiles()
    fun onSelectWorkspace(id: String)
    fun onNewWorkspace(name: String, root: String)
    fun onRenameWorkspace(id: String, name: String)
    fun onDeleteWorkspace(id: String)
    fun onOpenSettings()
    fun onDismiss()
}

private enum class WorkspaceDialog { NEW, RENAME, DELETE }

@Composable
fun TabsGrid(
    visible: Boolean,
    workspaces: List<WorkspaceItem>,
    tabs: List<TabCard>,
    defaultRoot: String,
    actions: TabsGridActions,
    preview: @Composable (tab: TabCard, modifier: Modifier) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = visible) { actions.onDismiss() }
    val active = workspaces.firstOrNull { it.active }
    var workspaceMenuOpen by remember(visible) { mutableStateOf(false) }
    var workspaceDialog by remember(visible) { mutableStateOf<WorkspaceDialog?>(null) }
    var renamingTab by remember(visible) { mutableStateOf<TabCard?>(null) }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 4 },
        exit = fadeOut() + slideOutVertically { it / 4 },
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { workspaceMenuOpen = true }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = active?.name.orEmpty(),
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = active?.root.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = workspaceMenuOpen, onDismissRequest = { workspaceMenuOpen = false }) {
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
                                        workspaceMenuOpen = false
                                        actions.onSelectWorkspace(workspace.id)
                                    },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_new)) },
                                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                onClick = {
                                    workspaceMenuOpen = false
                                    workspaceDialog = WorkspaceDialog.NEW
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_rename)) },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    workspaceMenuOpen = false
                                    workspaceDialog = WorkspaceDialog.RENAME
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_delete)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                enabled = workspaces.size > 1,
                                onClick = {
                                    workspaceMenuOpen = false
                                    workspaceDialog = WorkspaceDialog.DELETE
                                },
                            )
                        }
                    }
                    IconButton(onClick = actions::onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.action_open_settings))
                    }
                }
                HorizontalDivider()
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        SwipeableTabCard(
                            tab = tab,
                            onSelect = { actions.onSelectTab(tab.id) },
                            onClose = { actions.onCloseTab(tab.id) },
                            onRename = { renamingTab = tab },
                            preview = preview,
                        )
                    }
                    item { NewTerminalCard(onNewTerminal = actions::onNewTerminal, onNewFiles = actions::onNewFiles) }
                }
            }

            if (workspaceDialog == WorkspaceDialog.NEW) {
                NewWorkspaceDialog(
                    defaultRoot = defaultRoot,
                    onConfirm = { name, root ->
                        actions.onNewWorkspace(name, root)
                        workspaceDialog = null
                    },
                    onDismiss = { workspaceDialog = null },
                )
            }
            if (workspaceDialog == WorkspaceDialog.RENAME && active != null) {
                NameDialog(
                    title = stringResource(R.string.workspace_rename),
                    initialName = active.name,
                    onConfirm = { name ->
                        actions.onRenameWorkspace(active.id, name)
                        workspaceDialog = null
                    },
                    onDismiss = { workspaceDialog = null },
                )
            }
            if (workspaceDialog == WorkspaceDialog.DELETE && active != null) {
                AlertDialog(
                    onDismissRequest = { workspaceDialog = null },
                    title = { Text(stringResource(R.string.workspace_delete)) },
                    text = { Text(stringResource(R.string.workspace_delete_message, active.name)) },
                    confirmButton = {
                        TextButton(onClick = {
                            actions.onDeleteWorkspace(active.id)
                            workspaceDialog = null
                        }) {
                            Text(stringResource(R.string.workspace_delete_confirm))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { workspaceDialog = null }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
            renamingTab?.let { tab ->
                NameDialog(
                    title = stringResource(R.string.tabs_grid_rename_tab),
                    initialName = tab.title,
                    onConfirm = { name ->
                        actions.onRenameTab(tab.id, name)
                        renamingTab = null
                    },
                    onDismiss = { renamingTab = null },
                )
            }
        }
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabIcon(
                    kind = tab.kind,
                    processName = tab.processName,
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
                        contentDescription = stringResource(R.string.tabs_grid_close_tab),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
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
internal fun TabIcon(kind: TabKind, processName: String?, title: String?, tint: Color, modifier: Modifier = Modifier) {
    val processIcon = if (kind == TabKind.TERMINAL) {
        // Programs like Claude Code name themselves in the title even when the process is a runtime.
        commandIconResource(processName) ?: commandIconResource(title?.substringBefore(' '))
    } else {
        null
    }
    if (processIcon != null) {
        Icon(painterResource(processIcon), contentDescription = null, tint = tint, modifier = modifier)
    } else {
        val icon = when (kind) {
            TabKind.TERMINAL -> Icons.Rounded.Terminal
            TabKind.FILES -> Icons.Rounded.Folder
            TabKind.VIEWER -> Icons.Rounded.Description
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
    }
}

@Composable
private fun NewTerminalCard(onNewTerminal: () -> Unit, onNewFiles: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                .clickable { menuOpen = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = stringResource(R.string.tabs_grid_new_terminal),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tabs_grid_new_terminal)) },
                onClick = {
                    menuOpen = false
                    onNewTerminal()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.tabs_grid_new_files)) },
                onClick = {
                    menuOpen = false
                    onNewFiles()
                },
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.tabs_grid_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun NewWorkspaceDialog(
    defaultRoot: String,
    onConfirm: (name: String, root: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var root by remember { mutableStateOf(defaultRoot) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tabs_grid_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = root,
                    onValueChange = { root = it },
                    label = { Text(stringResource(R.string.workspace_folder)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val path = root.trim()
                    onConfirm(name.trim().ifEmpty { path.trimEnd('/').substringAfterLast('/') }, path)
                },
                enabled = root.isNotBlank(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
