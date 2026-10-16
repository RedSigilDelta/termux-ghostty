package com.mrndtvndv.term.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mrndtvndv.term.AppContainer
import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.data.prefs.KeyBarHostAction
import com.mrndtvndv.term.server.TerminalEntry
import com.mrndtvndv.term.herdr.HerdrState
import com.mrndtvndv.term.herdr.panesIn
import com.mrndtvndv.term.workspace.HerdrFileContext
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.herdrSheetTabId
import com.mrndtvndv.term.workspace.newId
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A server's Herdr session: one full-screen terminal running Herdr's own UI, the Git and SFTP sheets from the key
 * bar, an agents sheet, and swipeable file previews scoped to the focused remote workspace.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun HerdrSessionRoute(
    serverId: String,
    appViewModel: AppViewModel,
    container: AppContainer,
    settings: AppSettings,
    appearance: TerminalAppearance,
    host: TerminalHostCallbacks,
) {
    val server = appViewModel.getServer(serverId)
    val workspaceState by appViewModel.workspaces.collectAsStateWithLifecycle()
    val tab = remember(workspaceState) { appViewModel.herdrFullUiTab(serverId) }
    val terminals by (server?.terminals ?: flowOf(emptyMap<String, TerminalEntry>()))
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val contextFlow = remember(serverId) { appViewModel.herdrContext(serverId) }
    val fileContext by contextFlow.collectAsStateWithLifecycle()
    val ready = fileContext as? HerdrFileContext.Ready
    val scopeKey = ready?.scopeKey
    val viewersFlow: StateFlow<List<Tab.Viewer>> = remember(serverId, scopeKey) {
        if (scopeKey == null) {
            MutableStateFlow(emptyList<Tab.Viewer>())
        } else {
            appViewModel.herdrViewers(serverId, scopeKey)
        }
    }
    val viewers by viewersFlow.collectAsStateWithLifecycle()
    val currentScopeKey by rememberUpdatedState(scopeKey)
    val coroutineScope = rememberCoroutineScope()
    // Composition-scoped: nothing outside this route holds the request, so it cannot outlive the route.
    val sheetRequests = remember(serverId) {
        HerdrSheetRequests(
            scope = coroutineScope,
            // Server-global snapshot focus captured coherently, not a verified client focus.
            capture = { appViewModel.captureHerdrContext(serverId) },
            restartControl = { appViewModel.restartHerdr(serverId) },
            // Git starts at the captured cwd; SFTP resumes this workspace's existing browser.
            prepare = { kind, scopeKey ->
                if (kind == HerdrSheetKind.Git) {
                    appViewModel.connectionStore(serverId, herdrSheetTabId(kind.key, scopeKey)).viewModelStore.clear()
                }
            },
            homeDir = { appViewModel.getServer(serverId)?.homeDir.orEmpty() },
            newOpenId = ::newId,
        )
    }
    val sheet by sheetRequests.state.collectAsStateWithLifecycle()
    DisposableEffect(sheetRequests) {
        onDispose { sheetRequests.cancel() }
    }
    var agentsVisible by remember { mutableStateOf(false) }
    val sheetStates = rememberSaveableStateHolder()
    val herdrState by (server?.herdr?.state ?: flowOf(HerdrState()))
        .collectAsStateWithLifecycle(initialValue = HerdrState())
    val sessionWorkspace = workspaceState.workspaces.firstOrNull {
        it.id == appViewModel.herdrSessionWorkspaceId(serverId)
    }
    val activeTabId = sessionWorkspace?.activeTabId
        ?.takeIf { id -> id == tab?.id || viewers.any { it.id == id } } ?: tab?.id
    val store = appViewModel.connectionStore(serverId)
    val workspaceViewModel = viewModel(viewModelStoreOwner = store) {
        WorkspaceViewModel(sessionManager = container.sessionManager)
    }

    LaunchedEffect(serverId) {
        appViewModel.ensureHerdrSession(serverId)
        appViewModel.refreshHerdrContext(serverId)
    }

    BackHandler(enabled = tab != null && activeTabId != tab.id && sheet == null && !agentsVisible) {
        tab?.let { appViewModel.activateTab(it.id) }
    }

    if (server == null || tab == null) {
        HerdrLoading()
        return
    }

    fun closeViewer(tabId: String) {
        val index = viewers.indexOfFirst { it.id == tabId }
        val next = viewers.getOrNull(index + 1) ?: viewers.getOrNull(index - 1)
        appViewModel.closeHerdrViewer(tabId)
        if (activeTabId == tabId) appViewModel.activateTab(next?.id ?: tab.id)
    }

    // The sheet shows at once as Opening; the coherent capture runs in parallel with its entrance animation.
    fun openSheet(kind: HerdrSheetKind) {
        if (!agentsVisible) sheetRequests.open(kind)
    }

    val onHostAction: (KeyBarHostAction) -> Unit = { kind ->
        when (kind) {
            KeyBarHostAction.Git -> openSheet(HerdrSheetKind.Git)
            KeyBarHostAction.Sftp -> openSheet(HerdrSheetKind.Sftp)
        }
    }
    val agentsButton: @Composable () -> Unit = {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shadowElevation = 3.dp,
        ) {
            IconButton(onClick = {
                // The Agents sheet is the newer intent: it drops a sheet that is still opening.
                sheetRequests.cancel()
                appViewModel.refreshHerdrContext(serverId)
                agentsVisible = true
            }) {
                Icon(Icons.Default.SmartToy, contentDescription = "Agents")
            }
        }
    }
    val paneCallbacks: (TerminalSession) -> TerminalPaneCallbacks = { session ->
        host.paneCallbacks(session, container.preferences::update)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        WorkspacePager(
            tabs = listOf(tab) + viewers,
            currentTabId = activeTabId,
            onSelectTab = { appViewModel.activateTab(it.id) },
            empty = {},
        ) { page, isActive ->
            when (page) {
                is Tab.Terminal -> TerminalTab(
                    serverId = serverId,
                    tab = page,
                    entry = terminals[page.id],
                    isActive = isActive && sheet == null && !agentsVisible,
                    appViewModel = appViewModel,
                    workspaceViewModel = workspaceViewModel,
                    settings = settings,
                    appearance = appearance,
                    paneCallbacks = paneCallbacks,
                    tabsButton = agentsButton,
                    onHostAction = onHostAction,
                )
                is Tab.Viewer -> Column(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                        .statusBarsPadding(),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = page.path.substringAfterLast('/'),
                            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                            maxLines = 1,
                        )
                        IconButton(onClick = { closeViewer(page.id) }) {
                            Icon(Icons.Default.Close, contentDescription = "Close preview")
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        ViewerTab(
                            client = server.sftpClient,
                            tab = page,
                            onClose = { closeViewer(page.id) },
                            tabsButton = agentsButton,
                        )
                    }
                }
                else -> Unit
            }
        }
        if (agentsVisible) {
            sheetStates.SaveableStateProvider("agents:$serverId") {
                HerdrAgentsSheet(
                    state = herdrState,
                    onNewTab = { workspaceId -> appViewModel.createHerdrTab(serverId, workspaceId) },
                    onNewWorkspace = { sourceWorkspaceId ->
                        appViewModel.createHerdrWorkspace(serverId, sourceWorkspaceId)
                    },
                    onSplitPane = { paneId, direction ->
                        // The action resolves the pane again from its stable terminal when it runs.
                        val terminalId = herdrState.snapshot?.panes?.firstOrNull { it.id == paneId }?.terminalId
                        if (terminalId == null) {
                            appViewModel.notificationState.post("Couldn't split pane", "That pane has closed")
                        } else {
                            appViewModel.splitHerdrPane(serverId, terminalId, direction)
                        }
                    },
                    onFocusPane = { pane -> appViewModel.focusHerdrTerminal(serverId, pane.terminalId) },
                    onFocusTab = { remoteTab ->
                        // Tab IDs are positional, so one of the tab's panes identifies it when the action runs.
                        val anchor = herdrState.snapshot?.panesIn(remoteTab.id)?.firstOrNull()?.terminalId
                        if (anchor == null) {
                            appViewModel.notificationState.post("Couldn't focus tab", "That tab has closed")
                        } else {
                            appViewModel.focusHerdrTabOf(serverId, anchor)
                        }
                    },
                    onClosePane = { pane -> appViewModel.closeHerdrPane(serverId, pane.terminalId) },
                    onDismiss = { agentsVisible = false },
                )
            }
        }
        // One host for every phase keeps the same modal, so a finished capture neither dismisses nor re-animates it.
        sheet?.let { current ->
            HerdrSheetHost(
                state = current,
                serverId = serverId,
                server = server,
                appViewModel = appViewModel,
                browsers = container.workspaceRepository,
                // Show the preview only if its captured collection is still current.
                onViewerOpened = { openedScope, tabId ->
                    val eligible = sheetRequests.canSelectPreview(current.requestId, openedScope)
                    if (openedScope == currentScopeKey && eligible) {
                        appViewModel.activateTab(tabId)
                    }
                },
                onRetry = sheetRequests::retry,
                onBeginDismiss = sheetRequests::beginDismiss,
                onDismiss = sheetRequests::dismiss,
                savedState = sheetStates,
            )
        }
    }
}

@Composable
private fun HerdrLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
