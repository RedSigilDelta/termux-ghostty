package com.mrndtvndv.term.server

import com.mrndtvndv.term.ConnectionViewModelStores
import com.termux.terminal.compose.session.ProgramStatusState
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.herdr.HerdrActions
import com.mrndtvndv.term.workspace.Tab
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class SessionLocation(val serverId: String, val tabId: String)

interface ServerCoordinatorAccess {
    val activeIds: Set<String>

    fun getServer(id: String): Server?

    fun disconnect(id: String)
}

interface ServerRepositoryAccess {
    /** Every saved server, or [SavedServersLoad.Unreadable] when the stored list cannot be read in full. */
    fun load(): SavedServersLoad

    /** The saved server with [id]. Null when absent or when the stored list is unreadable; see [load] for why. */
    fun get(id: String): ServerConfig?

    /**
     * Refused, with storage untouched, while the stored list is unreadable. Success means the change was accepted
     * and queued for disk, not that it is durable.
     */
    fun add(config: ServerConfig): Result<Unit>

    fun update(config: ServerConfig): Result<Unit>

    fun remove(id: String): Result<Unit>
}

interface AppSessionManagerAccess {
    val connectionStores: ConnectionViewModelStores
    val coordinator: ServerCoordinatorAccess
    val serverRepository: ServerRepositoryAccess
    val sessionFinished: Flow<String>

    suspend fun connect(id: String): Result<Server>

    fun disconnect(id: String)

    fun observeTerminalProgress(session: TerminalSession): StateFlow<TerminalProgress?>

    fun observeProgramStatus(session: TerminalSession): StateFlow<ProgramStatusState>

    fun markProgramStatusSeen(session: TerminalSession)

    fun serverIdForSession(session: TerminalSession): String? = null

    /**
     * Returns the tab's running terminal, or opens one (a new SSH channel / local process).
     * A finished terminal for the tab is replaced. Concurrent calls for the same tab share one open.
     * Main thread.
     */
    suspend fun openTerminal(serverId: String, tab: Tab.Terminal): Result<TerminalSession>

    /**
     * Closes the tab's local attachment, if any. Never ends a remote terminal. Main thread.
     */
    fun closeTerminal(serverId: String, tab: Tab.Terminal)

    fun locateSession(session: TerminalSession): SessionLocation? = null

    /**
     * Remote Herdr actions of the server's current control client, owned for the life of that connection rather than
     * any activity. Null when the server is not connected or runs no Herdr control client.
     */
    fun herdrActions(serverId: String): HerdrActions? = null
}
