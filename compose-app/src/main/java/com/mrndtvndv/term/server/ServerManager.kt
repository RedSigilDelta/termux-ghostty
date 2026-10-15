package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred

/**
 * Owns all active (connected) Server instances.
 * One per saved server config. Max concurrent limit enforced.
 */
class ServerManager(
    private val create: suspend (ServerConfig) -> Server,
    private val maxConcurrent: Int = 5,
) {
    private val activeServers = linkedMapOf<String, Server>() // insertion order

    // Connects still in flight, so a second connect for the same server waits instead of dialing again.
    private val connecting = mutableMapOf<String, CompletableDeferred<Result<Server>>>()

    val activeIds: Set<String> get() = activeServers.keys

    fun get(id: String): Server? = activeServers[id]

    fun isConnected(id: String): Boolean = activeServers.containsKey(id)

    /** Find the server and tab whose terminal matches [session]. */
    fun locate(session: TerminalSession): SessionLocation? =
        activeServers.entries.firstNotNullOfOrNull { (serverId, server) ->
            server.tabIdFor(session)?.let { SessionLocation(serverId, it) }
        }

    /**
     * Connect to a server. If already connected, returns existing instance.
     * Concurrent calls for the same server share one connection.
     * Throws if maxConcurrent would be exceeded and the server isn't already connected.
     */
    suspend fun connect(config: ServerConfig): Server {
        activeServers[config.id]?.let { return it }
        connecting[config.id]?.let { return it.await().getOrThrow() }
        check(activeServers.size + connecting.size < maxConcurrent) {
            "Max $maxConcurrent concurrent connections reached. Disconnect one first."
        }
        val pending = CompletableDeferred<Result<Server>>()
        connecting[config.id] = pending
        val result = runCatching { create(config) }
        result.onSuccess { activeServers[config.id] = it }
        connecting.remove(config.id)
        pending.complete(result)
        return result.getOrThrow()
    }

    fun disconnect(id: String) {
        activeServers.remove(id)?.disconnect()
    }

    fun disconnectAll() {
        activeServers.values.forEach { it.disconnect() }
        activeServers.clear()
    }
}
