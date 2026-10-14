package com.mrndtvndv.term.server

import com.termux.terminal.compose.session.ProgramStatusState
import com.mrndtvndv.term.domain.ServerConfig
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
    fun loadAll(): List<ServerConfig>

    fun add(config: ServerConfig)

    fun update(config: ServerConfig)

    fun remove(id: String)

    fun get(id: String): ServerConfig?
}

interface AppSessionManagerAccess {
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
     * Closes the tab's terminal if any. [terminate] = the user closed the tab: also ends the
     * persistent remote session (TerminalPersistence.terminate). Main thread.
     */
    fun closeTerminal(serverId: String, tab: Tab.Terminal, terminate: Boolean)

    fun locateSession(session: TerminalSession): SessionLocation? = null
}
