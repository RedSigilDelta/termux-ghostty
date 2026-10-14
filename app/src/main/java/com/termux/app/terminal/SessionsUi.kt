package com.termux.app.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.termux.app.workspace.Tab
import com.termux.app.workspace.WorkspaceState
import com.termux.app.workspace.ui.TabCard
import com.termux.app.workspace.ui.TabKind
import com.termux.app.workspace.ui.WorkspaceItem
import com.termux.shared.termux.TermuxConstants
import com.termux.terminal.TerminalSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class TabsUiState {
    var workspaces by mutableStateOf<List<WorkspaceItem>>(emptyList())
        private set
    var tabs by mutableStateOf<List<TabCard>>(emptyList())
        private set
    var activeTab by mutableStateOf<Tab?>(null)
        private set
    var activeRoot by mutableStateOf<String?>(null)
        private set
    var processNames by mutableStateOf<Map<String, String>>(emptyMap())  // tabId -> foreground process
    internal var sessions: Map<String, TerminalSession> = emptyMap()     // tabId -> session, for the poller
        private set

    fun refresh(state: WorkspaceState, sessionFor: (String) -> TerminalSession?) {
        val active = state.activeWorkspace
        val activeTabs = active?.tabs.orEmpty()
        activeTab = active?.let { workspace -> workspace.tabs.firstOrNull { it.id == workspace.activeTabId } }
        activeRoot = active?.root
        workspaces = state.workspaces.map {
            WorkspaceItem(it.id, it.name, it.root, it.tabs.size, active = it.id == state.activeWorkspaceId)
        }
        tabs = activeTabs.map { tab ->
            val current = tab.id == active?.activeTabId
            when (tab) {
                is Tab.Terminal -> terminalCard(tab, current, sessionFor(tab.id))
                is Tab.Files -> fileCard(tab.id, TabKind.FILES, tab.dir, current)
                is Tab.Viewer -> fileCard(tab.id, TabKind.VIEWER, tab.path, current)
            }
        }
        sessions = activeTabs.filterIsInstance<Tab.Terminal>()
            .mapNotNull { tab -> sessionFor(tab.id)?.let { tab.id to it } }
            .toMap()
    }

    private fun terminalCard(tab: Tab.Terminal, current: Boolean, session: TerminalSession?) = TabCard(
        id = tab.id,
        kind = TabKind.TERMINAL,
        title = session?.mSessionName ?: tab.name ?: processNames[tab.id] ?: "Terminal",
        subtitle = session?.title?.takeIf { it.isNotBlank() } ?: displayPath(tab.cwd),
        processName = null,
        running = session?.isRunning ?: false,
        exitStatus = session?.exitStatus ?: 0,
        current = current,
    )

    private fun fileCard(id: String, kind: TabKind, path: String, current: Boolean) = TabCard(
        id = id,
        kind = kind,
        title = File(path).name,
        subtitle = File(path).parent,
        processName = null,
        running = true,
        exitStatus = 0,
        current = current,
    )
}

private fun displayPath(path: String): String {
    val home = TermuxConstants.TERMUX_HOME_DIR_PATH
    return if (path == home || path.startsWith("$home/")) "~" + path.removePrefix(home) else path
}

/** Polls each terminal tab's foreground process while the screen is started. */
@Composable
fun SessionProcessPoller(state: TabsUiState) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val sessions = state.sessions
    LaunchedEffect(sessions, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                state.processNames = withContext(Dispatchers.IO) {
                    sessions.mapNotNull { (tabId, session) -> session.foregroundProcessName?.let { tabId to it } }.toMap()
                }
                delay(PROCESS_POLL_INTERVAL_MS)
            }
        }
    }
}

private const val PROCESS_POLL_INTERVAL_MS = 2000L
