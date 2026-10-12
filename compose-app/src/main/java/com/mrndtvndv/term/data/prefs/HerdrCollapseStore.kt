package com.mrndtvndv.term.data.prefs

import android.content.SharedPreferences

/**
 * Persists which herdr workspaces are collapsed in the agents sheet, per server.
 * Only collapsed IDs are stored: a workspace missing from the set is expanded.
 */
class HerdrCollapseStore(
    private val prefs: SharedPreferences,
) {
    fun load(serverId: String): Set<String> =
        prefs.getStringSet(key(serverId), null).orEmpty()

    fun save(serverId: String, collapsedWorkspaceIds: Set<String>) {
        prefs.edit()
            .putStringSet(key(serverId), collapsedWorkspaceIds.toSet())
            .apply()
    }

    private fun key(serverId: String) = "herdr_collapsed_$serverId"
}
