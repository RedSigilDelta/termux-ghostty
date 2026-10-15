package com.mrndtvndv.term.workspace

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@Suppress("TooManyFunctions")
data class WorkspaceState(
    val workspaces: List<Workspace> = emptyList(),
    val activeWorkspaceId: String? = null,
    /** SFTP browsers of remote Herdr workspaces. Ordinary SFTP tabs keep theirs in [Tab.Sftp]. */
    val herdrBrowsers: List<HerdrSftpBrowser> = emptyList(),
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
        val remaining = copy(
            workspaces = workspaces.filter { it.serverId != serverId },
            herdrBrowsers = herdrBrowsers.filter { it.scope.serverId != serverId },
        )
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

    /** Moves a tab to [toIndex] within its workspace; the index is clamped. Active tab is unchanged. */
    fun moveTab(tabId: String, toIndex: Int): WorkspaceState {
        val owner = workspaceOf(tabId) ?: return this
        val from = owner.tabs.indexOfFirst { it.id == tabId }
        val tabs = owner.tabs.toMutableList()
        val tab = tabs.removeAt(from)
        tabs.add(toIndex.coerceIn(0, tabs.size), tab)
        return mapWorkspace(owner.id) { it.copy(tabs = tabs) }
    }

    fun setHiddenTerminals(workspaceId: String, hiddenTerminalIds: Set<String>): WorkspaceState =
        mapWorkspace(workspaceId) { workspace ->
            workspace.copy(herdr = workspace.herdr?.copy(hiddenTerminalIds = hiddenTerminalIds))
        }

    /** Removes the Herdr binding only. Local tabs stay, so user data is never deleted by unbinding. */
    fun unbindHerdr(workspaceId: String): WorkspaceState =
        mapWorkspace(workspaceId) { it.copy(herdr = null) }

    fun browser(key: SftpBrowserKey): SftpBrowserState? = when (key) {
        is SftpBrowserKey.SftpTab -> sftpTab(key)?.browser
        is SftpBrowserKey.HerdrWorkspace -> herdrBrowsers.firstOrNull { it.scope == key.scope }?.browser
    }

    /** Creates a Herdr browser at [initialDir] when missing. An ordinary tab's browser exists with its tab. */
    fun openBrowser(key: SftpBrowserKey, initialDir: String): WorkspaceState = when {
        browser(key) != null || key !is SftpBrowserKey.HerdrWorkspace -> this
        else -> copy(herdrBrowsers = herdrBrowsers + HerdrSftpBrowser(key.scope, SftpBrowserState(initialDir)))
    }

    fun updateBrowser(key: SftpBrowserKey, transform: (SftpBrowserState) -> SftpBrowserState): WorkspaceState =
        when (key) {
            is SftpBrowserKey.SftpTab -> if (sftpTab(key) == null) {
                this
            } else {
                updateTab(key.tabId) { tab -> if (tab is Tab.Sftp) tab.withBrowser(transform(tab.browser)) else tab }
            }
            is SftpBrowserKey.HerdrWorkspace -> copy(
                herdrBrowsers = herdrBrowsers.map {
                    if (it.scope == key.scope) it.copy(browser = transform(it.browser)) else it
                },
            )
        }

    private fun sftpTab(key: SftpBrowserKey.SftpTab): Tab.Sftp? =
        workspaceOf(key.tabId)?.takeIf { it.serverId == key.serverId }?.tabs?.firstOrNull { it.id == key.tabId }
            as? Tab.Sftp

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
    /** Set when this workspace shows a Herdr workspace. Its remote tabs are projected, never stored in [tabs]. */
    val herdr: HerdrWorkspaceBinding? = null,
)

/** Local record of which Herdr workspace on the server this workspace shows. Remote topology is not stored here. */
@Serializable
data class HerdrWorkspaceBinding(
    val workspaceId: String,
    /** Terminal ids of hidden cards. A local presentation preference; hiding never touches the remote terminal. */
    val hiddenTerminalIds: Set<String> = emptySet(),
)

/** A remote terminal a tab attaches to. Set only on projected cards, which are in-memory and never saved. */
@Serializable
data class HerdrTerminalRef(val terminalId: String)

/**
 * Identity of one remote Herdr workspace's file collection: the server, the control socket it was read from, and the
 * Herdr workspace id. Captured when a sheet or viewer opens, so a result that arrives later stays in its source.
 */
@Serializable
data class HerdrFileScope(val serverId: String, val socketPath: String, val herdrWorkspaceId: String)

@Serializable
sealed interface Tab {
    val id: String

    @Serializable @SerialName("terminal")
    data class Terminal(
        override val id: String,
        val name: String? = null,
        val cwd: String,
        val herdrTerminal: HerdrTerminalRef? = null,
    ) : Tab

    /**
     * An SFTP tab's [SftpBrowserState], stored flat. [scrollIndex] and [scrollOffset] are the position in [dir];
     * [scrolls] holds the other folders. Read and write it through [browser] and [withBrowser].
     */
    @Serializable @SerialName("sftp")
    data class Sftp(
        override val id: String,
        val dir: String,
        val back: List<String> = emptyList(),
        val expanded: List<String> = emptyList(),
        val collapsed: List<String> = emptyList(),
        val scrollIndex: Int = 0,
        val scrollOffset: Int = 0,
        val scrolls: Map<String, SftpScroll> = emptyMap(),
        val treeMode: Boolean = false,
    ) : Tab {
        val browser: SftpBrowserState
            get() = SftpBrowserState(
                dir = dir,
                // Saves from before navigation became immediate can end back in dir. Keep that folder once.
                back = if (back.lastOrNull() == dir) back.dropLast(1) else back,
                treeMode = treeMode,
                expanded = expanded,
                collapsed = collapsed,
                scroll = scrolls + (dir to SftpScroll(scrollIndex, scrollOffset)),
            )

        fun withBrowser(browser: SftpBrowserState): Sftp {
            val current = browser.scrollOf(browser.dir)
            return copy(
                dir = browser.dir,
                back = browser.back,
                expanded = browser.expanded,
                collapsed = browser.collapsed,
                scrollIndex = current.index,
                scrollOffset = current.offset,
                scrolls = browser.scroll - browser.dir,
                treeMode = browser.treeMode,
            )
        }
    }

    @Serializable @SerialName("git")
    data class Git(override val id: String, val repoDir: String) : Tab

    /** [scope] is null for an ordinary preview. A Herdr preview keeps the remote workspace it was opened from. */
    @Serializable @SerialName("viewer")
    data class Viewer(override val id: String, val path: String, val scope: HerdrFileScope? = null) : Tab
}

fun newId(): String = UUID.randomUUID().toString()
