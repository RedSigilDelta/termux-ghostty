package com.mrndtvndv.term.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HerdrWorkspaceBindingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun bound(tabs: List<Tab> = emptyList()) = Workspace(
        id = "local-1",
        serverId = "srv",
        name = "Work",
        root = "/home/u",
        tabs = tabs,
        activeTabId = tabs.firstOrNull()?.id,
        herdr = HerdrWorkspaceBinding(workspaceId = "w1"),
    )

    @Test
    fun unbindKeepsLocalTabsAndDropsOnlyTheBinding() {
        val local = Tab.Sftp(id = "sftp-1", dir = "/home/u/src")
        val state = WorkspaceState(workspaces = listOf(bound(listOf(local))), activeWorkspaceId = "local-1")

        val unbound = state.unbindHerdr("local-1").workspaces.single()

        assertNull(unbound.herdr)
        assertEquals(listOf<Tab>(local), unbound.tabs)
    }

    @Test
    fun hiddenTerminalsAreStoredOnTheBinding() {
        val state = WorkspaceState(workspaces = listOf(bound()))

        val hidden = state.setHiddenTerminals("local-1", setOf("term1", "term2"))

        assertEquals(setOf("term1", "term2"), hidden.workspaces.single().herdr?.hiddenTerminalIds)
    }

    @Test
    fun bindingAndHiddenTerminalsSurviveAFileRoundTrip() {
        val file = File(temp.root, "workspaces.json")
        val state = WorkspaceState(
            workspaces = listOf(bound(listOf(Tab.Terminal(id = "legacy", cwd = "/home/u")))),
            activeWorkspaceId = "local-1",
        ).setHiddenTerminals("local-1", setOf("term1"))

        WorkspaceFile(file).write(state)

        assertEquals(state, WorkspaceFile(file).read())
    }

    @Test
    fun filesWithoutHerdrFieldsStillLoadWithNoBinding() {
        val file = File(temp.root, "workspaces.json")
        file.writeText(
            """{"workspaces":[{"id":"a","serverId":"s","name":"n","root":"/",""" +
                """"tabs":[{"type":"terminal","id":"t","cwd":"/"}]}]}""",
        )

        val workspace = WorkspaceFile(file).read().workspaces.single()

        assertNull(workspace.herdr)
        assertNull((workspace.tabs.single() as Tab.Terminal).herdrTerminal)
    }
}
