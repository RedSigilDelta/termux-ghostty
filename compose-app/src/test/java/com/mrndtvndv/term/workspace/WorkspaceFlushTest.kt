package com.mrndtvndv.term.workspace

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceFlushTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stopBoundaryPersistsNavigationWithoutWaitingForDebounce() = runTest {
        val file = WorkspaceFile(File(temporaryFolder.root, "workspaces.json"))
        val repository = WorkspaceRepository(file, backgroundScope)
        val key = SftpBrowserKey.HerdrWorkspace(HerdrFileScope("server", "/socket", "workspace"))
        repository.openBrowser(key, "/project")
        repository.updateBrowser(key) { it.open("/project/build").collapse("/project/build/a") }
        repository.flush().join()

        val restored = WorkspaceRepository(file, backgroundScope)
        assertEquals(repository.browser(key), restored.browser(key))
    }

    @Test
    fun failedPersistenceIsObservable() = runTest {
        val blockedParent = temporaryFolder.newFile("not-a-directory")
        val repository = WorkspaceRepository(WorkspaceFile(File(blockedParent, "workspaces.json")), backgroundScope)
        repository.flush().join()
        assertNotNull(repository.persistenceError.value)
    }
}
