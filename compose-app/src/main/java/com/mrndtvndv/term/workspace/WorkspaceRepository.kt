package com.mrndtvndv.term.workspace

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

/** Main-thread only. */
class WorkspaceRepository(private val file: WorkspaceFile, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(file.read())
    val state: StateFlow<WorkspaceState> = _state.asStateFlow()

    @OptIn(FlowPreview::class)
    private val saveJob = scope.launch {
        state.debounce(300).collect { value -> withContext(Dispatchers.IO) { runCatching { file.write(value) } } }
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

    fun setSftpDir(tabId: String, dir: String) {
        _state.update { it.updateTab(tabId) { tab -> if (tab is Tab.Sftp) tab.copy(dir = dir) else tab } }
    }

    fun setTerminalCwd(tabId: String, cwd: String) {
        _state.update { it.updateTab(tabId) { tab -> if (tab is Tab.Terminal) tab.copy(cwd = cwd) else tab } }
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
