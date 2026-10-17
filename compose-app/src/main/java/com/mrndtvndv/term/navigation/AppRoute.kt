package com.mrndtvndv.term.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
enum class KeyBarItemStart {
    Combo,
    Snippet,
}

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object ServerList : AppRoute

    @Serializable
    data object AddServer : AppRoute

    @Serializable
    data class EditServer(val serverId: String) : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object KeyBarEditor : AppRoute

    /** Editor for one key bar item. A null [itemId] adds a new item. */
    @Serializable
    data class KeyBarItemEditor(
        val itemId: String? = null,
        val start: KeyBarItemStart? = null,
    ) : AppRoute

    @Serializable
    data class Workspace(val workspaceId: String) : AppRoute

    /** A server's Herdr session: its one full-UI terminal and file tabs. Its workspace is looked up from [serverId]. */
    @Serializable
    data class HerdrSession(val serverId: String) : AppRoute
}
