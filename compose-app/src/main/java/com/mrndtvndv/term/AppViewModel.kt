package com.mrndtvndv.term

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.herdr.HerdrControlClient
import com.mrndtvndv.term.navigation.AppNavigator
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.SavedServersLoad
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerCoordinatorAccess
import com.mrndtvndv.term.server.ServerRepositoryAccess
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.workspace.HerdrFileContext
import com.mrndtvndv.term.workspace.HerdrFileScope
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.Workspace
import com.mrndtvndv.term.workspace.WorkspaceRepository
import com.mrndtvndv.term.workspace.WorkspaceState
import com.mrndtvndv.term.workspace.herdrControlSocketPath
import com.mrndtvndv.term.workspace.herdrFileContext
import com.mrndtvndv.term.workspace.herdrFileScopeFromKey
import com.mrndtvndv.term.workspace.isHerdrFullUiTab
import com.mrndtvndv.term.workspace.isHerdrSessionWorkspace
import com.mrndtvndv.term.workspace.newId
import com.mrndtvndv.term.workspace.viewerTabsFor
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ServerListUiState(
    val servers: List<ServerConfig> = emptyList(),
    val activeIds: Set<String> = emptySet(),
    val connectingId: String? = null,
    val disconnectingId: String? = null,
    val error: String? = null,
    val localConfig: ServerConfig? = null,
    /** Why saved servers cannot be read or written, shown in place of the list. Null when storage is healthy. */
    val storageError: String? = null,
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

    /** Focused-workspace context per server. Written by [refreshHerdrContext]'s collector, read by sheets once. */
    private val herdrContextStores = HashMap<String, MutableStateFlow<HerdrFileContext>>()

    /** One collector per server, tied to the control client it reads. A reconnect replaces both. */
    private val herdrContextCollectors = HashMap<String, Pair<HerdrControlClient, Job>>()

    /** Viewer flows per server and collection key, so a composable gets the same flow on each call. */
    private val herdrViewerFlows = HashMap<String, StateFlow<List<Tab.Viewer>>>()

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
        connectServer(workspace.serverId) { server ->
            // A Herdr server always opens through its session, so an older saved workspace is not reopened directly.
            if (server.config.herdrEnabled || isHerdrSessionWorkspace(workspace)) {
                openServer(server)
            } else {
                openWorkspace(workspace.id)
            }
        }
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
        // An unreadable stored list shows no servers and says why. It never shows a partial list as complete.
        val (servers, storageError) = when (val loaded = serverRepository.load()) {
            is SavedServersLoad.Loaded -> loaded.servers to null
            is SavedServersLoad.Unreadable -> emptyList<ServerConfig>() to loaded.message
        }
        _serverListState.update {
            it.copy(
                servers = servers,
                localConfig = servers.find { config -> config.id == LOCAL_TERMINAL_ID },
                storageError = storageError,
            )
        }
    }

    /** Reloads after a write. A refused or failed write is shown in the list and as a notification, never dropped. */
    private fun afterWrite(result: Result<Unit>) {
        reloadServers()
        result.exceptionOrNull()?.let { failure ->
            val message = failure.message ?: "Saved servers could not be written."
            _serverListState.update { it.copy(storageError = message) }
            notificationState.post("Saved servers", message)
        }
    }

    /** A server that cannot be opened. An unreadable stored list is the reason to show, not "unknown server". */
    private fun reportUnknownServer() {
        val reason = (serverRepository.load() as? SavedServersLoad.Unreadable)?.message
        if (reason != null) _serverListState.update { it.copy(storageError = reason) }
        notificationState.post("Couldn't open server", reason ?: "This server is no longer saved.")
    }

    fun saveServer(config: ServerConfig) {
        afterWrite(serverRepository.add(config))
    }

    fun updateServer(config: ServerConfig) {
        afterWrite(serverRepository.update(config))
    }

    fun deleteServer(id: String) {
        sessionManager.disconnect(id)
        _serverListState.update { it.copy(activeIds = it.activeIds - id) }
        closeServerWorkspaces(id)
        repository.deleteServer(id)
        afterWrite(serverRepository.remove(id))
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
            // A Herdr server always opens through its session, so an older saved workspace is not reopened directly.
            if (target != null && !server.config.herdrEnabled && !isHerdrSessionWorkspace(target)) {
                openWorkspace(target.id, tabId)
            } else {
                openServer(server)
            }
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
        afterWrite(if (existing != null) serverRepository.update(updated) else serverRepository.add(updated))
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
        if (serverRepository.get(serverId) == null) {
            reportUnknownServer()
            return
        }
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

    /**
     * Opens the server's workspace. A Herdr-enabled server opens its session workspace, created when missing. Any other
     * server opens its most recent workspace, creating one rooted at the server's home if none exists.
     */
    private fun openServer(server: Server) {
        val serverId = server.config.id
        if (server.config.herdrEnabled) {
            ensureHerdrSession(serverId)?.let { openWorkspace(it) }
            return
        }
        // A session workspace is Herdr's. Plain mode picks another workspace and never opens the full-UI tab.
        val workspace = repository.state.value.workspacesFor(serverId).firstOrNull { !isHerdrSessionWorkspace(it) }
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
        if (herdrEnabled(serverId)) return
        val workspace = repository.createWorkspace(serverId, name, root)
        openWorkspace(workspace.id)
    }

    fun switchWorkspace(workspaceId: String) {
        val target = workspaceOf(workspaceId) ?: return
        // A Herdr-enabled server shows only its session workspace.
        if (herdrEnabled(target.serverId) && !isHerdrSessionWorkspace(target)) return
        openWorkspace(workspaceId)
    }

    fun renameWorkspace(workspaceId: String, name: String) {
        repository.renameWorkspace(workspaceId, name)
    }

    fun deleteWorkspace(workspaceId: String) {
        val workspace = repository.state.value.workspaces.firstOrNull { it.id == workspaceId } ?: return
        // The session workspace belongs to the server's Herdr session. Only the server ends that session.
        if (isHerdrSessionWorkspace(workspace)) return
        val wasOpen = navigator.currentWorkspaceId == workspaceId
        workspace.tabs.forEach { tab ->
            if (tab is Tab.Terminal) sessionManager.closeTerminal(workspace.serverId, tab)
            connectionStores.clearTab(workspace.serverId, tab.id)
        }
        navigator.closeWorkspace(workspaceId)
        repository.deleteWorkspace(workspaceId)
        if (wasOpen) {
            repository.state.value.workspacesFor(workspace.serverId).firstOrNull()?.let { openWorkspace(it.id) }
        }
    }

    fun addTab(workspaceId: String, type: TabType) {
        val workspace = workspaceOf(workspaceId) ?: return
        // Herdr owns the terminals of a Herdr-enabled server. The app creates none, locally or remotely.
        if (type == TabType.Terminal && herdrEnabled(workspace.serverId)) return
        val root = workspace.root
        val tab = when (type) {
            TabType.Terminal -> Tab.Terminal(newId(), cwd = root)
            TabType.Sftp -> Tab.Sftp(newId(), dir = root)
            TabType.Git -> Tab.Git(newId(), repoDir = root)
        }
        repository.openTab(workspaceId, tab)
    }

    fun openTerminalHere(dir: String) {
        val workspaceId = navigator.currentWorkspaceId ?: return
        val workspace = workspaceOf(workspaceId) ?: return
        if (herdrEnabled(workspace.serverId)) return
        repository.openTab(workspaceId, Tab.Terminal(newId(), cwd = dir))
    }

    /** Opens an ordinary preview in the current workspace. Herdr sessions take [openHerdrViewer] only. */
    fun openViewer(path: String) {
        val workspaceId = navigator.currentWorkspaceId ?: return
        val workspace = workspaceOf(workspaceId) ?: return
        if (isHerdrSessionWorkspace(workspace)) return
        repository.openTab(workspaceId, Tab.Viewer(newId(), path))
    }

    fun closeTab(tabId: String) {
        val workspace = repository.state.value.workspaceOf(tabId) ?: return
        val tab = workspace.tabs.firstOrNull { it.id == tabId } ?: return
        // The Herdr session terminal stays open: it is the server's session, not a local tab to close.
        if (isHerdrFullUiTab(tab)) return
        if (tab is Tab.Terminal) sessionManager.closeTerminal(workspace.serverId, tab)
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

    /** Selects a tab of [workspaceId]. Selection is local only and never focuses anything on a Herdr server. */
    fun selectTab(workspaceId: String, tabId: String) {
        if (workspaceOf(workspaceId)?.tabs?.any { it.id == tabId } != true) return
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
        sessionManager.closeTerminal(serverId, tab)
    }

    // ── Herdr session ─────────────────────────────────────────────────

    /** True for a Herdr-enabled server, which runs Herdr only: one full-UI session, no app workspaces or terminals. */
    private fun herdrEnabled(serverId: String): Boolean = serverRepository.get(serverId)?.herdrEnabled == true

    private fun herdrSessionWorkspace(serverId: String): Workspace? =
        repository.state.value.workspacesFor(serverId).firstOrNull(::isHerdrSessionWorkspace)

    /**
     * The session workspace id of a Herdr-enabled server, created when missing. Null when the server is not
     * Herdr-enabled or not connected. Does not navigate: the caller shows the workspace on its route.
     */
    fun ensureHerdrSession(serverId: String): String? {
        if (!herdrEnabled(serverId)) return null
        return herdrSessionWorkspace(serverId)?.id ?: coordinator.getServer(serverId)?.let { server ->
            repository.createHerdrSessionWorkspace(serverId, server.config.label, server.homeDir).id
        }
    }

    /** Id of the server's session workspace, or null when it has none yet. Lookup only. */
    fun herdrSessionWorkspaceId(serverId: String): String? = herdrSessionWorkspace(serverId)?.id

    /** The server-unique terminal that runs Herdr's full UI. Its tab id is stable, so open and restart reuse it. */
    fun herdrFullUiTab(serverId: String): Tab.Terminal? =
        herdrSessionWorkspace(serverId)?.tabs?.filterIsInstance<Tab.Terminal>()?.firstOrNull(::isHerdrFullUiTab)

    /**
     * Focused remote workspace of the server's Herdr session. Sheets read a Ready value once, when they open.
     * Only global snapshot focus is used, not this client's presented pane, so the UI must label it as limited.
     */
    fun herdrContext(serverId: String): StateFlow<HerdrFileContext> = herdrContextStore(serverId).asStateFlow()

    /**
     * Asks the control client to refresh and keeps [herdrContext] updated from its state. Starts one collector per
     * control client. The refresh is an invalidate into the client's existing coalesced loop.
     */
    fun refreshHerdrContext(serverId: String) {
        val store = herdrContextStore(serverId)
        val server = coordinator.getServer(serverId)
        val herdr = server?.herdr
        if (server == null || herdr == null) {
            store.value = HerdrFileContext.Unavailable("Herdr is not connected")
            return
        }
        herdr.refresh()
        val running = herdrContextCollectors[serverId]
        if (running != null && running.first === herdr && running.second.isActive) return
        running?.second?.cancel()
        val socket = herdrControlSocketPath(server.config.herdrSocketPath, server.homeDir)
        val job = viewModelScope.launch {
            herdr.state.collect { state -> store.value = herdrFileContext(serverId, socket, state) }
        }
        herdrContextCollectors[serverId] = herdr to job
    }

    private fun herdrContextStore(serverId: String): MutableStateFlow<HerdrFileContext> =
        herdrContextStores.getOrPut(serverId) {
            MutableStateFlow(HerdrFileContext.Unavailable("Herdr context has not loaded yet"))
        }

    /** Viewer tabs of one captured remote workspace collection. Other collections of the server never appear. */
    fun herdrViewers(serverId: String, scopeKey: String): StateFlow<List<Tab.Viewer>> =
        herdrViewerFlows.getOrPut("$serverId|$scopeKey") {
            val scope = herdrFileScopeFromKey(scopeKey)?.takeIf { it.serverId == serverId }
            repository.state
                .map { state -> herdrViewerTabs(state, serverId, scope) }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                    herdrViewerTabs(repository.state.value, serverId, scope),
                )
        }

    /**
     * Opens a file preview in the captured collection [scopeKey] and returns its new tab id. Every call makes a new
     * tab. Returns null when refused: the key does not belong to [serverId], or the server has no Herdr session.
     * A download that finishes later still lands in the collection it was captured from.
     */
    fun openHerdrViewer(serverId: String, scopeKey: String, path: String): String? {
        val scope = herdrFileScopeFromKey(scopeKey)
            ?.takeIf { it.serverId == serverId && herdrEnabled(serverId) } ?: return null
        val session = herdrSessionWorkspace(serverId) ?: return null
        val tab = Tab.Viewer(newId(), path, scope = scope)
        repository.openTab(session.id, tab)
        return tab.id
    }

    /** Closes a scoped viewer tab. Ordinary tabs are ignored. */
    fun closeHerdrViewer(tabId: String) {
        val tab = repository.state.value.tab(tabId) as? Tab.Viewer ?: return
        if (tab.scope == null) return
        closeTab(tabId)
    }

    suspend fun focusHerdrPane(serverId: String, paneId: String, tabId: String): Boolean =
        runHerdrAction(serverId, "Couldn't focus pane") { client ->
            client.request("pane.focus", buildJsonObject { put("pane_id", paneId) })
            // Tab focus publishes the selection to the normal Herdr frontend as well.
            client.request("tab.focus", buildJsonObject { put("tab_id", tabId) })
        }

    suspend fun focusHerdrTab(serverId: String, tabId: String): Boolean =
        runHerdrAction(serverId, "Couldn't focus tab") { client ->
            client.request("tab.focus", buildJsonObject { put("tab_id", tabId) })
        }

    suspend fun closeHerdrPane(serverId: String, paneId: String): Boolean =
        runHerdrAction(serverId, "Couldn't close pane") { client ->
            client.request("pane.close", buildJsonObject { put("pane_id", paneId) })
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runHerdrAction(
        serverId: String,
        title: String,
        action: suspend (HerdrControlClient) -> Unit,
    ): Boolean {
        val client = coordinator.getServer(serverId)?.herdr
        if (client == null) {
            notificationState.post(title, "Herdr is not connected")
            return false
        }
        return try {
            action(client)
            client.refresh()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            notificationState.post(title, error.message ?: "Herdr request failed")
            false
        }
    }

    /** Restarts the control client after a failure. Only the user's explicit Retry calls this. */
    fun retryHerdr(serverId: String) {
        val herdr = coordinator.getServer(serverId)?.herdr ?: return
        viewModelScope.launch { runCatching { herdr.start() } }
    }

    private fun herdrViewerTabs(state: WorkspaceState, serverId: String, scope: HerdrFileScope?): List<Tab.Viewer> {
        if (scope == null) return emptyList()
        val session = state.workspacesFor(serverId).firstOrNull(::isHerdrSessionWorkspace) ?: return emptyList()
        return viewerTabsFor(session.tabs, scope)
    }

    private fun workspaceOf(workspaceId: String): Workspace? =
        repository.state.value.workspaces.firstOrNull { it.id == workspaceId }

    override fun onCleared() {
        connectionStores.clearAll()
    }

    private companion object {
        const val LOCAL_TERMINAL_ID = "local_terminal"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
