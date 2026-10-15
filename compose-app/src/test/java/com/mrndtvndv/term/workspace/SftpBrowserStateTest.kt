package com.mrndtvndv.term.workspace

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SftpBrowserStateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = HerdrFileScope("s1", "/tmp/herdr.sock", "w1")
    private val herdrKey = SftpBrowserKey.HerdrWorkspace(scope)

    @Test
    fun openPushesNewFoldersAndPopsBackToKnownOnes() {
        val deep = SftpBrowserState("/home").open("/home/src").open("/home/src/app")

        assertEquals(listOf("/home", "/home/src"), deep.back)
        assertEquals(SftpBrowserState("/home/src", back = listOf("/home")), deep.open("/home/src"))
        assertSame(deep, deep.open("/home/src/app"))
        assertEquals(SftpBrowserState("/home/src", back = listOf("/home")), deep.goBack())
        assertNull(SftpBrowserState("/home").goBack())
    }

    @Test
    fun explicitCollapseBlocksAutoExpansionUntilExpandedAgain() {
        val collapsed = SftpBrowserState("/r").expand("/r/a").collapse("/r/a")

        assertSame(collapsed, collapsed.autoExpand("/r/a"))
        assertEquals(listOf("/r/a"), collapsed.expand("/r/a").expanded)
        assertEquals(emptyList<String>(), collapsed.expand("/r/a").collapsed)
        assertEquals(listOf("/r/b"), collapsed.autoExpand("/r/b").expanded)
    }

    @Test
    fun scrollKeepsTheMostRecentlySavedFolders() {
        val many = (0 until 70).fold(SftpBrowserState("/")) { state, i -> state.withScroll("/d$i", SftpScroll(i, 0)) }

        assertEquals(64, many.scroll.size)
        assertEquals(SftpScroll(), many.scrollOf("/d0"))
        assertEquals(SftpScroll(69, 0), many.scrollOf("/d69"))
    }

    @Test
    fun legacySftpTabKeepsItsFolderOnceAndItsScroll() {
        // Before navigation was immediate, a kill mid-load could leave back ending in dir.
        val tab = Tab.Sftp(
            id = "f1",
            dir = "/home/src",
            back = listOf("/home", "/home/src"),
            expanded = listOf("/home/src/app"),
            scrollIndex = 42,
            scrollOffset = 17,
            treeMode = true,
        )

        val browser = tab.browser

        assertEquals(listOf("/home"), browser.back)
        assertEquals(listOf("/home/src/app"), browser.expanded)
        assertEquals(SftpScroll(42, 17), browser.scrollOf("/home/src"))
        assertEquals(true, browser.treeMode)
    }

    @Test
    fun sftpTabStoresTheCurrentFolderScrollInItsOwnFields() {
        val tab = Tab.Sftp(id = "f1", dir = "/home", scrollIndex = 4, scrollOffset = 2)

        val moved = tab.withBrowser(tab.browser.open("/home/src").withScroll("/home/src", SftpScroll(9, 1)))

        assertEquals(9, moved.scrollIndex)
        assertEquals(1, moved.scrollOffset)
        assertEquals(mapOf("/home" to SftpScroll(4, 2)), moved.scrolls)
        val back = moved.withBrowser(moved.browser.goBack()!!)
        assertEquals(4, back.scrollIndex)
        assertEquals(mapOf("/home/src" to SftpScroll(9, 1)), back.scrolls)
    }

    @Test
    fun openingAnExistingHerdrBrowserKeepsItsNavigation() {
        val state = WorkspaceState()
            .openBrowser(herdrKey, "/project")
            .updateBrowser(herdrKey) { it.open("/project/build") }
            .openBrowser(herdrKey, "/elsewhere")

        assertEquals("/project/build", state.browser(herdrKey)?.dir)
    }

    @Test
    fun updatingAMissingBrowserCreatesNothing() {
        val state = WorkspaceState().updateBrowser(herdrKey) { it.open("/x") }

        assertNull(state.browser(herdrKey))
        assertEquals(WorkspaceState(), state.openBrowser(SftpBrowserKey.SftpTab("s1", "missing"), "/x"))
    }

    @Test
    fun removingAServerDropsOnlyItsHerdrBrowsers() {
        val otherServer = SftpBrowserKey.HerdrWorkspace(scope.copy(serverId = "s2"))
        val state = WorkspaceState()
            .openBrowser(herdrKey, "/project")
            .openBrowser(otherServer, "/other")
            .removeServer("s1")

        assertNull(state.browser(herdrKey))
        assertEquals("/other", state.browser(otherServer)?.dir)
    }

    @Test
    fun newRepositoryRestoresHerdrAndTabBrowsers() = runTest {
        val file = File(tmp.root, "workspaces.json")
        val repository = WorkspaceRepository(WorkspaceFile(file), backgroundScope)
        val workspace = repository.createWorkspace("s1", "home", "/home")
        repository.openTab(workspace.id, Tab.Sftp(id = "f1", dir = "/home"))
        val tabKey = SftpBrowserKey.SftpTab("s1", "f1")
        repository.openBrowser(herdrKey, "/project")
        repository.updateBrowser(herdrKey) { it.open("/project/build").copy(treeMode = true).collapse("/project/x") }
        repository.updateBrowser(tabKey) { it.open("/home/src").expand("/home/src/app") }
        repository.close()

        val restored = WorkspaceRepository(WorkspaceFile(file), backgroundScope)

        assertEquals(repository.browser(herdrKey), restored.browser(herdrKey))
        assertEquals(repository.browser(tabKey), restored.browser(tabKey))
        assertEquals(
            SftpBrowserState("/project/build", listOf("/project"), treeMode = true, collapsed = listOf("/project/x")),
            restored.browser(herdrKey),
        )
    }
}
