package com.mrndtvndv.term.workspace

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceState(
    val workspaces: List<Workspace> = emptyList(),
    val activeWorkspaceId: String? = null,
) {
    val activeWorkspace: Workspace?
        get() = workspaces.firstOrNull { it.id == activeWorkspaceId }

    fun workspaceOf(tabId: String): Workspace? =
        workspaces.firstOrNull { workspace -> workspace.tabs.any { it.id == tabId } }

    fun tab(tabId: String): Tab? =
        workspaces.firstNotNullOfOrNull { workspace -> workspace.tabs.firstOrNull { it.id == tabId } }

    fun workspacesFor(serverId: String): List<Workspace> =
        workspaces.filter { it.serverId == serverId }.sortedByDescending { it.lastUsed }

    fun addWorkspace(workspace: Workspace): WorkspaceState =
        copy(workspaces = workspaces + workspace, activeWorkspaceId = workspace.id)

    fun activateWorkspace(workspaceId: String, now: Long): WorkspaceState {
        if (workspaces.none { it.id == workspaceId }) return this
        return copy(
            workspaces = workspaces.map { if (it.id == workspaceId) it.copy(lastUsed = now) else it },
            activeWorkspaceId = workspaceId,
        )
    }

    fun deactivate(): WorkspaceState = copy(activeWorkspaceId = null)

    fun removeWorkspace(workspaceId: String, now: Long): WorkspaceState {
        val removed = workspaces.firstOrNull { it.id == workspaceId } ?: return this
        val remaining = copy(workspaces = workspaces.filter { it.id != workspaceId })
        if (activeWorkspaceId != workspaceId) return remaining
        val next = remaining.workspacesFor(removed.serverId).firstOrNull()
        return if (next == null) remaining.deactivate() else remaining.activateWorkspace(next.id, now)
    }

    fun removeServer(serverId: String): WorkspaceState {
        val remaining = copy(workspaces = workspaces.filter { it.serverId != serverId })
        return if (activeWorkspace?.serverId == serverId) remaining.deactivate() else remaining
    }

    fun renameWorkspace(workspaceId: String, name: String): WorkspaceState =
        mapWorkspace(workspaceId) { it.copy(name = name) }

    fun addTab(workspaceId: String, tab: Tab, activate: Boolean): WorkspaceState =
        mapWorkspace(workspaceId) { workspace ->
            workspace.copy(
                tabs = workspace.tabs + tab,
                activeTabId = if (activate || workspace.activeTabId == null) tab.id else workspace.activeTabId,
            )
        }

    fun activateTab(tabId: String): WorkspaceState {
        val owner = workspaceOf(tabId) ?: return this
        return copy(
            workspaces = workspaces.map { if (it.id == owner.id) it.copy(activeTabId = tabId) else it },
            activeWorkspaceId = owner.id,
        )
    }

    fun updateTab(tabId: String, transform: (Tab) -> Tab): WorkspaceState {
        val owner = workspaceOf(tabId) ?: return this
        return mapWorkspace(owner.id) { workspace ->
            workspace.copy(tabs = workspace.tabs.map { if (it.id == tabId) transform(it) else it })
        }
    }

    fun closeTab(tabId: String): WorkspaceState {
        val owner = workspaceOf(tabId) ?: return this
        val index = owner.tabs.indexOfFirst { it.id == tabId }
        val tabs = owner.tabs.filter { it.id != tabId }
        val activeTabId = when (owner.activeTabId) {
            tabId -> tabs.getOrNull(index)?.id ?: tabs.lastOrNull()?.id
            else -> owner.activeTabId
        }
        return mapWorkspace(owner.id) { it.copy(tabs = tabs, activeTabId = activeTabId) }
    }

    private fun mapWorkspace(workspaceId: String, transform: (Workspace) -> Workspace): WorkspaceState =
        copy(workspaces = workspaces.map { if (it.id == workspaceId) transform(it) else it })
}

@Serializable
data class Workspace(
    val id: String,
    val serverId: String,
    val name: String,
    val root: String,
    val tabs: List<Tab> = emptyList(),
    val activeTabId: String? = null,
    val lastUsed: Long = 0,
)

@Serializable
sealed interface Tab {
    val id: String

    @Serializable @SerialName("terminal")
    data class Terminal(override val id: String, val name: String? = null, val cwd: String) : Tab

    @Serializable @SerialName("sftp")
    data class Sftp(override val id: String, val dir: String) : Tab

    @Serializable @SerialName("git")
    data class Git(override val id: String, val repoDir: String) : Tab

    @Serializable @SerialName("viewer")
    data class Viewer(override val id: String, val path: String) : Tab
}

fun newId(): String = UUID.randomUUID().toString()
