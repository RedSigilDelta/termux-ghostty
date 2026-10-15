package com.mrndtvndv.term.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.WorkspaceState
import com.mrndtvndv.term.workspace.isHerdrSessionWorkspace
import kotlinx.coroutines.flow.StateFlow

class AppNavigator(private val workspaceState: StateFlow<WorkspaceState>) {
    val backStack = NavBackStack<NavKey>(AppRoute.ServerList)

    private val workspaces = mutableMapOf<String, WorkspaceNavigator>()

    /** Workspace of the most recent workspace or Herdr session route, resolved through its server when needed. */
    val currentWorkspaceId: String?
        get() = backStack.asReversed().firstNotNullOfOrNull { workspaceIdOf(it) }

    fun workspace(workspaceId: String): WorkspaceNavigator? = workspaces[workspaceId]

    fun navigate(route: AppRoute) {
        backStack.add(route)
    }

    fun goBack() {
        val top = backStack.lastOrNull()
        val topWorkspaceId = workspaceIdOf(top)
        if (topWorkspaceId != null && workspaces[topWorkspaceId]?.goBack() == true) return
        if (backStack.size > 1) {
            removeFrom(backStack.lastIndex)
        }
    }

    /** Shows [workspaceId]. A Herdr session workspace is shown on its [AppRoute.HerdrSession] route. */
    fun openWorkspace(workspaceId: String) {
        if (workspaces[workspaceId] == null) {
            workspaces[workspaceId] = WorkspaceNavigator { activeTab(workspaceId) }
        }
        val route = routeFor(workspaceId)
        val index = backStack.indexOf(route)
        if (index >= 0) {
            removeFrom(index + 1)
        } else {
            removeFrom(1)
            backStack.add(route)
        }
    }

    fun closeWorkspace(workspaceId: String) {
        workspaces.remove(workspaceId)
        val index = backStack.indexOfFirst { workspaceIdOf(it) == workspaceId }
        if (index < 0) return
        removeFrom(index)
    }

    private fun routeFor(workspaceId: String): AppRoute {
        val workspace = workspaceState.value.workspaces.firstOrNull { it.id == workspaceId }
        return if (workspace != null && isHerdrSessionWorkspace(workspace)) {
            AppRoute.HerdrSession(workspace.serverId)
        } else {
            AppRoute.Workspace(workspaceId)
        }
    }

    /** Workspace behind a route, or null. A Herdr session route resolves to its server's session workspace. */
    private fun workspaceIdOf(key: NavKey?): String? = when (key) {
        is AppRoute.Workspace -> key.workspaceId
        is AppRoute.HerdrSession -> workspaceState.value.workspacesFor(key.serverId)
            .firstOrNull(::isHerdrSessionWorkspace)?.id
        else -> null
    }

    private fun activeTab(workspaceId: String): Tab? {
        val workspace = workspaceState.value.workspaces.firstOrNull { it.id == workspaceId } ?: return null
        return workspace.tabs.firstOrNull { it.id == workspace.activeTabId }
    }

    private fun removeFrom(index: Int) {
        while (backStack.size > index) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
}
