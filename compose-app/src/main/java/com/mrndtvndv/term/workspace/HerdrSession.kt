package com.mrndtvndv.term.workspace

import com.mrndtvndv.term.herdr.HerdrPhase
import com.mrndtvndv.term.herdr.HerdrState
import com.mrndtvndv.term.herdr.workspaceById

private const val HerdrFullUiTabPrefix = "herdr-full:"

/** Tab id of the one terminal that runs Herdr's full UI for [serverId]. Stable, so the server keeps one session tab. */
fun herdrFullUiTabId(serverId: String): String = HerdrFullUiTabPrefix + serverId

/** True for the terminal that runs Herdr's full UI. It is the only terminal a Herdr-enabled server's session has. */
fun isHerdrFullUiTab(tab: Tab): Boolean = tab is Tab.Terminal && tab.id.startsWith(HerdrFullUiTabPrefix)

/** True for a workspace that holds a server's Herdr session. Its full-UI terminal is the only terminal it shows. */
fun isHerdrSessionWorkspace(workspace: Workspace): Boolean = workspace.tabs.any { isHerdrFullUiTab(it) }

/** Viewer tabs of one remote workspace collection, in tab order. Other collections never appear here. */
fun viewerTabsFor(tabs: List<Tab>, scope: HerdrFileScope): List<Tab.Viewer> =
    tabs.filterIsInstance<Tab.Viewer>().filter { it.scope == scope }

/** Separates the parts of a collection key. A unit separator cannot occur in a server id, socket path or Herdr id. */
private const val ScopeKeySeparator = "\u001F"

/** Collection key of a remote workspace. [herdrFileScopeFromKey] reverses it. */
fun herdrFileScopeKey(scope: HerdrFileScope): String =
    listOf(scope.serverId, scope.socketPath, scope.herdrWorkspaceId).joinToString(ScopeKeySeparator)

/** The scope behind a collection key, or null when [key] is not a key from [herdrFileScopeKey]. */
fun herdrFileScopeFromKey(key: String): HerdrFileScope? {
    val parts = key.split(ScopeKeySeparator)
    if (parts.size != 3 || parts.any { it.isEmpty() }) return null
    return HerdrFileScope(parts[0], parts[1], parts[2])
}

/** Store key for a sheet's view model, one per sheet kind and collection. Cleared with the server's other stores. */
fun herdrSheetTabId(kind: String, scopeKey: String): String = "herdr-sheet:$kind:$scopeKey"

/**
 * The focused remote workspace of a Herdr session, as sheets read it. Only global snapshot focus is used, since
 * upstream `session.snapshot` does not report focus per attached client. The UI must label this as limited.
 */
sealed interface HerdrFileContext {
    /**
     * [cwd] is the focused pane's foreground cwd, else its cwd. Blank means unknown. [stale] is true when the
     * control connection is not Live or the snapshot is not fresh; the values are then the last coherent ones.
     */
    data class Ready(val scopeKey: String, val label: String, val cwd: String, val stale: Boolean) : HerdrFileContext

    data class Unavailable(val message: String) : HerdrFileContext
}

/** Maps the control cache to the context. Focus missing from the snapshot is unavailable, never a guessed workspace. */
fun herdrFileContext(serverId: String, socketPath: String, state: HerdrState): HerdrFileContext {
    val snapshot = state.snapshot
        ?: return HerdrFileContext.Unavailable(state.error?.message ?: "Herdr has not reported a snapshot yet")
    val workspaceId = snapshot.focusedWorkspaceId
        ?: return HerdrFileContext.Unavailable("Herdr reports no focused workspace")
    val label = snapshot.workspaceById(workspaceId)?.label?.takeIf { it.isNotBlank() } ?: workspaceId
    val pane = snapshot.focusedPaneId?.let { focused -> snapshot.panes.firstOrNull { it.id == focused } }
    val cwd = listOfNotNull(pane?.foregroundCwd, pane?.cwd).firstOrNull { it.isNotBlank() }.orEmpty()
    val scopeKey = herdrFileScopeKey(HerdrFileScope(serverId, socketPath, workspaceId))
    val stale = state.phase != HerdrPhase.Live || !state.snapshotFresh
    return HerdrFileContext.Ready(scopeKey, label, cwd, stale)
}
