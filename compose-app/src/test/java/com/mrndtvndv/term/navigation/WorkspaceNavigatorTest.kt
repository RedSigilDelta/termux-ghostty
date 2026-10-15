package com.mrndtvndv.term.navigation

import com.mrndtvndv.term.workspace.Tab
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceNavigatorTest {

    private val navigator = WorkspaceNavigator { null }

    @Test
    fun seedsSftpStackWithBackThenDir() {
        val tab = Tab.Sftp(id = "f1", dir = "/home/src/app", back = listOf("/home", "/home/src"))

        assertEquals(
            listOf(SftpFolder("/home"), SftpFolder("/home/src"), SftpFolder("/home/src/app")),
            navigator.sftpStack(tab).toList(),
        )
    }

    @Test
    fun keepsFolderOnceWhenBackEndsInDir() {
        // A kill mid-load leaves back ending in dir, because dir only moves when the listing succeeds.
        val tab = Tab.Sftp(id = "f1", dir = "/home/src", back = listOf("/home", "/home/src"))

        assertEquals(listOf(SftpFolder("/home"), SftpFolder("/home/src")), navigator.sftpStack(tab).toList())
    }
}
