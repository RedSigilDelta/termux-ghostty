package com.mrndtvndv.term

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.navigation.AppNavigator
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerCoordinatorAccess
import com.mrndtvndv.term.server.ServerRepositoryAccess
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.WorkspaceRepository
import com.mrndtvndv.term.workspace.WorkspaceState
import com.mrndtvndv.term.workspace.newId
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerListUiState(
    val servers: List<ServerConfig> = emptyList(),
    val activeIds: Set<String> = emptySet(),
    val connectingId: String? = null,
    val disconnectingId: String? = null,
    val error: String? = null,
    val localConfig: ServerConfig? = null,
)

/** Tab kinds that can be added from the workspace tab strip. */
enum class TabType { Terminal, Sftp, Git }

@Suppress("TooManyFunctions")
class AppViewModel(
    private val sessionManager: AppSessionManagerAccess,
    val notificationState: NotificationState,
    private val repository: WorkspaceRepository,
) : ViewModel() {

    private val coordinator: ServerCoordinatorAccess get() = sessionManager.coordinator
    private val serverRepository: ServerRepositoryAccess get() = sessionManager.serverRepository
    private val connectionStores = ConnectionViewModelStores()

    val workspaces: StateFlow<WorkspaceState> = repository.state
    val navigator = AppNavigator(repository.state)

    private val _serverListState = MutableStateFlow(ServerListUiState())
    val serverListState: StateFlow<ServerListUiState> = _serverListState.asStateFlow()

    init {
        reloadServers()
        _serverListState.update { it.copy(activeIds = coordinator.activeIds) }
        observeSessionFinishes()
        restoreActiveWorkspace()
    }

    /**
     * Sessions outlive this ViewModel (owned by the app-scoped
     * AppSessionManager), so when one finishes on its own we only need to
     * reflect that in the UI.
     */
    private fun observeSessionFinishes() {
        viewModelScope.launch {
            sessionManager.sessionFinished.collect { serverId ->
                if (coordinator.getServer(serverId) != null) return@collect
                _serverListState.update { it.copy(activeIds = it.activeIds - serverId) }
                closeServerWorkspaces(serverId)
            }
        }
    }

    /**
     * If the app was in a workspace when it was killed, reconnect to that
     * workspace's server and reopen that workspace.
     *
     * If the session is still alive in the app-scoped manager (dismissed
     * from recents), connect is a no-op re-attach: the live session is
     * returned immediately without a new SSH connection.
     */
    private fun restoreActiveWorkspace() {
        val workspace = repository.state.value.activeWorkspace ?: return
        if (serverRepository.get(workspace.serverId) == null) return
        connectServer(workspace.serverId) { openWorkspace(workspace.id) }
    }

    /** Called when the workspace route is popped back to the server list. */
    fun leaveWorkspace() {
        syncTerminalCwds()
        repository.deactivate()
    }

    /** Copies each live terminal's current working directory onto its tab so new shells start there. */
    fun syncTerminalCwds() {
        repository.state.value.workspaces.forEach { workspace ->
            val server = coordinator.getServer(workspace.serverId) ?: return@forEach
            workspace.tabs.filterIsInstance<Tab.Terminal>().forEach { tab ->
                val entry = server.terminals.value[tab.id] ?: return@forEach
                val cwd = if (server.config.isLocal) entry.session.cwd else entry.session.reportedCwd
                if (cwd != null && cwd != tab.cwd) repository.setTerminalCwd(tab.id, cwd)
            }
        }
    }

    // ── Server management ─────────────────────────────────────────────

    private fun reloadServers() {
        val servers = serverRepository.loadAll()
        _serverListState.update {
            it.copy(servers = servers, localConfig = servers.find { config -> config.id == LOCAL_TERMINAL_ID })
        }
    }

    fun saveServer(config: ServerConfig) {
        serverRepository.add(config)
        reloadServers()
    }

    fun updateServer(config: ServerConfig) {
        serverRepository.update(config)
        reloadServers()
    }

    fun deleteServer(id: String) {
        sessionManager.disconnect(id)
        _serverListState.update { it.copy(activeIds = it.activeIds - id) }
        closeServerWorkspaces(id)
        repository.deleteServer(id)
        serverRepository.remove(id)
        reloadServers()
    }

    fun getServer(serverId: String): Server? = coordinator.getServer(serverId)

    fun connectionStore(serverId: String, tabId: String? = null): ViewModelStoreOwner =
        connectionStores.owner(serverId, tabId)

    /**
     * Handle a tapped terminal notification: open the workspace that owns
     * [tabId] on the tab, or the server's workspace when the tab is unknown.
     */
    fun focusTerminalNotification(serverId: String?, tabId: String?) {
        val targetServerId = serverId ?: return
        val target = tabId?.let { repository.state.value.workspaceOf(it) }?.takeIf { it.serverId == targetServerId }
        val open: (Server) -> Unit = { server ->
            if (target == null) openServer(server) else openWorkspace(target.id, tabId)
        }
        val server = coordinator.getServer(targetServerId)
        if (server != null) open(server) else connectServer(targetServerId, open)
    }

    /**
     * Update configuration for the local terminal (startup command and upload settings).
     * Creates the config first if it doesn't exist yet.
     */
    fun updateLocalConfig(
        command: String,
        imagePasteEnabled: Boolean = false,
        imagePasteDirectory: String? = null,
        imagePasteAutoCleanup: Boolean = true,
        maxFiles: Int = 20,
    ) {
        val trimmed = command.trim()
        val existing = serverRepository.get(LOCAL_TERMINAL_ID)
        val updated = (existing ?: ServerConfig(
            id = LOCAL_TERMINAL_ID,
            label = "Local Terminal",
            isLocal = true,
        )).copy(
            startupCommand = trimmed.ifEmpty { null },
            imagePasteEnabled = imagePasteEnabled,
            imagePasteDirectory = imagePasteDirectory?.trim()?.ifEmpty { null },
            imagePasteAutoCleanup = imagePasteAutoCleanup,
            imagePasteMaxFiles = maxFiles.coerceAtLeast(1),
        )
        if (existing != null) {
            serverRepository.update(updated)
        } else {
            serverRepository.add(updated)
        }
        reloadServers()
    }

    /**
     * Update the startup command for the local terminal.
     * Creates the config first if it doesn't exist yet.
     */
    fun setLocalStartupCommand(command: String) {
        val existing = serverRepository.get(LOCAL_TERMINAL_ID)
        updateLocalConfig(
            command = command,
            imagePasteEnabled = existing?.imagePasteEnabled ?: false,
            imagePasteDirectory = existing?.imagePasteDirectory,
            imagePasteAutoCleanup = existing?.imagePasteAutoCleanup ?: true,
            maxFiles = existing?.safeImagePasteMaxFiles ?: ServerConfig.DEFAULT_IMAGE_PASTE_MAX_FILES,
        )
    }

    // ── Connection ────────────────────────────────────────────────────

    fun connect(serverId: String) {
        connectServer(serverId, ::openServer)
    }

    private fun connectServer(serverId: String, onConnected: (Server) -> Unit) {
        if (serverRepository.get(serverId) == null) return
        _serverListState.update { it.copy(connectingId = serverId, error = null) }

        viewModelScope.launch {
            val result = sessionManager.connect(serverId)
            result.fold(
                onSuccess = { server ->
                    _serverListState.update {
                        it.copy(connectingId = null, activeIds = it.activeIds + serverId)
                    }
                    onConnected(server)
                },
                onFailure = { error ->
                    _serverListState.update { it.copy(connectingId = null, error = error.message) }
                    // Don't reopen a dead connection's workspace on every launch.
                    if (repository.state.value.activeWorkspace?.serverId == serverId) repository.deactivate()
                },
            )
        }
    }

    /**
     * Start a local terminal shell.
     * Uses a singleton config with a well-known ID so tapping "Local Terminal"
     * always reuses the same entry (no duplicates in saved servers).
     */
    fun startLocalTerminal() {
        var config = serverRepository.get(LOCAL_TERMINAL_ID)
        if (config == null) {
            config = ServerConfig(
                id = LOCAL_TERMINAL_ID,
                label = "Local Terminal",
                isLocal = true,
            )
            saveServer(config)
        }
        connect(config.id)
    }

    fun disconnect(id: String) {
        _serverListState.update { it.copy(disconnectingId = id) }
        sessionManager.disconnect(id)
        _serverListState.update { it.copy(disconnectingId = null, activeIds = it.activeIds - id) }
        closeServerWorkspaces(id)
    }

    // ── Workspace ─────────────────────────────────────────────────────

    /** Opens the server's most recent workspace, creating one rooted at the server's home if none exists. */
    private fun openServer(server: Server) {
        val serverId = server.config.id
        val workspace = repository.state.value.workspacesFor(serverId).firstOrNull()
            ?: repository.createWorkspace(serverId, server.config.label, server.homeDir)
        openWorkspace(workspace.id)
    }

    private fun openWorkspace(workspaceId: String, tabId: String? = null) {
        if (repository.state.value.workspaces.none { it.id == workspaceId }) return
        if (tabId != null) repository.activateTab(tabId) else repository.activateWorkspace(workspaceId)
        navigator.openWorkspace(workspaceId)
    }

    /** Drops the open route and per-tab state of every workspace of [serverId]. */
    private fun closeServerWorkspaces(serverId: String) {
        repository.state.value.workspacesFor(serverId).forEach { navigator.closeWorkspace(it.id) }
        connectionStores.clear(serverId)
        if (repository.state.value.activeWorkspace?.serverId == serverId) repository.deactivate()
    }

    fun createWorkspace(serverId: String, name: String, root: String) {
        val workspace = repository.createWorkspace(serverId, name, root)
        openWorkspace(workspace.id)
    }

    fun switchWorkspace(workspaceId: String) {
        openWorkspace(workspaceId)
    }

    fun renameWorkspace(workspaceId: String, name: String) {
        repository.renameWorkspace(workspaceId, name)
    }

    fun deleteWorkspace(workspaceId: String) {
        val workspace = repository.state.value.workspaces.firstOrNull { it.id == workspaceId } ?: return
        val wasOpen = navigator.currentWorkspaceId == workspaceId
        workspace.tabs.forEach { tab ->
            if (tab is Tab.Terminal) sessionManager.closeTerminal(workspace.serverId, tab, terminate = true)
            connectionStores.clearTab(workspace.serverId, tab.id)
        }
        navigator.closeWorkspace(workspaceId)
        repository.deleteWorkspace(workspaceId)
        if (wasOpen) {
            repository.state.value.workspacesFor(workspace.serverId).firstOrNull()?.let { openWorkspace(it.id) }
        }
    }

    fun addTab(workspaceId: String, type: TabType) {
        val root = repository.state.value.workspaces.firstOrNull { it.id == workspaceId }?.root ?: return
        val tab = when (type) {
            TabType.Terminal -> Tab.Terminal(newId(), cwd = root)
            TabType.Sftp -> Tab.Sftp(newId(), dir = root)
            TabType.Git -> Tab.Git(newId(), repoDir = root)
        }
        repository.openTab(workspaceId, tab)
    }

    fun openTerminalHere(dir: String) {
        val workspaceId = navigator.currentWorkspaceId ?: return
        repository.openTab(workspaceId, Tab.Terminal(newId(), cwd = dir))
    }

    fun openViewer(path: String) {
        val workspaceId = navigator.currentWorkspaceId ?: return
        repository.openTab(workspaceId, Tab.Viewer(newId(), path))
    }

    fun closeTab(tabId: String) {
        val workspace = repository.state.value.workspaceOf(tabId) ?: return
        val tab = workspace.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab is Tab.Terminal) sessionManager.closeTerminal(workspace.serverId, tab, terminate = true)
        navigator.workspace(workspace.id)?.dropTab(tabId)
        connectionStores.clearTab(workspace.serverId, tabId)
        repository.closeTab(tabId)
    }

    fun moveTab(tabId: String, toIndex: Int) {
        repository.moveTab(tabId, toIndex)
    }

    fun activateTab(tabId: String) {
        repository.activateTab(tabId)
    }

    fun renameTerminal(tabId: String, name: String?) {
        repository.renameTerminal(tabId, name?.trim()?.ifEmpty { null })
    }

    fun setSftpDir(tabId: String, dir: String) {
        repository.setSftpDir(tabId, dir)
    }

    suspend fun openTerminal(serverId: String, tab: Tab.Terminal): Result<TerminalSession> =
        sessionManager.openTerminal(serverId, tab)

    fun restartTerminal(serverId: String, tab: Tab.Terminal) {
        syncTerminalCwds()
        sessionManager.closeTerminal(serverId, tab, terminate = false)
    }

    override fun onCleared() {
        connectionStores.clearAll()
    }

    private companion object {
        const val LOCAL_TERMINAL_ID = "local_terminal"
    }
}
