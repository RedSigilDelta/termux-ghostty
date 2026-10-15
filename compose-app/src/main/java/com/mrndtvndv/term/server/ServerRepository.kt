package com.mrndtvndv.term.server

import android.content.SharedPreferences
import com.mrndtvndv.term.domain.ServerConfig

/** What the saved server list holds right now. [Unreadable] means the stored payload is left as it is. */
sealed interface SavedServersLoad {
    data class Loaded(val servers: List<ServerConfig>) : SavedServersLoad

    data class Unreadable(val message: String) : SavedServersLoad
}

/**
 * Saved servers in SharedPreferences. Reads are all-or-nothing and writes are gated on a complete read, so a
 * stored list this version cannot fully read is never partly shown as complete and never rewritten.
 */
class ServerRepository(
    private val prefs: SharedPreferences,
) : ServerRepositoryAccess {

    companion object {
        internal const val KEY_SAVED_SERVERS = "saved_servers_v2"
    }

    override fun load(): SavedServersLoad {
        // Only a missing key means nothing was saved. Any stored text, blank included, must decode in full.
        val raw = prefs.getString(KEY_SAVED_SERVERS, null) ?: return SavedServersLoad.Loaded(emptyList())
        return try {
            SavedServersLoad.Loaded(SavedServerCodec.decode(raw))
        } catch (e: SavedServerCodec.UnreadableException) {
            SavedServersLoad.Unreadable(e.message.orEmpty())
        }
    }

    override fun get(id: String): ServerConfig? =
        (load() as? SavedServersLoad.Loaded)?.servers?.find { it.id == id }

    override fun add(config: ServerConfig): Result<Unit> = write { servers -> servers + config }

    override fun update(config: ServerConfig): Result<Unit> = write { servers ->
        servers.map { if (it.id == config.id) config else it }
    }

    override fun remove(id: String): Result<Unit> = write { servers -> servers.filterNot { it.id == id } }

    /**
     * Applies [change] to the complete list. Refused, without opening an editor, when the stored list is unreadable.
     * Accepted writes use apply(), so the disk write happens in the background and never blocks the main thread.
     * Success means accepted, not durable: a disk write that fails later is not reported back to the caller.
     */
    private fun write(change: (List<ServerConfig>) -> List<ServerConfig>): Result<Unit> {
        val servers = when (val loaded = load()) {
            is SavedServersLoad.Loaded -> loaded.servers
            is SavedServersLoad.Unreadable ->
                return Result.failure(IllegalStateException("${loaded.message} Nothing was changed."))
        }
        prefs.edit().putString(KEY_SAVED_SERVERS, SavedServerCodec.encode(change(servers))).apply()
        return Result.success(Unit)
    }
}
