package com.mrndtvndv.term.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val state = WorkspaceState(
        workspaces = listOf(
            Workspace(
                id = "w1",
                serverId = "s1",
                name = "home",
                root = "/home",
                tabs = listOf(
                    Tab.Terminal(id = "t1", name = "shell", cwd = "/home/src"),
                    Tab.Sftp(id = "f1", dir = "/home/src"),
                    Tab.Git(id = "g1", repoDir = "/home/src/repo"),
                    Tab.Viewer(id = "v1", path = "/home/src/main.kt"),
                ),
                activeTabId = "t1",
                lastUsed = 1234L,
            ),
        ),
        activeWorkspaceId = "w1",
    )

    @Test
    fun roundTripsAllTabTypes() {
        WorkspaceFile(File(tmp.root, "workspaces.json")).write(state)

        assertEquals(state, WorkspaceFile(File(tmp.root, "workspaces.json")).read())
    }

    @Test
    fun roundTripsSftpRestoreState() {
        val sftp = Tab.Sftp(
            id = "f2",
            dir = "/home/src",
            back = listOf("/home", "/home/src/app"),
            expanded = listOf("/home/src/app/ui"),
            scrollIndex = 42,
            scrollOffset = 17,
            treeMode = true,
        )
        val withSftp = state.copy(workspaces = listOf(state.workspaces.single().copy(tabs = listOf(sftp))))
        WorkspaceFile(File(tmp.root, "workspaces.json")).write(withSftp)

        assertEquals(withSftp, WorkspaceFile(File(tmp.root, "workspaces.json")).read())
    }

    @Test
    fun decodesSftpTabWithoutRestoreStateAsDefaults() {
        val file = File(tmp.root, "workspaces.json")
        file.writeText(
            """
            {"workspaces":[{"id":"w1","serverId":"s1","name":"home","root":"/home",
            "tabs":[{"type":"sftp","id":"f1","dir":"/home/src"}],"activeTabId":"f1"}],"activeWorkspaceId":"w1"}
            """.trimIndent()
        )

        val tab = WorkspaceFile(file).read().workspaces.single().tabs.single()

        assertEquals(Tab.Sftp(id = "f1", dir = "/home/src"), tab)
    }

    @Test
    fun decodesSavedSftpNavigationIntoItsBrowser() {
        val file = File(tmp.root, "workspaces.json")
        file.writeText(
            """
            {"workspaces":[{"id":"w1","serverId":"s1","name":"home","root":"/home",
            "tabs":[{"type":"sftp","id":"f1","dir":"/home/src","back":["/home"],"expanded":["/home/src/app"],
            "scrollIndex":42,"scrollOffset":17,"treeMode":true}],"activeTabId":"f1"}],"activeWorkspaceId":"w1"}
            """.trimIndent()
        )

        val browser = WorkspaceFile(file).read().browser(SftpBrowserKey.SftpTab("s1", "f1"))

        assertEquals(
            SftpBrowserState(
                dir = "/home/src",
                back = listOf("/home"),
                treeMode = true,
                expanded = listOf("/home/src/app"),
                scroll = mapOf("/home/src" to SftpScroll(42, 17)),
            ),
            browser,
        )
    }

    @Test
    fun roundTripsHerdrBrowsers() {
        val browser = HerdrSftpBrowser(
            scope = HerdrFileScope("s1", "/tmp/herdr.sock", "w1"),
            browser = SftpBrowserState(
                dir = "/project/build",
                back = listOf("/project"),
                treeMode = true,
                expanded = listOf("/project/build/out"),
                collapsed = listOf("/project/build/tmp"),
                scroll = mapOf("/project" to SftpScroll(3, 4)),
            ),
        )
        val withBrowser = state.copy(herdrBrowsers = listOf(browser))
        WorkspaceFile(File(tmp.root, "workspaces.json")).write(withBrowser)

        assertEquals(withBrowser, WorkspaceFile(File(tmp.root, "workspaces.json")).read())
    }

    @Test
    fun writesTabTypeDiscriminator() {
        WorkspaceFile(File(tmp.root, "workspaces.json")).write(state)

        assertTrue(File(tmp.root, "workspaces.json").readText().contains("\"type\":\"terminal\""))
    }

    @Test
    fun missingFileReadsAsEmptyState() {
        assertEquals(WorkspaceState(), WorkspaceFile(File(tmp.root, "missing.json")).read())
    }

    @Test
    fun invalidJsonReadsAsEmptyState() {
        val file = File(tmp.root, "workspaces.json")
        file.writeText("{ not json")

        assertEquals(WorkspaceState(), WorkspaceFile(file).read())
    }

    @Test
    fun writeLeavesNoTempFile() {
        WorkspaceFile(File(tmp.root, "workspaces.json")).write(state)

        assertFalse(File(tmp.root, "workspaces.json.tmp").exists())
    }
}
