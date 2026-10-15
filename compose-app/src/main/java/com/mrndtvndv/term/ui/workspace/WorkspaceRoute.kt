@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("MatchingDeclarationName")

package com.mrndtvndv.term.ui.workspace

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mrndtvndv.term.AppContainer
import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.TabType
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.navigation.SftpFolder
import com.mrndtvndv.term.navigation.WorkspaceNavigator
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.TerminalEntry
import com.mrndtvndv.term.ui.review.ReviewScreenRoute
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpScreenRoute
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.sftp.openDownloadedFile
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransferManager
import com.mrndtvndv.term.workspace.Tab
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalThumbnail
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class TerminalHostCallbacks(
    val onCommitContent: (TerminalSession, ClipData) -> Boolean,
    val onRequestMediaUpload: (TerminalSession) -> Unit,
    val onRequestFileUpload: (TerminalSession) -> Unit,
    val onOpenUrl: (String) -> Unit,
)

@Suppress("LongMethod", "LongParameterList")
@Composable
fun WorkspaceRoute(
    workspaceId: String,
    appViewModel: AppViewModel,
    container: AppContainer,
    settings: AppSettings,
    appearance: TerminalAppearance,
    host: TerminalHostCallbacks,
    onBack: () -> Unit,
) {
    val workspaceState by appViewModel.workspaces.collectAsStateWithLifecycle()
    val workspace = workspaceState.workspaces.firstOrNull { it.id == workspaceId }
    val server = workspace?.let { appViewModel.getServer(it.serverId) }
    // Closing drops the workspace navigator, but the entry still composes during the exit animation.
    val navigator = remember(workspaceId) { appViewModel.navigator.workspace(workspaceId) }
    if (workspace == null || server == null || navigator == null) {
        WorkspaceLoading()
        return
    }

    val serverId = server.config.id
    val sessionManager = container.sessionManager
    val store = appViewModel.connectionStore(serverId)
    val workspaceViewModel = viewModel(viewModelStoreOwner = store) {
        WorkspaceViewModel(sessionManager = sessionManager)
    }
    val terminals by server.terminals.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<WorkspaceDialogs?>(null) }
    var gridVisible by rememberSaveable(workspaceId) { mutableStateOf(false) }
    val thumbnails = remember { TabThumbnails() }
    if (gridVisible) {
        DisposableEffect(terminals) {
            thumbnails.start(terminals.mapValues { it.value.session })
            onDispose { thumbnails.stop() }
        }
    }
    DisposableEffect(Unit) {
        onDispose { thumbnails.stop() }
    }
    val paneCallbacks: (TerminalSession) -> TerminalPaneCallbacks = { session ->
        host.paneCallbacks(session, container.preferences::update)
    }

    // Without this the root NavDisplay would preview popping the whole workspace
    // when back only pops a tab's inner stack.
    BackHandler(enabled = navigator.handlesBack, onBack = onBack)

    val dialogActions = WorkspaceDialogController(
        appViewModel = appViewModel,
        serverId = serverId,
        setDialog = { dialog = it },
        setGridVisible = { gridVisible = it },
    )
    val gridActions = WorkspaceGridController(
        appViewModel = appViewModel,
        workspace = workspace,
        server = server,
        setDialog = { dialog = it },
        setGridVisible = { gridVisible = it },
    )
    WorkspaceDialogHost(dialog = dialog, actions = dialogActions)

    val canAddSftp = server.sftpClient != null
    val canAddGit = server.sshSession != null
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val tabsButton: @Composable () -> Unit = {
        TabsButton(
            tabCount = workspace.tabs.size,
            onOpenTabs = {
                // The IME is its own window and would cover the grid, so drop focus and hide it first.
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                gridVisible = true
            },
        )
    }
    val selectedTab = workspace.tabs.firstOrNull { it.id == workspace.activeTabId }

    Box(modifier = Modifier.fillMaxSize()) {
        WorkspacePager(
            tabs = workspace.tabs,
            currentTabId = workspace.activeTabId,
            onSelectTab = { appViewModel.selectTab(workspace.id, it.id) },
            empty = {
                WorkspaceEmptyState(
                    canAddSftp = canAddSftp,
                    canAddGit = canAddGit,
                    onAdd = { appViewModel.addTab(workspace.id, it) },
                )
            },
        ) { tab, isActive ->
            // Terminal pages are edge to edge; the other pages keep the system bars and IME clear.
            when (tab) {
                is Tab.Terminal -> TerminalTab(
                    serverId = serverId,
                    tab = tab,
                    entry = terminals[tab.id],
                    isActive = isActive && !gridVisible,
                    appViewModel = appViewModel,
                    workspaceViewModel = workspaceViewModel,
                    settings = settings,
                    appearance = appearance,
                    paneCallbacks = paneCallbacks,
                    tabsButton = tabsButton,
                )
                is Tab.Sftp -> Box(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                    SftpTab(
                        appViewModel = appViewModel,
                        server = server,
                        navigator = navigator,
                        tab = tab,
                        isActive = isActive,
                        onBack = onBack,
                        tabsButton = tabsButton,
                    )
                }
                is Tab.Git -> Box(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                    GitTab(
                        appViewModel = appViewModel,
                        server = server,
                        navigator = navigator,
                        tab = tab,
                        onBack = onBack,
                        tabsButton = tabsButton,
                    )
                }
                is Tab.Viewer -> Box(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                    ViewerTab(
                        client = server.sftpClient,
                        tab = tab,
                        onClose = { appViewModel.closeTab(tab.id) },
                        tabsButton = tabsButton,
                    )
                }
            }
        }
        if (!gridVisible && needsOverlayTabsButton(selectedTab, terminals, navigator)) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 12.dp, bottom = 16.dp),
            ) {
                tabsButton()
            }
        }
        TabsGrid(
            visible = gridVisible,
            workspaces = workspaceState.workspacesFor(serverId).map { item ->
                WorkspaceItem(item.id, item.name, item.root, item.tabs.size, item.id == workspace.id)
            },
            tabs = workspace.tabs.map { tab ->
                tabCard(tab, terminals, tab.id == workspace.activeTabId)
            },
            addOptions = TabAddOptions(canAddSftp = canAddSftp, canAddGit = canAddGit),
            actions = gridActions,
            preview = { card, modifier ->
                TabPreview(workspace.tabs.firstOrNull { it.id == card.id }, thumbnails, modifier)
            },
        )
    }
}

