package com.mrndtvndv.term.navigation

import com.mrndtvndv.term.workspace.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceNavigatorTest {

    private var active: Tab? = null
    private val navigator = WorkspaceNavigator { active }

    @Test
    fun sftpBackFollowsTheDurableFolderStack() {
        active = Tab.Sftp(id = "f1", dir = "/home/src", back = listOf("/home"))
        assertTrue(navigator.handlesBack)

        active = Tab.Sftp(id = "f1", dir = "/home")
        assertFalse(navigator.handlesBack)
    }

    @Test
    fun backReachesTheAttachedSftpBrowser() {
        active = Tab.Sftp(id = "f1", dir = "/home/src", back = listOf("/home"))
        var pops = 0
        val goBack: () -> Boolean = {
            pops++
            true
        }
        navigator.attachSftp("f1", goBack)
        // A browser that was replaced cannot detach its successor.
        navigator.detachSftp("f1") { false }

        assertTrue(navigator.goBack())
        navigator.detachSftp("f1", goBack)
        assertFalse(navigator.goBack())
        assertEquals(1, pops)
    }
}
