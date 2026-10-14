package com.termux.app.workspace

import com.termux.terminal.TerminalSession
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LiveTerminal(val workspace: Workspace, val tab: Tab.Terminal, val session: TerminalSession)

/** Main-thread only: state updates, session binding and close() must run on the main thread. */
class WorkspaceRepository(private val file: WorkspaceFile, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(file.read())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    // runtime only, never persisted: tabId -> live session
    private val sessions = HashMap<String, TerminalSession>()

    @OptIn(FlowPreview::class)
    private val saveJob = scope.launch {
        state.debounce(300).collect { value -> withContext(Dispatchers.IO) { runCatching { file.write(value) } } }
    }

    fun ensureDefaultWorkspace(root: String) {
        if (_state.value.activeWorkspace != null) return
        _state.update { current ->
            val first = current.workspaces.firstOrNull()
            if (first != null) {
                current.activateWorkspace(first.id, System.currentTimeMillis())
            } else {
                current.addWorkspace(Workspace(newId(), File(root).name.ifEmpty { "~" }, root))
            }
        }
    }

    fun createWorkspace(name: String, root: String) {
        _state.update { it.addWorkspace(Workspace(newId(), name, root, lastUsed = System.currentTimeMillis())) }
    }

    fun activateWorkspace(workspaceId: String) {
        _state.update { it.activateWorkspace(workspaceId, System.currentTimeMillis()) }
    }

    fun renameWorkspace(workspaceId: String, name: String) {
        _state.update { it.renameWorkspace(workspaceId, name) }
    }

    fun deleteWorkspace(workspaceId: String) {
        _state.value.workspaces.firstOrNull { it.id == workspaceId }?.tabs?.forEach { sessions.remove(it.id) }
        _state.update { it.removeWorkspace(workspaceId, System.currentTimeMillis()) }
    }

    fun closeTab(tabId: String) {
        sessions.remove(tabId)
        _state.update { it.closeTab(tabId) }
    }

    fun openTab(tab: Tab) {
        val workspace = _state.value.activeWorkspace ?: return
        _state.update { it.addTab(workspace.id, tab, activate = true) }
    }

    fun activateTab(tabId: String) {
        _state.update { it.activateTab(tabId) }
    }

    fun setFilesDir(tabId: String, dir: String) {
        _state.update { it.updateTab(tabId) { tab -> if (tab is Tab.Files) tab.copy(dir = dir) else tab } }
    }

    fun activeTab(): Tab? = _state.value.activeWorkspace?.let { workspace ->
        workspace.tabs.firstOrNull { it.id == workspace.activeTabId }
    }

    fun sessionFor(tabId: String): TerminalSession? = sessions[tabId]

    fun tabIdFor(session: TerminalSession): String? = sessions.entries.firstOrNull { it.value === session }?.key

    fun isInActiveWorkspace(session: TerminalSession): Boolean {
        val tabId = tabIdFor(session) ?: return false
        return _state.value.activeWorkspace?.tabs.orEmpty().any { it.id == tabId }
    }

    fun activeSession(): TerminalSession? = _state.value.activeWorkspace?.activeTabId?.let { sessionFor(it) }

    fun activeTerminalSessions(): List<TerminalSession> =
        _state.value.activeWorkspace?.tabs.orEmpty()
            .filterIsInstance<Tab.Terminal>()
            .mapNotNull { sessionFor(it.id) }

    /** Every terminal tab with a running shell, across all workspaces, in workspace and tab order. */
    fun liveTerminals(): List<LiveTerminal> =
        _state.value.workspaces.flatMap { workspace ->
            workspace.tabs.filterIsInstance<Tab.Terminal>().mapNotNull { tab ->
                sessionFor(tab.id)?.takeIf { it.isRunning }?.let { LiveTerminal(workspace, tab, it) }
            }
        }

    fun workspaceSessions(workspaceId: String): List<TerminalSession> =
        _state.value.workspaces.firstOrNull { it.id == workspaceId }?.tabs.orEmpty()
            .mapNotNull { sessionFor(it.id) }

    fun unboundTerminalTabs(): List<Tab.Terminal> =
        _state.value.activeWorkspace?.tabs.orEmpty()
            .filterIsInstance<Tab.Terminal>()
            .filter { sessionFor(it.id) == null }

    fun bindNewSession(session: TerminalSession, activate: Boolean) {
        if (tabIdFor(session) != null) return
        val workspace = _state.value.activeWorkspace ?: return
        val tab = Tab.Terminal(newId(), session.mSessionName, session.cwd ?: workspace.root)
        _state.update { it.addTab(workspace.id, tab, activate) }
        sessions[tab.id] = session
    }

    fun bindRestoredSession(tabId: String, session: TerminalSession) {
        sessions[tabId] = session
    }

    fun activate(session: TerminalSession) {
        val tabId = tabIdFor(session) ?: return
        _state.update { it.activateTab(tabId) }
    }

    fun onSessionRemoved(session: TerminalSession, closeTab: Boolean) {
        val tabId = tabIdFor(session) ?: return
        sessions.remove(tabId)
        if (closeTab) _state.update { it.closeTab(tabId) }
    }

    fun refreshTerminalTabs() {
        _state.update { state ->
            sessions.entries.fold(state) { acc, (tabId, session) ->
                acc.updateTab(tabId) { tab ->
                    if (tab is Tab.Terminal) tab.copy(name = session.mSessionName, cwd = session.cwd ?: tab.cwd) else tab
                }
            }
        }
    }

    fun close() {
        saveJob.cancel()
        runCatching { file.write(_state.value) }
    }

    companion object {
        @JvmStatic
        fun create(filesDir: File): WorkspaceRepository =
            WorkspaceRepository(WorkspaceFile(File(filesDir, "workspaces.json")), MainScope())
    }
}
