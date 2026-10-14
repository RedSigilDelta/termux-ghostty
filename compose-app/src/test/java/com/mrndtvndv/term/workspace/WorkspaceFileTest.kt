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