@Composable
private fun WorkspaceEmptyState(canAddSftp: Boolean, canAddGit: Boolean, onAdd: (TabType) -> Unit) {
    Box(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        EmptyWorkspace(canAddSftp = canAddSftp, canAddGit = canAddGit, onAdd = onAdd)
    }
}

@Composable
private fun TabPreview(tab: Tab?, thumbnails: TabThumbnails, modifier: Modifier) {
    when (tab) {
        is Tab.Terminal -> TerminalThumbnail(thumbnails.frames[tab.id], modifier)
        is Tab.Sftp -> IconPreview(Icons.Default.Folder, modifier)
        is Tab.Git -> IconPreview(tab.icon(), modifier)
        is Tab.Viewer -> ViewerPreview(
            file = File(LocalContext.current.cacheDir, "viewer/${tab.id}/${File(tab.path).name}"),
            modifier = modifier,
        )
        null -> Unit
    }
}

/**
 * Terminal panes carry the tabs button in their key bar, Sftp and Git in their action pill, and viewers
 * render it themselves; the empty state, opening terminals and Git diffs (no action pill) need the overlay.
 */
private fun needsOverlayTabsButton(
    activeTab: Tab?,
    terminals: Map<String, TerminalEntry>,
    navigator: WorkspaceNavigator,
): Boolean = when (activeTab) {
    null -> true
    is Tab.Terminal -> terminals[activeTab.id] == null
    is Tab.Git -> navigator.reviewStack(activeTab.id).size > 1
    is Tab.Sftp, is Tab.Viewer -> false
}

private fun tabCard(tab: Tab, terminals: Map<String, TerminalEntry>, current: Boolean): TabCard {
    val entry = terminals[tab.id]
    return when (tab) {
        is Tab.Terminal -> TabCard(
            id = tab.id,
            kind = TabKind.Terminal,
            title = tabLabel(tab, terminals),
            subtitle = entry?.session?.reportedCwd ?: tab.cwd,
            running = entry?.running ?: true,
            exitStatus = entry?.takeIf { !it.running }?.session?.exitStatus ?: 0,
            current = current,
        )
        is Tab.Sftp -> TabCard(tab.id, TabKind.Sftp, tabLabel(tab, terminals), tab.dir, true, 0, current)
        is Tab.Git -> TabCard(tab.id, TabKind.Git, tabLabel(tab, terminals), tab.repoDir, true, 0, current)
        is Tab.Viewer -> TabCard(tab.id, TabKind.Viewer, tabLabel(tab, terminals), tab.path, true, 0, current)
    }
}

