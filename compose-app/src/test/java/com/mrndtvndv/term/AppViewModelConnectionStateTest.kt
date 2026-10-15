package com.mrndtvndv.term

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mrndtvndv.term.navigation.SftpFolder
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.FakeSharedPreferences
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerCoordinatorAccess
import com.mrndtvndv.term.server.ServerRepository
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.ui.workspace.HerdrSftpNavigation
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
    fun activityReplacementKeepsBrowserUntilConnectionEnds() = runTest {
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

        fun browser(activity: AppViewModel): HerdrSftpNavigation = ViewModelProvider.create(
            activity.connectionStore("server", "sftp:workspace"),
            viewModelFactory { initializer { HerdrSftpNavigation("/project") } },
        )[HerdrSftpNavigation::class]

        try {
            val originalActivity = newActivity()
            val originalBrowser = browser(originalActivity)
            originalBrowser.backStack.add(SftpFolder("/project/build/outputs"))
            originalBrowser.saveScrollPosition("/project/build/outputs", 7, 23)
            originalBrowser.treeMode = true

            activities.first().viewModelStore.clear()
            val replacementActivity = newActivity()
            val resumedBrowser = browser(replacementActivity)

            assertSame(originalBrowser, resumedBrowser)
            assertEquals(SftpFolder("/project/build/outputs"), resumedBrowser.backStack.last())
            assertEquals(7 to 23, resumedBrowser.scrollPosition("/project/build/outputs"))
            assertEquals(true, resumedBrowser.treeMode)

            manager.disconnect("server")
            val reconnectedBrowser = browser(replacementActivity)
            assertNotSame(originalBrowser, reconnectedBrowser)
            assertEquals(SftpFolder("/project"), reconnectedBrowser.backStack.last())
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
