package com.mrndtvndv.term.workspace

import com.mrndtvndv.term.herdr.HerdrAgentStatus
import com.mrndtvndv.term.herdr.HerdrError
import com.mrndtvndv.term.herdr.HerdrErrorKind
import com.mrndtvndv.term.herdr.HerdrPane
import com.mrndtvndv.term.herdr.HerdrPhase
import com.mrndtvndv.term.herdr.HerdrSnapshot
import com.mrndtvndv.term.herdr.HerdrState
import com.mrndtvndv.term.herdr.HerdrTab
import com.mrndtvndv.term.herdr.HerdrWorkspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrWorkspaceProjectionTest {
    private val binding = HerdrWorkspaceBinding(workspaceId = "w1")

    private fun pane(terminalId: String, tabId: String, cwd: String? = "/srv/$terminalId") = HerdrPane(
        id = "p-$terminalId", terminalId = terminalId, workspaceId = "w1", tabId = tabId, focused = false,
        cwd = cwd, foregroundCwd = null, restoreError = null, label = null, agent = null, title = null,
        terminalTitle = null, displayAgent = null, agentStatus = HerdrAgentStatus.Idle,
        stateLabels = emptyMap(), tokens = emptyMap(), scroll = null, revision = 1,
    )

    private fun tab(id: String, number: Int, label: String = "tab $number") = HerdrTab(
        id = id, workspaceId = "w1", number = number, label = label, focused = false,
        paneCount = 0, agentStatus = HerdrAgentStatus.Idle,
    )

    private fun snapshot(
        workspaces: List<String> = listOf("w1"),
        tabs: List<HerdrTab> = listOf(tab("t1", 1), tab("t2", 2)),
        panes: List<HerdrPane> = listOf(pane("term1", "t1"), pane("term2", "t1"), pane("term3", "t2")),
    ) = HerdrSnapshot(
        version = "0.9.1", protocol = 1, focusedWorkspaceId = null, focusedTabId = null, focusedPaneId = null,
        workspaces = workspaces.map {
            HerdrWorkspace(
                id = it, number = 1, label = "Work", focused = false, paneCount = 0, tabCount = 0,
                activeTabId = "t1", agentStatus = HerdrAgentStatus.Idle, tokens = emptyMap(), worktree = null,
            )
        },
        tabs = tabs,
        panes = panes,
        layouts = emptyList(),
        agents = emptyList(),
    )

    private fun live(snapshot: HerdrSnapshot, fresh: Boolean = true) = HerdrState(
        phase = HerdrPhase.Live, snapshot = snapshot, snapshotFresh = fresh,
    )

    @Test
    fun cardsFollowTabOrderAndUseFirstPaneByDefault() {
        val view = projectRemoteWorkspace(live(snapshot()), binding) as RemoteWorkspaceView.Bound

        assertEquals(false, view.stale)
        assertEquals(listOf("t1", "t2"), view.cards.map { it.tabId })
        assertEquals("term1", view.cards[0].terminalId)
        assertEquals(listOf("term1", "term2"), view.cards[0].paneTerminalIds)
        assertEquals("/srv/term1", view.cards[0].cwd)
    }

    @Test
    fun selectedPaneBecomesTheCardsTerminal() {
        val view = projectRemoteWorkspace(
            live(snapshot()),
            binding,
            selectedTerminals = mapOf("t1" to "term2"),
        ) as RemoteWorkspaceView.Bound

        assertEquals("term2", view.cards[0].terminalId)
    }

    @Test
    fun hiddenTerminalHidesItsWholeCard() {
        val hidden = binding.copy(hiddenTerminalIds = setOf("term2"))

        val view = projectRemoteWorkspace(live(snapshot()), hidden) as RemoteWorkspaceView.Bound

        assertEquals(listOf("t2"), view.cards.map { it.tabId })
    }

    @Test
    fun absentWorkspaceIsMissingOnlyFromAFreshLiveSnapshot() {
        val other = binding.copy(workspaceId = "gone")

        assertEquals(RemoteWorkspaceView.Missing, projectRemoteWorkspace(live(snapshot()), other))

        val reconnecting = HerdrState(phase = HerdrPhase.Reconnecting, snapshot = snapshot(), snapshotFresh = false)
        val view = projectRemoteWorkspace(reconnecting, other) as RemoteWorkspaceView.Bound
        assertEquals(true, view.stale)
        assertEquals(emptyList<RemoteCard>(), view.cards)
    }

    @Test
    fun transportLossKeepsLastKnownCardsAsStale() {
        val lost = HerdrState(
            phase = HerdrPhase.Reconnecting,
            snapshot = snapshot(),
            snapshotFresh = false,
            error = HerdrError(HerdrErrorKind.Disconnected, "Herdr event stream closed"),
        )

        val view = projectRemoteWorkspace(lost, binding) as RemoteWorkspaceView.Bound

        assertEquals(true, view.stale)
        assertEquals(listOf("t1", "t2"), view.cards.map { it.tabId })
    }

    @Test
    fun noSnapshotReportsPhaseAndMessage() {
        val unsupported = HerdrState(
            phase = HerdrPhase.Unsupported,
            error = HerdrError(HerdrErrorKind.Unsupported, "Herdr 0.9.1 has no events.subscribe"),
        )

        val view = projectRemoteWorkspace(unsupported, binding)

        assertEquals(
            RemoteWorkspaceView.Unavailable(HerdrPhase.Unsupported, "Herdr 0.9.1 has no events.subscribe"),
            view,
        )
    }

    @Test
    fun endedTerminalsComeOnlyFromAFreshLiveSnapshot() {
        val state = live(snapshot())
        val attached = setOf("term1", "term-gone")

        assertEquals(setOf("term-gone"), endedRemoteTerminals(attached, state))
        assertEquals(emptySet<String>(), endedRemoteTerminals(attached, live(snapshot(), fresh = false)))
        val lost = HerdrState(phase = HerdrPhase.Reconnecting, snapshot = snapshot(), snapshotFresh = false)
        assertEquals(emptySet<String>(), endedRemoteTerminals(attached, lost))
    }

    @Test
    fun cardIdsRoundTripAndLocalIdsAreNotRemote() {
        assertEquals("herdr-terminal:term1", remoteCardId("term1"))
        assertEquals("term1", remoteTerminalId(remoteCardId("term1")))
        assertNull(remoteTerminalId("local-tab-id"))
    }

    @Test
    fun statusLabelsOnlySpeakWhenThereIsSomethingToSay() {
        assertEquals("Working", remoteStatusLabel(HerdrAgentStatus.Working, fresh = true))
        assertEquals("Needs input", remoteStatusLabel(HerdrAgentStatus.Blocked, fresh = true))
        assertEquals("Done", remoteStatusLabel(HerdrAgentStatus.Done, fresh = true))
        assertNull(remoteStatusLabel(HerdrAgentStatus.Idle, fresh = true))
        assertNull(remoteStatusLabel(HerdrAgentStatus.Unknown, fresh = true))
    }

    @Test
    fun sampledStatusIsLabelledAsLastKnownWhenNotFresh() {
        assertEquals("Last known: Working", remoteStatusLabel(HerdrAgentStatus.Working, fresh = false))
        assertNull(remoteStatusLabel(HerdrAgentStatus.Idle, fresh = false))
    }

    @Test
    fun lastKnownStatusNamesWhenItWasSampled() {
        val label = remoteStatusLabel(HerdrAgentStatus.Working, fresh = false, sampledAtMillis = 0L)

        assertTrue(label!!.startsWith("Last known: Working · as of "))
    }

    @Test
    fun cardCarriesWhetherItsStatusIsFresh() {
        val fresh = projectRemoteWorkspace(
            HerdrState(phase = HerdrPhase.Live, snapshot = snapshot(), snapshotFresh = true, agentStatusFresh = true),
            binding,
        ) as RemoteWorkspaceView.Bound
        val lastKnown = projectRemoteWorkspace(
            HerdrState(phase = HerdrPhase.Live, snapshot = snapshot(), snapshotFresh = true, agentStatusFresh = false),
            binding,
        ) as RemoteWorkspaceView.Bound

        assertEquals(true, fresh.cards.first().statusFresh)
        assertEquals(false, lastKnown.cards.first().statusFresh)
    }

    @Test
    fun controlSocketUsesConfiguredAbsolutePathOrHomeDefault() {
        assertEquals("/run/herdr.sock", herdrControlSocketPath("/run/herdr.sock", "/home/u"))
        assertEquals("/home/u/.config/herdr/herdr.sock", herdrControlSocketPath(null, "/home/u"))
        assertEquals("/home/u/.config/herdr/herdr.sock", herdrControlSocketPath(null, "/home/u/"))
        assertEquals("/home/u/.config/herdr/herdr.sock", herdrControlSocketPath("relative.sock", "/home/u"))
    }
}
