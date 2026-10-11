package com.mrndtvndv.term.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.HerdrWorkspaceResolver
import com.mrndtvndv.term.server.herdrResolver
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WorkspaceViewModel(
    private val serverId: String,
    private val sessionManager: AppSessionManagerAccess,
) : ViewModel() {

    private val coordinator get() = sessionManager.coordinator

    private val _herdrState = MutableStateFlow(HerdrUiState())
    val herdrState: StateFlow<HerdrUiState> = _herdrState.asStateFlow()
    private var herdrRefreshJob: Job? = null

    fun observeTerminalProgress(session: TerminalSession) = sessionManager.observeTerminalProgress(session)

    fun observeProgramStatus(session: TerminalSession) = sessionManager.observeProgramStatus(session)

    fun markProgramStatusSeen(session: TerminalSession) = sessionManager.markProgramStatusSeen(session)

    fun loadHerdrAgents() {
        val resolver = coordinator.getServer(serverId)?.herdrResolver()
        if (resolver == null) {
            _herdrState.update { it.copy(isLoading = false, error = "Herdr is unavailable for this session") }
            return
        }

        // A warm cache stays on screen. StateFlow drops the refresh when the data is unchanged.
        if (_herdrState.value.workspaces.isEmpty()) {
            _herdrState.update { it.copy(isLoading = true, error = null) }
        }
        herdrRefreshJob?.cancel()
        herdrRefreshJob = viewModelScope.launch {
            runCatching { resolver.listWorkspaceTabs() }
                // A cancelled refresh must not publish an error over the newer request's state.
                .onFailure { error -> if (error is CancellationException) throw error }
                .fold(
                    onSuccess = { workspaces ->
                        _herdrState.update { it.copy(workspaces = workspaces, isLoading = false, error = null) }
                    },
                    onFailure = { error ->
                        _herdrState.update {
                            it.copy(isLoading = false, error = error.message ?: "Unable to query Herdr workspaces")
                        }
                    },
                )
        }
    }

    fun focusTab(tab: HerdrWorkspaceResolver.HerdrTabNode) {
        viewModelScope.launch { coordinator.focusHerdr(serverId) { this.focusTab(tab) } }
    }

    fun focusPane(pane: HerdrWorkspaceResolver.HerdrPaneNode) {
        // Assume the focus succeeds; the refresh after the command restores the real state if it did not.
        _herdrState.update { state -> state.copy(workspaces = state.workspaces.withFocusedPane(pane)) }
        viewModelScope.launch {
            coordinator.focusHerdr(serverId) { this.focusPane(pane) }
            loadHerdrAgents()
        }
    }

    fun closePane(pane: HerdrWorkspaceResolver.HerdrPaneNode) {
        val resolver = coordinator.getServer(serverId)?.herdrResolver() ?: return
        // Drop the row now so the cached sheet does not keep showing a closed pane until the refresh lands.
        _herdrState.update { state -> state.copy(workspaces = state.workspaces.withoutPane(pane.paneId)) }
        viewModelScope.launch {
            runCatching { resolver.closePane(pane.paneId) }
            loadHerdrAgents()
        }
    }

    private fun List<HerdrWorkspaceResolver.HerdrWorkspaceNode>.withFocusedPane(
        target: HerdrWorkspaceResolver.HerdrPaneNode,
    ): List<HerdrWorkspaceResolver.HerdrWorkspaceNode> = map { workspace ->
        workspace.copy(
            tabs = workspace.tabs.map { tab ->
                tab.copy(
                    focused = tab.tabId == target.tabId,
                    panes = tab.panes.map { it.copy(focused = it.paneId == target.paneId) },
                )
            },
        )
    }

    private fun List<HerdrWorkspaceResolver.HerdrWorkspaceNode>.withoutPane(
        paneId: String,
    ): List<HerdrWorkspaceResolver.HerdrWorkspaceNode> = mapNotNull { workspace ->
        val tabs = workspace.tabs.mapNotNull { tab ->
            val panes = tab.panes.filter { it.paneId != paneId }
            when {
                panes.size == tab.panes.size -> tab
                panes.isEmpty() -> null
                else -> tab.copy(panes = panes)
            }
        }
        workspace.copy(tabs = tabs).takeIf { tabs.isNotEmpty() }
    }
}
