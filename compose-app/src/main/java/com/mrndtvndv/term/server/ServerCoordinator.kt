package com.mrndtvndv.term.server

import kotlinx.coroutines.Job

/**
 * Coordinates connection lifecycle.
 */
class ServerCoordinator(
    private val serverManager: ServerManager,
    private val serverRepository: ServerRepository,
) : ServerCoordinatorAccess {
    // Track in-flight connections to allow cancellation on disconnectAll
    private val connectJobs = mutableMapOf<String, Job>()

    /**
     * Connect to a server. Returns the Server on success, or failure result.
     * If already connected, returns the existing Server.
     */
    @Suppress("TooGenericExceptionCaught") // catches SSH/IO errors into Result; specific types are impractical here
    suspend fun connect(id: String): Result<Server> {
        val config = serverRepository.get(id)
            ?: return Result.failure(IllegalArgumentException("Unknown server: $id"))

        return try {
            Result.success(serverManager.connect(config))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getServer(id: String): Server? = serverManager.get(id)

    override val activeIds: Set<String> get() = serverManager.activeIds

    override fun disconnect(id: String) {
        connectJobs[id]?.cancel()
        connectJobs.remove(id)
        serverManager.disconnect(id)
    }

    fun disconnectAll() {
        connectJobs.values.forEach { it.cancel() }
        connectJobs.clear()
        serverManager.disconnectAll()
    }
}
