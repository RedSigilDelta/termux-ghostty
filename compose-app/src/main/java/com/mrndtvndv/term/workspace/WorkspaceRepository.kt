package com.mrndtvndv.term.workspace

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Main-thread only. */
@Suppress("TooManyFunctions")
class WorkspaceRepository(private val file: WorkspaceFile, private val scope: CoroutineScope) : SftpBrowserStore {
    private val _state = MutableStateFlow(file.read())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()
    private val writes = Mutex()
    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError = _persistenceError.asStateFlow()

    @OptIn(FlowPreview::class)
    private val saveJob = scope.launch {
        state.debounce(300).collect { persist() }
    }

    fun createWorkspace(serverId: String, name: String, root: String): Workspace {
        val tab = Tab.Terminal(newId(), cwd = root)
        val workspace = Workspace(
            id = newId(),
            serverId = serverId,
            name = name,
            root = root,
            tabs = listOf(tab),
            activeTabId = tab.id,
            lastUsed = System.currentTimeMillis(),
        )
        _state.update { it.addWorkspace(workspace) }
        return workspace
    }

    /** A workspace showing a Herdr workspace. Starts with no tabs, so no local shell opens behind the scenes. */
    fun createHerdrWorkspace(serverId: String, name: String, root: String, herdrWorkspaceId: String): Workspace {
        val workspace = Workspace(
            id = newId(),
            serverId = serverId,
            name = name,
            root = root,
            lastUsed = System.currentTimeMillis(),
            herdr = HerdrWorkspaceBinding(herdrWorkspaceId),
        )
        _state.update { it.addWorkspace(workspace) }
        return workspace
    }

    /** The workspace that holds a server's Herdr session: its one full-UI terminal and nothing else. */
    fun createHerdrSessionWorkspace(serverId: String, name: String, root: String): Workspace {
        val tab = Tab.Terminal(herdrFullUiTabId(serverId), cwd = root)
        val workspace = Workspace(
            id = newId(),
            serverId = serverId,
            name = name,
            root = root,
            tabs = listOf(tab),
            activeTabId = tab.id,
            lastUsed = System.currentTimeMillis(),
        )
        _state.update { it.addWorkspace(workspace) }
        return workspace
    }

    fun setHiddenTerminals(workspaceId: String, hiddenTerminalIds: Set<String>) {
        _state.update { it.setHiddenTerminals(workspaceId, hiddenTerminalIds) }
    }

    fun setHerdrAgentWorkspaceCollapsed(serverId: String, workspaceId: String, collapsed: Boolean) {
        _state.update { it.setHerdrAgentWorkspaceCollapsed(serverId, workspaceId, collapsed) }
    }

    fun unbindHerdr(workspaceId: String) {
        _state.update { it.unbindHerdr(workspaceId) }
    }

    fun activateWorkspace(workspaceId: String) {
        _state.update { it.activateWorkspace(workspaceId, System.currentTimeMillis()) }
    }

    fun deactivate() {
        _state.update { it.deactivate() }
    }

    fun renameWorkspace(workspaceId: String, name: String) {
        _state.update { it.renameWorkspace(workspaceId, name) }
    }

    fun deleteWorkspace(workspaceId: String) {
        _state.update { it.removeWorkspace(workspaceId, System.currentTimeMillis()) }
    }

    fun deleteServer(serverId: String) {
        _state.update { it.removeServer(serverId) }
    }

    fun openTab(workspaceId: String, tab: Tab, activate: Boolean = true) {
        _state.update { it.addTab(workspaceId, tab, activate) }
    }

    fun activateTab(tabId: String) {
        _state.update { it.activateTab(tabId) }
    }

    fun closeTab(tabId: String) {
        _state.update { it.closeTab(tabId) }
    }

    fun moveTab(tabId: String, toIndex: Int) {
        _state.update { it.moveTab(tabId, toIndex) }
    }

    fun renameTerminal(tabId: String, name: String?) {
        _state.update { it.updateTab(tabId) { tab -> if (tab is Tab.Terminal) tab.copy(name = name) else tab } }
    }

    override fun browser(key: SftpBrowserKey): SftpBrowserState? = _state.value.browser(key)

    override fun observeBrowser(key: SftpBrowserKey): Flow<SftpBrowserState?> =
        state.map { it.browser(key) }.distinctUntilChanged()

    override fun openBrowser(key: SftpBrowserKey, initialDir: String) {
        _state.update { it.openBrowser(key, initialDir) }
    }

    override fun updateBrowser(key: SftpBrowserKey, transform: (SftpBrowserState) -> SftpBrowserState) {
        _state.update { it.updateBrowser(key, transform) }
    }

    fun setTerminalCwd(tabId: String, cwd: String) {
        _state.update { it.updateTab(tabId) { tab -> if (tab is Tab.Terminal) tab.copy(cwd = cwd) else tab } }
    }

    fun close() {
        saveJob.cancel()
        runCatching { file.write(_state.value) }.onFailure { _persistenceError.value = it.message }
    }

    /** Runs in the repository's app scope, so stopping or replacing the activity cannot cancel the write. */
    fun flush() = scope.launch { persist() }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun persist() {
        try {
            writes.withLock { withContext(Dispatchers.IO) { file.write(_state.value) } }
            _persistenceError.value = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _persistenceError.value = error.message ?: "Couldn't save workspace state"
        }
    }

    companion object {
        @JvmStatic
        fun create(filesDir: File): WorkspaceRepository =
            WorkspaceRepository(WorkspaceFile(File(filesDir, "workspaces.json")), MainScope())
    }
}
