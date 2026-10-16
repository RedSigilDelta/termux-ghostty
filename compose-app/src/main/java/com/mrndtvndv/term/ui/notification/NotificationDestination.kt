package com.mrndtvndv.term.ui.notification

import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.workspace.WorkspaceState
import com.mrndtvndv.term.workspace.herdrFullUiTabId
import com.mrndtvndv.term.workspace.isHerdrSessionWorkspace

/**
 * Where tapping a terminal notification leads. OSC identifies only the emitting session; semantic endpoint events
 * can additionally identify the originating pane. Neither derives a pane from notification text or current focus.
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

    /** Stable terminal identity captured when the endpoint reported a semantic pane notification. */
    data class HerdrPane(
        override val serverId: String,
        val socketPath: String,
        val terminalId: String,
    ) : NotificationDestination
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
    const val TERMINAL_ID = "termux.terminal.notification.terminal_id"
    val KEYS = listOf(KIND, SERVER_ID, TAB_ID, HERDR_SOCKET, TERMINAL_ID)

    private const val APP_TAB = "app_tab"
    private const val HERDR_SESSION = "herdr_session"
    private const val HERDR_PANE = "herdr_pane"

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
        is NotificationDestination.HerdrPane -> mapOf(
            KIND to HERDR_PANE,
            SERVER_ID to destination.serverId,
            HERDR_SOCKET to destination.socketPath,
            TERMINAL_ID to destination.terminalId,
        )
    }

    /** Null when the extras do not hold a complete destination. */
    fun decode(extra: (String) -> String?): NotificationDestination? {
        val serverId = extra(SERVER_ID) ?: return null
        return when (extra(KIND)) {
            APP_TAB -> extra(TAB_ID)?.let { NotificationDestination.AppTab(serverId, it) }
            HERDR_SESSION -> extra(HERDR_SOCKET)?.let { NotificationDestination.HerdrSession(serverId, it) }
            HERDR_PANE -> extra(HERDR_SOCKET)?.let { socket ->
                extra(TERMINAL_ID)?.let { NotificationDestination.HerdrPane(serverId, socket, it) }
            }
            else -> null
        }
    }
}

/** What a tapped notification opens once its server is connected. */
sealed interface NotificationRoute {
    data class WorkspaceTab(val workspaceId: String, val tabId: String) : NotificationRoute

    /** The server's Herdr session on its full-UI terminal. No remote pane is focused: the origin is unknown. */
    data object HerdrSession : NotificationRoute

    data class HerdrPane(val terminalId: String) : NotificationRoute

    /** The server's usual workspace, because the destination no longer exists as captured. */
    data object Server : NotificationRoute
}

/**
 * Resolves [destination] against the connected server. Herdr destinations require the same control socket; a pane
 * target is additionally resolved by stable terminal ID on tap. An app tab must still belong to the plain server.
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
    is NotificationDestination.HerdrPane ->
        if (config.herdrEnabled && destination.socketPath == herdrSocketPath) {
            NotificationRoute.HerdrPane(destination.terminalId)
        } else {
            NotificationRoute.Server
        }
    is NotificationDestination.AppTab -> workspaces.workspaceOf(destination.tabId)
        ?.takeIf { it.serverId == destination.serverId && !config.herdrEnabled && !isHerdrSessionWorkspace(it) }
        ?.let { NotificationRoute.WorkspaceTab(it.id, destination.tabId) }
        ?: NotificationRoute.Server
}
