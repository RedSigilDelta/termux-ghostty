package com.termux.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceStateTest {

    private fun stateWithTabs(vararg tabIds: String): WorkspaceState =
        tabIds.fold(WorkspaceState().addWorkspace(Workspace(id = "w1", name = "home", root = "/home"))) { state, id ->
            state.addTab("w1", Tab.Terminal(id = id, cwd = "/home"), activate = false)
        }

    private fun tabIds(state: WorkspaceState): List<String> = state.workspaces.single().tabs.map { it.id }

    @Test
    fun firstTabBecomesActiveWithoutActivate() {
        val state = stateWithTabs("t1")

        assertEquals("t1", state.activeWorkspace?.activeTabId)
    }

    @Test
    fun addTabWithoutActivateKeepsActiveTab() {
        val state = stateWithTabs("t1", "t2")

        assertEquals("t1", state.activeWorkspace?.activeTabId)
        assertEquals(listOf("t1", "t2"), tabIds(state))
    }

    @Test
    fun addTabWithActivateSelectsNewTab() {
        val state = stateWithTabs("t1").addTab("w1", Tab.Terminal(id = "t2", cwd = "/home"), activate = true)

        assertEquals("t2", state.activeWorkspace?.activeTabId)
    }

    @Test
    fun closeActiveMiddleTabActivatesTabAtSameIndex() {
        val state = stateWithTabs("t1", "t2", "t3").activateTab("t2").closeTab("t2")

        assertEquals("t3", state.activeWorkspace?.activeTabId)
        assertEquals(listOf("t1", "t3"), tabIds(state))
    }

    @Test
    fun closeActiveLastTabActivatesNewLastTab() {
        val state = stateWithTabs("t1", "t2", "t3").activateTab("t3").closeTab("t3")

        assertEquals("t2", state.activeWorkspace?.activeTabId)
    }

    @Test
    fun closeOnlyTabClearsActiveTab() {
        val state = stateWithTabs("t1").closeTab("t1")

        assertNull(state.activeWorkspace?.activeTabId)
        assertEquals(emptyList<String>(), tabIds(state))
    }

    @Test
    fun closeInactiveTabKeepsActiveTab() {
        val state = stateWithTabs("t1", "t2", "t3").activateTab("t1").closeTab("t3")

        assertEquals("t1", state.activeWorkspace?.activeTabId)
        assertEquals(listOf("t1", "t2"), tabIds(state))
    }

    @Test
    fun activateTabSwitchesActiveWorkspace() {
        val state = stateWithTabs("t1")
            .addWorkspace(Workspace(id = "w2", name = "other", root = "/other"))
            .addTab("w2", Tab.Terminal(id = "t2", cwd = "/other"), activate = true)

        val result = state.activateTab("t1")

        assertEquals("w1", result.activeWorkspaceId)
        assertEquals("t1", result.activeWorkspace?.activeTabId)
    }

    @Test
    fun activateWorkspaceSetsLastUsed() {
        val state = stateWithTabs("t1")
            .addWorkspace(Workspace(id = "w2", name = "other", root = "/other"))
            .activateWorkspace("w1", now = 42L)

        assertEquals("w1", state.activeWorkspaceId)
        assertEquals(42L, state.workspaces.first { it.id == "w1" }.lastUsed)
    }

    @Test
    fun removeActiveWorkspaceActivatesMostRecentlyUsedRemaining() {
        val state = stateWithTabs("t1")
            .addWorkspace(Workspace(id = "w2", name = "old", root = "/old", lastUsed = 10L))
            .addWorkspace(Workspace(id = "w3", name = "recent", root = "/recent", lastUsed = 20L))
            .activateWorkspace("w2", now = 30L)

        val result = state.removeWorkspace("w2", now = 40L)

        assertEquals(listOf("w1", "w3"), result.workspaces.map { it.id })
        assertEquals("w3", result.activeWorkspaceId)
        assertEquals(40L, result.workspaces.first { it.id == "w3" }.lastUsed)
    }

    @Test
    fun removeInactiveWorkspaceKeepsActiveWorkspace() {
        val state = stateWithTabs("t1")
            .addWorkspace(Workspace(id = "w2", name = "other", root = "/other"))
            .activateWorkspace("w1", now = 5L)

        val result = state.removeWorkspace("w2", now = 40L)

        assertEquals(listOf("w1"), result.workspaces.map { it.id })
        assertEquals("w1", result.activeWorkspaceId)
        assertEquals(5L, result.activeWorkspace?.lastUsed)
    }

    @Test
    fun removeLastWorkspaceClearsActiveWorkspace() {
        val state = stateWithTabs("t1").removeWorkspace("w1", now = 1L)

        assertEquals(emptyList<Workspace>(), state.workspaces)
        assertNull(state.activeWorkspaceId)
    }

    @Test
    fun renameWorkspaceChangesOnlyName() {
        val state = stateWithTabs("t1").renameWorkspace("w1", "renamed")

        assertEquals("renamed", state.activeWorkspace?.name)
        assertEquals("/home", state.activeWorkspace?.root)
        assertEquals(listOf("t1"), tabIds(state))
    }

    @Test
    fun unknownIdsLeaveStateUnchanged() {
        val state = stateWithTabs("t1")

        assertEquals(state, state.activateWorkspace("missing", now = 1L))
        assertEquals(state, state.activateTab("missing"))
        assertEquals(state, state.addTab("missing", Tab.Terminal(id = "t2", cwd = "/home"), activate = true))
        assertEquals(state, state.closeTab("missing"))
        assertEquals(state, state.updateTab("missing") { it })
        assertEquals(state, state.removeWorkspace("missing", now = 1L))
        assertEquals(state, state.renameWorkspace("missing", "renamed"))
    }
}
