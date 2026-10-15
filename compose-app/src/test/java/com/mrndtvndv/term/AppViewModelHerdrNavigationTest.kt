package com.mrndtvndv.term

import androidx.lifecycle.ViewModelStore
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.herdr.FakeHerdrServer
import com.mrndtvndv.term.herdr.FakeHerdrSession
import com.mrndtvndv.term.herdr.FakePane
import com.mrndtvndv.term.herdr.HerdrActions
import com.mrndtvndv.term.herdr.HerdrControlClient
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.FakeSharedPreferences
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerCoordinatorAccess
import com.mrndtvndv.term.server.ServerRepository
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.workspace.HerdrFileScope
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.WorkspaceFile
import com.mrndtvndv.term.workspace.WorkspaceRepository
import com.mrndtvndv.term.workspace.herdrFileScopeKey
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelHerdrNavigationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun laterViewerSelectionSurvivesActivityReplacementAndEarlierFocusCompletion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val remote = FakeHerdrServer().also { it.panes += FakePane("p-1") }
        val client = HerdrControlClient(FakeHerdrSession(remote), "/socket", backgroundScope)
        val actions = HerdrActions(client, backgroundScope)
        val store = ViewModelStore()
        try {
            client.start()
            val repository = WorkspaceRepository(WorkspaceFile(temporaryFolder.newFile()), backgroundScope)
            val manager = NavigationSessionManager(client, actions)
            val model = AppViewModel(manager, NotificationState(), repository, logEvent = {})
            store.put("app", model)
            val workspace = repository.createHerdrSessionWorkspace("server", "Herdr", "/project")
            model.navigator.openWorkspace(workspace.id)
            val viewer = Tab.Viewer("viewer", "/project/file")
            repository.openTab(workspace.id, viewer)
            val gate = CompletableDeferred<Unit>()
            remote.gateNextSnapshot(gate)
            model.focusHerdrTerminal("server", "t-p-1")
            assertEquals("herdr-full:server", repository.state.value.activeWorkspace?.activeTabId)
            advanceTimeBy(251)
            runCurrent()
            assertEquals(2, remote.requestCount("session.snapshot"))

            model.activateTab(viewer.id)
            store.clear()
            val replacement = AppViewModel(manager, NotificationState(), repository, logEvent = {})
            store.put("replacement", replacement)
            gate.complete(Unit)
            advanceTimeBy(1_000)
            runCurrent()

            assertEquals("p-1", remote.focusedPaneId)
            assertEquals(viewer.id, repository.state.value.activeWorkspace?.activeTabId)

            val captured = herdrFileScopeKey(HerdrFileScope("server", "/socket", "other-workspace"))
            val newViewer = replacement.openHerdrViewer("server", captured, "/other/file")
            assertNotNull(newViewer)
            assertTrue(repository.state.value.tab(checkNotNull(newViewer)) is Tab.Viewer)
            assertEquals(viewer.id, repository.state.value.activeWorkspace?.activeTabId)
        } finally {
            store.clear()
            actions.close()
            client.close()
            Dispatchers.resetMain()
        }
    }
}

private class NavigationSessionManager(client: HerdrControlClient, private val actions: HerdrActions) :
    AppSessionManagerAccess {
    override val connectionStores = ConnectionViewModelStores()
    override val serverRepository = ServerRepository(FakeSharedPreferences())
    override val sessionFinished = MutableSharedFlow<String>()
    private val server = Server(
        ServerConfig("server", "Herdr", herdrEnabled = true), homeDir = "/project", herdr = client,
    )
    init {
        serverRepository.add(server.config).getOrThrow()
    }
    override val coordinator = object : ServerCoordinatorAccess {
        override val activeIds = setOf("server")
        override fun getServer(id: String): Server? = server.takeIf { id == "server" }
        override fun disconnect(id: String) = Unit
    }

    override fun herdrActions(serverId: String): HerdrActions? = actions.takeIf { serverId == "server" }
    override fun disconnect(id: String) = Unit
    override suspend fun connect(id: String): Result<Server> = Result.success(server)
    override fun observeTerminalProgress(session: TerminalSession): Nothing = error("No terminal")
    override fun observeProgramStatus(session: TerminalSession): Nothing = error("No terminal")
    override fun markProgramStatusSeen(session: TerminalSession) = Unit
    override suspend fun openTerminal(serverId: String, tab: Tab.Terminal): Result<TerminalSession> =
        error("No terminal")
    override fun closeTerminal(serverId: String, tab: Tab.Terminal) = Unit
}
