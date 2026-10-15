package com.mrndtvndv.term

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.FakeSharedPreferences
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerCoordinatorAccess
import com.mrndtvndv.term.server.ServerRepository
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.ui.sftp.FakeSftpClient
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.workspace.HerdrFileScope
import com.mrndtvndv.term.workspace.SftpBrowserKey
import com.mrndtvndv.term.workspace.SftpScroll
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.WorkspaceFile
import com.mrndtvndv.term.workspace.WorkspaceRepository
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelConnectionStateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun browserControllerFollowsConnectionAndNavigationOutlivesIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val manager = FakeSessionManager()
        val repository = WorkspaceRepository(
            WorkspaceFile(temporaryFolder.newFile("workspaces.json")),
            backgroundScope,
        )
        val activities = mutableListOf<ViewModelStoreOwner>()

        fun newActivity(): AppViewModel {
            val owner = object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
            activities += owner
            return ViewModelProvider.create(
                owner,
                viewModelFactory { initializer { AppViewModel(manager, NotificationState(), repository) } },
            )[AppViewModel::class]
        }

        val key = SftpBrowserKey.HerdrWorkspace(HerdrFileScope("server", "/tmp/herdr.sock", "workspace"))
        val folders = mapOf("/project" to emptyList(), "/project/build" to emptyList<SftpFile>())

        fun browser(activity: AppViewModel, initialDir: String): SftpViewModel = ViewModelProvider.create(
            activity.connectionStore("server", "sftp:workspace"),
            viewModelFactory {
                initializer {
                    SftpViewModel(FakeSftpClient(folders), { "" }, null, "server", repository, key, initialDir)
                }
            },
        )[SftpViewModel::class]

        try {
            val originalActivity = newActivity()
            val originalBrowser = browser(originalActivity, "/project")
            originalBrowser.openFolder("/project/build")
            originalBrowser.saveScroll("/project/build", SftpScroll(7, 23))
            originalBrowser.setTreeMode(true)

            activities.first().viewModelStore.clear()
            val replacementActivity = newActivity()
            val resumedBrowser = browser(replacementActivity, "/elsewhere")

            assertSame(originalBrowser, resumedBrowser)

            manager.disconnect("server")
            val reconnectedBrowser = browser(replacementActivity, "/elsewhere")
            assertNotSame(originalBrowser, reconnectedBrowser)
            // Only the connection's listings went away. Navigation, scroll and tree mode are kept.
            val kept = checkNotNull(repository.browser(key))
            assertEquals("/project/build", kept.dir)
            assertEquals(listOf("/project"), kept.back)
            assertEquals(SftpScroll(7, 23), kept.scrollOf("/project/build"))
            assertEquals(true, kept.treeMode)
        } finally {
            activities.forEach { it.viewModelStore.clear() }
            manager.connectionStores.clearAll()
            Dispatchers.resetMain()
        }
    }
}

private class FakeSessionManager : AppSessionManagerAccess {
    override val connectionStores = ConnectionViewModelStores()
    override val serverRepository = ServerRepository(FakeSharedPreferences())
    override val sessionFinished = MutableSharedFlow<String>()
    override val coordinator = object : ServerCoordinatorAccess {
        override val activeIds = emptySet<String>()
        override fun getServer(id: String): Server? = null
        override fun disconnect(id: String) = Unit
    }

    override fun disconnect(id: String) = connectionStores.clear(id)
    override suspend fun connect(id: String): Result<Server> = error("Unexpected connection")
    override fun observeTerminalProgress(session: TerminalSession): Nothing = error("No terminal")
    override fun observeProgramStatus(session: TerminalSession): Nothing = error("No terminal")
    override fun markProgramStatusSeen(session: TerminalSession) = Unit
    override suspend fun openTerminal(serverId: String, tab: Tab.Terminal): Result<TerminalSession> =
        error("Unexpected terminal")
    override fun closeTerminal(serverId: String, tab: Tab.Terminal) = Unit
}
