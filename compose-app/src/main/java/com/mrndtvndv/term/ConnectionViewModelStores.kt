package com.mrndtvndv.term

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * ViewModel stores that live as long as a server connection, so workspace
 * state survives leaving and re-entering the workspace while connected.
 * Keyed by server, or by server and tab for per-tab state.
 */
class ConnectionViewModelStores {
    private val owners = mutableMapOf<String, ViewModelStoreOwner>()

    fun owner(serverId: String, tabId: String? = null): ViewModelStoreOwner =
        owners.getOrPut(key(serverId, tabId)) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }

    /** Clears the server's store and every tab store under it. */
    fun clear(serverId: String) {
        owners.keys
            .filter { it == serverId || it.startsWith("$serverId/") }
            .forEach { remove(it) }
    }

    fun clearTab(serverId: String, tabId: String) {
        remove(key(serverId, tabId))
    }

    fun clearAll() {
        owners.keys.toList().forEach { remove(it) }
    }

    private fun remove(key: String) {
        owners.remove(key)?.viewModelStore?.clear()
    }

    private fun key(serverId: String, tabId: String?) = if (tabId == null) serverId else "$serverId/$tabId"
}
