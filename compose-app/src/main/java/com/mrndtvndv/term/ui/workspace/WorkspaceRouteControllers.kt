package com.mrndtvndv.term.ui.workspace

import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.TabType
import com.mrndtvndv.term.navigation.AppRoute
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.Workspace

/** Dialog actions for the workspace screen. Each confirmed action closes its dialog, then acts. */
internal class WorkspaceDialogController(
    private val appViewModel: AppViewModel,
    private val serverId: String,
    private val setDialog: (WorkspaceDialogs?) -> Unit,
    private val setGridVisible: (Boolean) -> Unit,
) : WorkspaceDialogActions {
    override fun dismiss() = setDialog(null)

    override fun show(dialog: WorkspaceDialogs) = setDialog(dialog)

    override fun createWorkspace(name: String, root: String) {
        setDialog(null)
        appViewModel.createWorkspace(serverId, name, root)
    }

    override fun renameWorkspace(workspaceId: String, name: String) {
        setDialog(null)
        appViewModel.renameWorkspace(workspaceId, name)
    }

    override fun renameTerminal(tabId: String, name: String?) {
        setDialog(null)
        appViewModel.renameTerminal(tabId, name)
    }

    override fun deleteWorkspace(workspaceId: String) {
        setDialog(null)
        setGridVisible(false)
        appViewModel.deleteWorkspace(workspaceId)
    }
}

/** Actions for the tabs grid on the workspace screen. The route only lays out; behaviour lives here. */
internal class WorkspaceGridController(
    private val appViewModel: AppViewModel,
    private val workspace: Workspace,
    private val server: Server,
    private val setDialog: (WorkspaceDialogs?) -> Unit,
    private val setGridVisible: (Boolean) -> Unit,
) : TabsGridActions {
    override fun onSelectTab(id: String) {
        appViewModel.selectTab(workspace.id, id)
        setGridVisible(false)
    }

    override fun onCloseTab(id: String) {
        appViewModel.closeTab(id)
    }

    override fun onMoveTab(id: String, toIndex: Int) {
        appViewModel.moveTab(id, toIndex)
    }

    override fun onRenameTab(id: String) {
        val tab = workspace.tabs.firstOrNull { it.id == id } as? Tab.Terminal ?: return
        setDialog(WorkspaceDialogs.RenameTerminal(tab.id, tab.name))
    }

    override fun onNewTab(type: TabType) {
        appViewModel.addTab(workspace.id, type)
        setGridVisible(false)
    }

    override fun onWorkspaceMenu(action: WorkspaceMenuAction) {
        when (action) {
            is WorkspaceMenuAction.Select -> {
                appViewModel.switchWorkspace(action.workspaceId)
                setGridVisible(false)
            }
            WorkspaceMenuAction.New -> setDialog(WorkspaceDialogs.NewWorkspace(defaultRoot = server.homeDir))
            WorkspaceMenuAction.Rename -> setDialog(WorkspaceDialogs.RenameWorkspace(workspace.id, workspace.name))
            WorkspaceMenuAction.Delete -> setDialog(
                WorkspaceDialogs.DeleteWorkspace(workspace.id, workspace.name, bound = workspace.herdr != null),
            )
            // Herdr binding and its remote menu moved to the Herdr session route, so the grid never offers them.
            is WorkspaceMenuAction.Bind, WorkspaceMenuAction.ShowHidden, WorkspaceMenuAction.CloseOnServer -> Unit
        }
    }

    override fun onOpenSettings() {
        setGridVisible(false)
        appViewModel.navigator.navigate(AppRoute.Settings)
    }

    override fun onDismiss() = setGridVisible(false)
}
