package com.mrndtvndv.term.ui.notification

import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.workspace.WorkspaceState
import com.mrndtvndv.term.workspace.herdrFullUiTabId
import com.mrndtvndv.term.workspace.isHerdrSessionWorkspace

/**
 * Where tapping a terminal notification leads. Built only from the terminal session that emitted the notification,
 * never from focus or from the notification text.
 */
sealed interface NotificationDestination {
    val serverId: String

    /** The app tab whose terminal emitted the notification. */
    data class AppTab(override val serverId: String, val tabId: String) : NotificationDestination

    /**
     * The server's Herdr session terminal on control socket [socketPath]. Herdr's attached client re-emits its
     * notifications as plain OSC text without the originating pane, so the origin pane is unknown and never named.
     */
    data class HerdrSession(override val serverId: String, val socketPath: String) : NotificationDestination
}

/** The destination of a notification from the terminal of [tabId]. [herdrSocketPath] is the server's control socket. */
fun notificationDestination(serverId: String, tabId: String, herdrSocketPath: String): NotificationDestination =
    if (tabId == herdrFullUiTabId(serverId)) {
        NotificationDestination.HerdrSession(serverId, herdrSocketPath)
    } else {
        NotificationDestination.AppTab(serverId, tabId)
    }

/** Flat string form for intent extras. In-app and system notifications carry the same destination. */
object NotificationDestinationCodec {
    const val KIND = "termux.terminal.notification.kind"
    const val SERVER_ID = "termux.terminal.notification.server_id"
    const val TAB_ID = "termux.terminal.notification.tab_id"
    const val HERDR_SOCKET = "termux.terminal.notification.herdr_socket"
    val KEYS = listOf(KIND, SERVER_ID, TAB_ID, HERDR_SOCKET)

    private const val APP_TAB = "app_tab"
    private const val HERDR_SESSION = "herdr_session"

    fun encode(destination: NotificationDestination): Map<String, String> = when (destination) {
        is NotificationDestination.AppTab -> mapOf(
            KIND to APP_TAB,
            SERVER_ID to destination.serverId,
            TAB_ID to destination.tabId,
        )
        is NotificationDestination.HerdrSession -> mapOf(
            KIND to HERDR_SESSION,
            SERVER_ID to destination.serverId,
            HERDR_SOCKET to destination.socketPath,
        )
    }

    /** Null when the extras do not hold a complete destination. */
    fun decode(extra: (String) -> String?): NotificationDestination? {
        val serverId = extra(SERVER_ID) ?: return null
        return when (extra(KIND)) {
            APP_TAB -> extra(TAB_ID)?.let { NotificationDestination.AppTab(serverId, it) }
            HERDR_SESSION -> extra(HERDR_SOCKET)?.let { NotificationDestination.HerdrSession(serverId, it) }
            else -> null
        }
    }
}

/** What a tapped notification opens once its server is connected. */
sealed interface NotificationRoute {
    data class WorkspaceTab(val workspaceId: String, val tabId: String) : NotificationRoute

    /** The server's Herdr session on its full-UI terminal. No remote pane is focused: the origin is unknown. */
    data object HerdrSession : NotificationRoute

    /** The server's usual workspace, because the destination no longer exists as captured. */
    data object Server : NotificationRoute
}

/**
 * Resolves [destination] against the connected server. A Herdr destination opens the session only while the server
 * still runs Herdr on the same control socket. An app tab opens only while it still belongs to a plain workspace of
 * the server. Anything else opens the server, never another tab or pane.
 */
fun notificationRoute(
    destination: NotificationDestination,
    config: ServerConfig,
    herdrSocketPath: String,
    workspaces: WorkspaceState,
): NotificationRoute = when (destination) {
    is NotificationDestination.HerdrSession ->
        if (config.herdrEnabled && destination.socketPath == herdrSocketPath) {
            NotificationRoute.HerdrSession
        } else {
            NotificationRoute.Server
        }
    is NotificationDestination.AppTab -> workspaces.workspaceOf(destination.tabId)
        ?.takeIf { it.serverId == destination.serverId && !config.herdrEnabled && !isHerdrSessionWorkspace(it) }
        ?.let { NotificationRoute.WorkspaceTab(it.id, destination.tabId) }
        ?: NotificationRoute.Server
}
