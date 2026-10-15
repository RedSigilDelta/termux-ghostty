package com.mrndtvndv.term.ui.sftp

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mrndtvndv.term.workspace.HerdrFileScope
import com.mrndtvndv.term.workspace.SftpBrowserKey
import com.mrndtvndv.term.workspace.SftpScroll
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.WorkspaceFile
import com.mrndtvndv.term.workspace.WorkspaceRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SftpViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val owners = mutableMapOf<SftpViewModel, ViewModelStoreOwner>()
    private val herdrScope = HerdrFileScope("s1", "/tmp/herdr.sock", "w1")
    private val herdrKey = SftpBrowserKey.HerdrWorkspace(herdrScope)

    private val project = mapOf(
        "/project" to listOf(folder("/project/build"), file("/project/README.md")),
        "/project/build" to listOf(file("/project/build/app.apk")),
        "/other" to emptyList(),
    )
    private val tree = mapOf(
        "/r" to listOf(folder("/r/a")),
        "/r/a" to listOf(folder("/r/a/b")),
        "/r/a/b" to listOf(file("/r/a/b/c.txt")),
        "/other" to listOf(folder("/other/x")),
    )

    private fun browserTest(body: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            body()
        } finally {
            owners.values.forEach { it.viewModelStore.clear() }
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.repository(): WorkspaceRepository =
        WorkspaceRepository(WorkspaceFile(File(tmp.root, "workspaces.json")), backgroundScope)

    private fun controller(
        client: FakeSftpClient,
        repository: WorkspaceRepository,
        key: SftpBrowserKey,
        initialDir: String,
    ): SftpViewModel {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val viewModel = ViewModelProvider.create(
            owner,
            viewModelFactory {
                initializer { SftpViewModel(client, { "" }, null, "s1", repository, key, initialDir) }
            },
        )[SftpViewModel::class]
        owners[viewModel] = owner
        return viewModel
    }

    /** What a reconnect or cleared sheet store does: the controller and its client go away. */
    private fun dispose(viewModel: SftpViewModel) {
        owners.remove(viewModel)?.viewModelStore?.clear()
    }

    private fun WorkspaceRepository.sftpTab(serverId: String, dir: String, back: List<String> = emptyList()) =
        createWorkspace(serverId, "home", "/").let { workspace ->
            val tab = Tab.Sftp(id = "f1", dir = dir, back = back)
            openTab(workspace.id, tab)
            SftpBrowserKey.SftpTab(serverId, tab.id)
        }

    @Test
    fun herdrBrowserStartsAtCapturedCwdOnceThenResumes() = browserTest {
        val repository = repository()
        val first = controller(FakeSftpClient(project), repository, herdrKey, "/project")
        runCurrent()
        assertEquals(
            SftpUiState.Success("/project", listOf(folder("/project/build"), file("/project/README.md"))),
            first.uiState.value,
        )
        first.openFolder("/project/build")
        runCurrent()
        dispose(first)

        // The focused pane moved to /other meanwhile. The existing browser is not redirected.
        val client = FakeSftpClient(project)
        val resumed = controller(client, repository, herdrKey, "/other")
        runCurrent()

        assertEquals("/project/build", repository.browser(herdrKey)?.dir)
        assertEquals(listOf("/project"), repository.browser(herdrKey)?.back)
        assertEquals(listOf("/project/build"), client.requests)
        assertEquals("/project/build", resumed.uiState.value.path)
    }

    @Test
    fun browsersAreKeyedByServerSocketAndWorkspaceOrByServerAndTab() = browserTest {
        val repository = repository()
        val otherWorkspace = SftpBrowserKey.HerdrWorkspace(herdrScope.copy(herdrWorkspaceId = "w2"))
        val otherSocket = SftpBrowserKey.HerdrWorkspace(herdrScope.copy(socketPath = "/tmp/other.sock"))
        val tabKey = repository.sftpTab("s1", "/project")

        controller(FakeSftpClient(project), repository, herdrKey, "/project").openFolder("/project/build")
        controller(FakeSftpClient(project), repository, otherWorkspace, "/other")
        controller(FakeSftpClient(project), repository, tabKey, "/project").openFolder("/other")

        assertEquals("/project/build", repository.browser(herdrKey)?.dir)
        assertEquals("/other", repository.browser(otherWorkspace)?.dir)
        assertNull(repository.browser(otherSocket))
        assertEquals("/other", repository.browser(tabKey)?.dir)
        assertNull(repository.browser(SftpBrowserKey.SftpTab("s2", tabKey.tabId)))
    }

    @Test
    fun ordinaryTabResumesSavedNavigationAndPopsItDurably() = browserTest {
        val repository = repository()
        val key = repository.sftpTab("s1", "/project/build", back = listOf("/project"))
        val client = FakeSftpClient(project)
        val viewModel = controller(client, repository, key, "/project/build")
        runCurrent()
        assertEquals(listOf("/project/build"), client.requests)

        assertTrue(viewModel.goBack())
        val tab = repository.state.value.tab(key.tabId) as Tab.Sftp
        assertEquals("/project", tab.dir)
        assertEquals(emptyList<String>(), tab.back)
        assertFalse(viewModel.goBack())
    }

    @Test
    fun navigationIsDurableBeforeTheListingArrives() = browserTest {
        val repository = repository()
        val client = FakeSftpClient(project).apply { hold("/project/build") }
        val viewModel = controller(client, repository, herdrKey, "/project")
        runCurrent()

        viewModel.openFolder("/project/build")

        assertEquals("/project/build", repository.browser(herdrKey)?.dir)
        assertEquals(SftpUiState.Loading("/project/build"), viewModel.uiState.value)
        runCurrent()
        client.release("/project/build")
        runCurrent()
        assertEquals(
            SftpUiState.Success("/project/build", listOf(file("/project/build/app.apk"))),
            viewModel.uiState.value,
        )
    }

    @Test
    fun lateListingsOfEarlierFoldersNeverReplaceTheLatest() = browserTest {
        val folders = mapOf("/" to emptyList(), "/a" to listOf(file("/a/1")), "/c" to listOf(file("/c/3")))
        val client = FakeSftpClient(folders).apply {
            hold("/a")
            hold("/b")
            hold("/c")
        }
        val viewModel = controller(client, repository(), herdrKey, "/")
        runCurrent()
        viewModel.openFolder("/a")
        runCurrent()
        viewModel.openFolder("/b")
        runCurrent()
        viewModel.openFolder("/c")
        runCurrent()

        client.release("/c")
        runCurrent()
        client.release("/a")
        client.release("/b", Result.failure(IOException("gone")))
        runCurrent()

        assertEquals(SftpUiState.Success("/c", listOf(file("/c/3"))), viewModel.uiState.value)
        assertFalse(viewModel.isRefreshing.value)
    }

    @Test
    fun supersededListingIsCancelledWithoutReportingAnError() = browserTest {
        val client = FakeSftpClient(project).apply {
            hold("/project", FakeSftpClient.Hold.Cancellable)
            hold("/other")
        }
        val viewModel = controller(client, repository(), herdrKey, "/project")
        runCurrent()

        viewModel.openFolder("/other")
        runCurrent()

        assertEquals(listOf("/project"), client.cancelled)
        assertEquals(SftpUiState.Loading("/other"), viewModel.uiState.value)
    }

    @Test
    fun staleRefreshDoesNotClearANewerRefresh() = browserTest {
        val client = FakeSftpClient(project)
        val viewModel = controller(client, repository(), herdrKey, "/project")
        runCurrent()
        val listed = viewModel.uiState.value
        client.hold("/project")

        viewModel.refresh()
        runCurrent()
        viewModel.refresh()
        runCurrent()
        assertTrue(viewModel.isRefreshing.value)
        assertEquals(listed, viewModel.uiState.value)

        client.release("/project")
        runCurrent()
        assertTrue(viewModel.isRefreshing.value)
        client.release("/project")
        runCurrent()
        assertFalse(viewModel.isRefreshing.value)
    }

    @Test
    fun failedListingBelongsToTheSelectedFolder() = browserTest {
        val repository = repository()
        val viewModel = controller(FakeSftpClient(project), repository, herdrKey, "/project")
        runCurrent()

        viewModel.openFolder("/project/missing")
        runCurrent()

        assertEquals(
            SftpUiState.Error("/project/missing", "No such folder: /project/missing"),
            viewModel.uiState.value,
        )
        assertEquals("/project/missing", repository.browser(herdrKey)?.dir)
        assertTrue(viewModel.goBack())
        runCurrent()
        assertEquals("/project", viewModel.uiState.value.path)
    }

    @Test
    fun collapseDuringExpansionDropsTheLateChildren() = browserTest {
        val repository = repository()
        val client = FakeSftpClient(tree)
        val viewModel = controller(client, repository, herdrKey, "/r")
        viewModel.setTreeMode(true)
        runCurrent()
        client.hold("/r/a")

        viewModel.toggleExpanded(folder("/r/a"))
        runCurrent()
        assertEquals(setOf("/r/a"), viewModel.tree.value.loading)
        viewModel.toggleExpanded(folder("/r/a"))
        client.release("/r/a")
        runCurrent()

        val browser = repository.browser(herdrKey)!!
        assertEquals(emptyList<String>(), browser.expanded)
        assertEquals(listOf("/r/a"), browser.collapsed)
        assertEquals(emptyMap<String, Any>(), viewModel.tree.value.children)
        assertEquals(emptySet<String>(), viewModel.tree.value.loading)
        assertFalse("/r/a/b" in client.requests)
    }

    @Test
    fun explicitCollapseSurvivesRestartAndBlocksAutoExpansion() = browserTest {
        val file = File(tmp.root, "workspaces.json")
        val repository = WorkspaceRepository(WorkspaceFile(file), backgroundScope)
        val viewModel = controller(FakeSftpClient(tree), repository, herdrKey, "/r")
        viewModel.setTreeMode(true)
        runCurrent()
        viewModel.toggleExpanded(folder("/r/a"))
        runCurrent()
        // The only child of an expanded folder opens on its own.
        assertEquals(listOf("/r/a", "/r/a/b"), repository.browser(herdrKey)?.expanded)
        viewModel.toggleExpanded(folder("/r/a/b"))
        viewModel.toggleExpanded(folder("/r/a"))
        repository.close()
        dispose(viewModel)

        val restored = WorkspaceRepository(WorkspaceFile(file), backgroundScope)
        val client = FakeSftpClient(tree)
        val reopened = controller(client, restored, herdrKey, "/r")
        runCurrent()
        reopened.toggleExpanded(folder("/r/a"))
        runCurrent()

        val browser = restored.browser(herdrKey)!!
        assertTrue(browser.treeMode)
        assertEquals(listOf("/r/a"), browser.expanded)
        assertEquals(listOf("/r/a/b"), browser.collapsed)
        assertEquals(listOf("/r", "/r/a"), client.requests)
    }

    @Test
    fun treeResultsOfAPreviousFolderAreDropped() = browserTest {
        val client = FakeSftpClient(tree)
        val viewModel = controller(client, repository(), herdrKey, "/r")
        viewModel.setTreeMode(true)
        runCurrent()
        client.hold("/r/a")
        viewModel.toggleExpanded(folder("/r/a"))
        runCurrent()

        viewModel.openFolder("/other")
        runCurrent()
        client.release("/r/a")
        runCurrent()

        assertEquals(SftpUiState.Success("/other", listOf(folder("/other/x"))), viewModel.uiState.value)
        assertEquals(emptyMap<String, Any>(), viewModel.tree.value.children)
        assertEquals(emptySet<String>(), viewModel.tree.value.loading)
    }

    @Test
    fun expandedFoldersAreListedAgainWhenTheBrowserReturns() = browserTest {
        val repository = repository()
        val viewModel = controller(FakeSftpClient(tree), repository, herdrKey, "/r")
        viewModel.setTreeMode(true)
        runCurrent()
        viewModel.toggleExpanded(folder("/r/a"))
        runCurrent()
        viewModel.openFolder("/other")
        runCurrent()

        viewModel.goBack()
        runCurrent()

        val children = viewModel.tree.value.children
        assertEquals(listOf(folder("/r/a/b")), children["/r/a"])
        assertEquals(listOf(file("/r/a/b/c.txt")), children["/r/a/b"])
    }

    @Test
    fun scrollIsKeptPerFolderAndPersistsWithTheTab() = browserTest {
        val file = File(tmp.root, "workspaces.json")
        val repository = WorkspaceRepository(WorkspaceFile(file), backgroundScope)
        val key = repository.sftpTab("s1", "/project")
        val viewModel = controller(FakeSftpClient(project), repository, key, "/project")
        runCurrent()

        viewModel.saveScroll("/project", SftpScroll(10, 5))
        viewModel.openFolder("/project/build")
        viewModel.saveScroll("/project/build", SftpScroll(3, 1))
        viewModel.goBack()
        repository.close()

        val restored = WorkspaceRepository(WorkspaceFile(file), backgroundScope).browser(key)!!
        assertEquals("/project", restored.dir)
        assertEquals(SftpScroll(10, 5), restored.scrollOf("/project"))
        assertEquals(SftpScroll(3, 1), restored.scrollOf("/project/build"))
        assertEquals(SftpScroll(), restored.scrollOf("/other"))
    }
}
