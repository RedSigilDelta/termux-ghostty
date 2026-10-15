package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.herdr.HerdrControlClient
import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshShellChannel
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A tab's terminal. [running] turns false when its shell or process exits. */
data class TerminalEntry(val session: TerminalSession, val running: Boolean)

/**
 * Aggregate root for a single connection (SSH or local shell).
 * SSH-specific fields are nullable and omitted for local sessions.
 * All resources are cleaned up by calling [disconnect].
 */
class Server(
    val config: ServerConfig,
    val sshSession: SshSession? = null,
    val sftpClient: SftpClient? = null,
    /** Absolute home dir on the server (local: the app's HOME). Default root for new workspaces. */
    val homeDir: String,
    /** Herdr control client for this connection. Closed before the SSH session on disconnect. */
    val herdr: HerdrControlClient? = null,
) {
    private val _terminals = MutableStateFlow<Map<String, TerminalEntry>>(emptyMap())

    /** Terminal per tab id. Mutated and read on the main thread only. */
    val terminals: StateFlow<Map<String, TerminalEntry>> = _terminals

    private val channels = mutableMapOf<String, SshShellChannel?>()
    private val disconnectActions = mutableListOf<() -> Unit>()
    private var disconnected = false

    fun tabIdFor(session: TerminalSession): String? =
        _terminals.value.entries.firstOrNull { it.value.session === session }?.key

    /** False once the SSH connection died. Always true for local. */
    val isAlive: Boolean
        get() = sshSession?.isAlive ?: true

    internal fun putTerminal(tabId: String, session: TerminalSession, channel: SshShellChannel?) {
        channels[tabId] = channel
        _terminals.value = _terminals.value + (tabId to TerminalEntry(session, running = true))
    }

    /** Marks the tab's terminal finished, unless it has already been replaced by [session]. */
    internal fun markFinished(tabId: String, session: TerminalSession) {
        val entry = _terminals.value[tabId] ?: return
        if (entry.session !== session) return
        _terminals.value = _terminals.value + (tabId to entry.copy(running = false))
    }

    internal fun removeTerminal(tabId: String): Pair<TerminalSession, SshShellChannel?>? {
        val entry = _terminals.value[tabId] ?: return null
        _terminals.value = _terminals.value - tabId
        return entry.session to channels.remove(tabId)
    }

    /**
     * Runs [action] once when this connection disconnects, before the SSH session is torn down, so it can
     * release channels that ride that session. Runs immediately if the connection is already gone.
     */
    fun onDisconnect(action: () -> Unit) {
        if (disconnected) {
            runDisconnectAction(action)
        } else {
            disconnectActions += action
        }
    }

    /**
     * Clean up all native resources for this connection, including every terminal.
     * Safe to call multiple times.
     */
    fun disconnect() {
        disconnected = true
        herdr?.close()
        val actions = disconnectActions.toList()
        disconnectActions.clear()
        actions.forEach { runDisconnectAction(it) }
        val entries = _terminals.value
        _terminals.value = emptyMap()
        entries.forEach { (tabId, entry) -> closeTerminalSession(entry.session, channels[tabId]) }
        channels.clear()
        try { sftpClient?.close() } catch (_: Exception) { }
        try { sshSession?.disconnect() } catch (_: Exception) { }
    }

    private fun runDisconnectAction(action: () -> Unit) {
        try { action() } catch (_: Exception) { }
    }
}

/**
 * Order matters: the session must stop using the shell before [SshShellChannel.close]
 * frees the native shell handle.
 */
internal fun closeTerminalSession(session: TerminalSession, channel: SshShellChannel?) {
    try { session.finishIfRunning() } catch (_: Exception) { }
    try { session.close() } catch (_: Exception) { }
    try { channel?.close() } catch (_: Exception) { }
}
