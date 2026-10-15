package com.mrndtvndv.term.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrProjectionTest {
    private val snapshot = HerdrWire.decodeSnapshot(
        HerdrWire.resultOf(HerdrWire.parseLine(snapshotReply("r")), "r"),
    )

    @Test
    fun `tabs are ordered by number within their workspace`() {
        assertEquals(listOf("tab-1", "tab-2"), snapshot.tabsIn("ws-1").map { it.id })
        assertTrue(snapshot.tabsIn("ws-missing").isEmpty())
    }

    @Test
    fun `panes match by tab and by stable terminal identity`() {
        assertEquals(listOf("p-1"), snapshot.panesIn("tab-1").map { it.id })
        assertEquals("p-2", snapshot.paneForTerminal("t-2")?.id)
        assertNull(snapshot.paneForTerminal("t-missing"))
    }

    @Test
    fun `focused pane resolves through server-global snapshot focus`() {
        assertEquals("t-1", snapshot.focusedPane()?.terminalId)
        assertNull(snapshot.copy(focusedPaneId = "p-missing").focusedPane())
    }

    @Test
    fun `layout and workspace lookups`() {
        assertEquals("tab-1", snapshot.layoutFor("tab-1")?.tabId)
        assertNull(snapshot.layoutFor("tab-2"))
        assertEquals("main", snapshot.workspaceById("ws-1")?.label)
    }
}