@Composable
private fun WorkspaceLoading() {
    Box(modifier = Modifier.fillMaxSize()) {
        LoadingIndicator(modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun EmptyWorkspace(canAddSftp: Boolean, canAddGit: Boolean, onAdd: (TabType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No tabs", style = MaterialTheme.typography.titleMedium)
        Box(modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = { expanded = true }) { Text("Add tab") }
            AddTabMenu(
                expanded = expanded,
                onDismiss = { expanded = false },
                canAddSftp = canAddSftp,
                canAddGit = canAddGit,
                onAdd = onAdd,
            )
        }
    }
}

@Composable
private fun SftpTab(
    appViewModel: AppViewModel,
    server: Server,
    navigator: WorkspaceNavigator,
    tab: Tab.Sftp,
    isActive: Boolean,
    onBack: () -> Unit,
    tabsButton: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val onError: (String, String) -> Unit = { title, message -> appViewModel.notificationState.post(title, message) }
    val store: ViewModelStoreOwner = appViewModel.connectionStore(server.config.id, tab.id)
    val sftpViewModel = viewModel(viewModelStoreOwner = store) {
        val sshSession = checkNotNull(server.sshSession)
        SftpViewModel(
            client = checkNotNull(server.sftpClient),
            execCommand = { command -> sshSession.execCommand(command) },
            transferManager = SftpTransferManager.current,
            ownerKey = server.config.id,
            onDirectoryChanged = { dir -> appViewModel.setSftpDir(tab.id, dir) },
            initialExpanded = tab.expanded.toSet(),
            onExpandedChanged = { expanded -> appViewModel.setSftpExpanded(tab.id, expanded.toList()) },
        )
    }
    LaunchedEffect(tab.id) {
        snapshotFlow { navigator.sftpStack(tab).mapNotNull { (it as? SftpFolder)?.path }.dropLast(1) }
            .distinctUntilChanged()
            .collect { back -> appViewModel.setSftpBack(tab.id, back) }
    }
    SftpScreenRoute(
        viewModel = sftpViewModel,
        backStack = navigator.sftpStack(tab),
        onOpenFolder = { path -> navigator.openFolder(tab.id, path) },
        onBack = onBack,
        isTabActive = isActive,
        onOpenFile = { remote, local ->
            openDownloadedFile(
                context = context,
                file = local,
                onViewText = { appViewModel.openViewer(remote.path) },
                onError = onError,
            )
        },
        onOpenFileError = { message -> onError("SFTP Error", message) },
        tabsButton = tabsButton,
        initialScrollIndex = tab.scrollIndex,
        initialScrollOffset = tab.scrollOffset,
        onScrollChanged = { index, offset -> appViewModel.setSftpScroll(tab.id, index, offset) },
        initialTreeMode = tab.treeMode,
        onTreeModeChanged = { enabled -> appViewModel.setSftpTreeMode(tab.id, enabled) },
    )
}

@Composable
private fun GitTab(
    appViewModel: AppViewModel,
    server: Server,
    navigator: WorkspaceNavigator,
    tab: Tab.Git,
    onBack: () -> Unit,
    tabsButton: @Composable () -> Unit,
) {
    val store: ViewModelStoreOwner = appViewModel.connectionStore(server.config.id, tab.id)
    val reviewViewModel = viewModel(viewModelStoreOwner = store) {
        val sshSession = checkNotNull(server.sshSession)
        ReviewViewModel(
            execCommand = { command -> sshSession.execCommand(command) },
            workspaceDir = MutableStateFlow(tab.repoDir),
        )
    }
    ReviewScreenRoute(
        viewModel = reviewViewModel,
        backStack = navigator.reviewStack(tab.id),
        onOpenDiff = { route -> navigator.openDiff(tab.id, route) },
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
        tabsButton = tabsButton,
    )
}

internal fun TerminalHostCallbacks.paneCallbacks(
    session: TerminalSession,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
) = TerminalPaneCallbacks(
    onUploadMedia = { onRequestMediaUpload(session) },
    onUploadFile = { onRequestFileUpload(session) },
    onCommitContent = { content -> onCommitContent(session, content) },
    onOpenUrl = onOpenUrl,
    onUpdateSettings = onUpdateSettings,
)
