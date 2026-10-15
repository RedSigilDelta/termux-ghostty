package com.mrndtvndv.term.ui.notification

import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.Workspace
import com.mrndtvndv.term.workspace.WorkspaceState
import com.mrndtvndv.term.workspace.herdrFullUiTabId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationDestinationTest {
    private val plain = ServerConfig(id = SERVER, label = "box")
    private val herdr = plain.copy(herdrEnabled = true)

    @Test
    fun `a notification from the Herdr session terminal names the session, never a pane`() {
        // Whatever pane Herdr reports as focused, the session terminal is the only verified source.
        val destination = notificationDestination(SERVER, herdrFullUiTabId(SERVER), SOCKET)

        assertEquals(NotificationDestination.HerdrSession(SERVER, SOCKET), destination)
    }

    @Test
    fun `a notification from an ordinary terminal names its app tab`() {
        assertEquals(
            NotificationDestination.AppTab(SERVER, "tab-a"),
            notificationDestination(SERVER, "tab-a", SOCKET),
        )
    }

    @Test
    fun `destinations survive the intent extras round trip`() {
        listOf(
            NotificationDestination.AppTab(SERVER, "tab-a"),
            NotificationDestination.HerdrSession(SERVER, SOCKET),
        ).forEach { destination ->
            val extras = NotificationDestinationCodec.encode(destination)

            assertEquals(destination, NotificationDestinationCodec.decode(extras::get))
        }
    }

    @Test
    fun `incomplete extras decode to no destination`() {
        assertNull(NotificationDestinationCodec.decode(emptyMap<String, String>()::get))
        val noTab = mapOf(
            NotificationDestinationCodec.KIND to "app_tab",
            NotificationDestinationCodec.SERVER_ID to SERVER,
        )
        assertNull(NotificationDestinationCodec.decode(noTab::get))
    }

    @Test
    fun `a Herdr destination opens the session terminal on the same control socket`() {
        val destination = NotificationDestination.HerdrSession(SERVER, SOCKET)

        assertEquals(NotificationRoute.HerdrSession, notificationRoute(destination, herdr, SOCKET, workspaces()))
    }

    @Test
    fun `a Herdr destination from another socket or a server without Herdr opens only the server`() {
        val destination = NotificationDestination.HerdrSession(SERVER, SOCKET)

        assertEquals(NotificationRoute.Server, notificationRoute(destination, herdr, "/other.sock", workspaces()))
        assertEquals(NotificationRoute.Server, notificationRoute(destination, plain, SOCKET, workspaces()))
    }

    @Test
    fun `an app tab destination opens its workspace on that tab`() {
        val destination = NotificationDestination.AppTab(SERVER, "tab-a")

        assertEquals(
            NotificationRoute.WorkspaceTab("w-1", "tab-a"),
            notificationRoute(destination, plain, SOCKET, workspaces()),
        )
    }

    @Test
    fun `an app tab that is gone, foreign, or on a Herdr server opens only the server`() {
        assertEquals(
            NotificationRoute.Server,
            notificationRoute(NotificationDestination.AppTab(SERVER, "tab-gone"), plain, SOCKET, workspaces()),
        )
        assertEquals(
            NotificationRoute.Server,
            notificationRoute(NotificationDestination.AppTab(SERVER, "tab-other"), plain, SOCKET, workspaces()),
        )
        assertEquals(
            NotificationRoute.Server,
            notificationRoute(NotificationDestination.AppTab(SERVER, "tab-a"), herdr, SOCKET, workspaces()),
        )
    }

    private fun workspaces(): WorkspaceState = WorkspaceState()
        .addWorkspace(Workspace(id = "w-1", serverId = SERVER, name = "box", root = "/home/u"))
        .addTab("w-1", Tab.Terminal(id = "tab-a", cwd = "/home/u"), activate = true)
        .addWorkspace(Workspace(id = "w-2", serverId = "server-2", name = "other", root = "/"))
        .addTab("w-2", Tab.Terminal(id = "tab-other", cwd = "/"), activate = true)

    private companion object {
        const val SERVER = "server-1"
        const val SOCKET = "/home/u/.config/herdr/herdr.sock"
    }
}
