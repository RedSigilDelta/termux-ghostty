package com.mrndtvndv.term.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.WorkspaceState
import kotlinx.coroutines.flow.StateFlow

class AppNavigator(private val workspaceState: StateFlow<WorkspaceState>) {
    val backStack = NavBackStack<NavKey>(AppRoute.ServerList)

    private val workspaces = mutableMapOf<String, WorkspaceNavigator>()

    val currentWorkspaceId: String?
        get() = backStack.filterIsInstance<AppRoute.Workspace>().lastOrNull()?.workspaceId

    fun workspace(workspaceId: String): WorkspaceNavigator? = workspaces[workspaceId]

    fun navigate(route: AppRoute) {
        backStack.add(route)
    }

    fun goBack() {
        val top = backStack.lastOrNull()
        if (top is AppRoute.Workspace && workspaces[top.workspaceId]?.goBack() == true) return
        if (backStack.size > 1) {
            removeFrom(backStack.lastIndex)
        }
    }

    fun openWorkspace(workspaceId: String) {
        if (workspaces[workspaceId] == null) {
            workspaces[workspaceId] = WorkspaceNavigator { activeTab(workspaceId) }
        }
        val route = AppRoute.Workspace(workspaceId)
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
        val index = backStack.indexOf(AppRoute.Workspace(workspaceId))
        if (index < 0) return
        removeFrom(index)
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
