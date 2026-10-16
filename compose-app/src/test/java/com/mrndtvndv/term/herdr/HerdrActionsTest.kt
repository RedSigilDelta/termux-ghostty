package com.mrndtvndv.term.herdr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrActionsTest {
    @Test
    fun `action failure reaches the session owner after its activity waiter is cancelled`() = withScope {
        val server = serverWith(FakePane("p-1"))
        val client = clientFor(server, this)
        client.start()
        val failure = CompletableDeferred<HerdrActionResult.Failed>()
        val actions = HerdrActions(client, this, onFailure = { failure.complete(it) })
        val gate = CompletableDeferred<Unit>()
        server.gateNextSnapshot(gate)
        // A close is used because a focus on a missing terminal is not reported.
        val ticket = actions.closePane("missing-terminal")
        val activity = launch { ticket.result.await() }
        awaitUntil { server.requestCount("session.snapshot") == 2 }
        activity.cancelAndJoin()
        gate.complete(Unit)

        assertEquals(HerdrActionFailure.TargetGone, failure.await().reason)
        actions.close()
        client.close()
    }

    @Test
    fun `sheet context waits for invalidations received during its snapshot`() = withScope {
        val server = serverWith(FakePane("p-1"))
        val (client, actions) = started(server, this)
        val firstReply = CompletableDeferred<Unit>()
        val coherentReply = CompletableDeferred<Unit>()
        server.gateNextSnapshot(firstReply)
        val context = launchAwait { actions.awaitSettledSnapshot() }
        awaitUntil { server.requestCount("session.snapshot") == 2 }

        // Same synchronous invalidation path used by topology events while a fetch is in flight.
        client.refresh()
        server.gateNextSnapshot(coherentReply)
        firstReply.complete(Unit)
        awaitUntil { server.requestCount("session.snapshot") == 3 }
        assertFalse(context.isCompleted)

        coherentReply.complete(Unit)
        assertTrue((context.await() as HerdrFreshSnapshot.Ready).state.snapshotFresh)
        client.close()
    }

    // ---- Fresh snapshots at the control seam ---------------------------------------------------

    @Test
    fun `a fresh-snapshot wait ignores a fetch that started before it`() = withScope {
        val server = serverWith(FakePane("p-1"))
        val client = clientFor(server, this)
        client.start()
        val gate = CompletableDeferred<Unit>()
        server.gateNextSnapshot(gate)
        client.refresh()
        awaitUntil { server.requestCount("session.snapshot") == 2 }

        val waiting = launchAwait { client.awaitFreshSnapshot(TIMEOUT_MILLIS) }
        gate.complete(Unit)
        val fresh = waiting.await() as HerdrFreshSnapshot.Ready

        // Fetch 2 was in flight when the wait began, so only fetch 3 can satisfy it.
        assertEquals(3, server.requestCount("session.snapshot"))
        assertTrue(fresh.state.snapshotGeneration > 0)
        client.close()
    }

    @Test
    fun `a fresh-snapshot wait on a closed client is unavailable at once`() = withScope {
        val client = clientFor(serverWith(FakePane("p-1")), this)
        client.start()
        client.close()

        assertTrue(client.awaitFreshSnapshot(TIMEOUT_MILLIS) is HerdrFreshSnapshot.Unavailable)
    }

    // ---- Focus resolution ------------------------------------------------------------------------

    @Test
    fun `focus of a cached pane that moves mid-send lands on its stable terminal`() = withScope {
        val server = serverWith(FakePane("p-1", terminalId = "t-agent"), FakePane("p-2"))
        server.focusedPaneId = "p-2"
        val (client, actions) = started(server, this)
        val reply = CompletableDeferred<Unit>()
        server.gateNextReply("pane.focus", reply)

        // The cached snapshot maps the terminal to p-1 when the focus is sent. Herdr renumbers
        // it while that request is held.
        val ticket = actions.focusTerminal("t-agent")
        awaitUntil { server.requestCount("pane.focus") == 1 }
        server.movePane("p-1", "p-5")
        reply.complete(Unit)
        val result = ticket.result.await()

        assertEquals(HerdrActionResult.Focused("tab-1", GlobalFocus.Observed), result)
        // The stale ID is rejected, then the retry from a fresh snapshot focuses the terminal's new pane.
        assertEquals(
            listOf("p-1", "p-5"),
            server.paramsOf("pane.focus").map { it.getValue("pane_id").jsonPrimitive.content },
        )
        assertEquals("p-5", server.focusedPaneId)
        // pane.focus alone selects workspace, tab and pane upstream; no stale tab.focus follows it.
        assertEquals(0, server.requestCount("tab.focus"))
        client.close()
    }

    @Test
    fun `focus of a pane that closes mid-send is rejected once and then reported gone`() = withScope {
        val server = serverWith(FakePane("p-1"), FakePane("p-2"))
        val failures = java.util.concurrent.CopyOnWriteArrayList<HerdrActionResult.Failed>()
        val (client, actions) = started(server, this, onFailure = { failures.add(it) })
        val reply = CompletableDeferred<Unit>()
        server.gateNextReply("pane.focus", reply)

        val ticket = actions.focusTerminal("t-p-1")
        awaitUntil { server.requestCount("pane.focus") == 1 }
        server.removePane("p-1")
        reply.complete(Unit)
        val result = ticket.result.await()

        assertEquals(HerdrActionFailure.TargetGone, (result as HerdrActionResult.Failed).reason)
        // Panes close in normal use, so a focus that finds its pane gone is not reported.
        assertTrue(failures.isEmpty())
        // The cached ID is sent once. The retry resolves from a fresh snapshot, which no longer has the pane.
        assertEquals(
            listOf("p-1"),
            server.paramsOf("pane.focus").map { it.getValue("pane_id").jsonPrimitive.content },
        )
        client.close()
    }

    @Test
    fun `an acknowledgment for another terminal is retried once and then reported`() = withScope {
        val server = serverWith(FakePane("p-1"), FakePane("p-2"))
        server.focusAckPaneOverride = "p-2"
        val failures = java.util.concurrent.CopyOnWriteArrayList<HerdrActionResult.Failed>()
        val (client, actions) = started(server, this, onFailure = { failures.add(it) })

        val result = actions.focusTerminal("t-p-1").result.await() as HerdrActionResult.Failed

        // Another pane may hold focus, so this is a rejection and not a quiet missing pane.
        assertEquals(HerdrActionFailure.Rejected, result.reason)
        assertTrue(result.message.contains("another pane may now be focused"))
        assertEquals(2, server.requestCount("pane.focus"))
        awaitUntil { failures.isNotEmpty() }
        assertEquals(result, failures.single())
        client.close()
    }

    @Test
    fun `an acknowledged focus that the snapshot does not show is not reported as observed`() = withScope {
        val server = serverWith(FakePane("p-1"), FakePane("p-2"))
        server.focusedPaneId = "p-2"
        server.focusAcksWithoutChange = true
        val (client, actions) = started(server, this)

        val result = actions.focusTerminal("t-p-1").result.await()

        assertEquals(HerdrActionResult.Focused("tab-1", GlobalFocus.NotObserved), result)
        client.close()
    }

    @Test
    fun `tab focus re-resolves the tab from an anchor terminal`() = withScope {
        val server = serverWith(FakePane("p-1"), FakePane("p-2", tabId = "tab-2"))
        server.focusedPaneId = "p-1"
        val (client, actions) = started(server, this)

        val result = actions.focusTabOf("t-p-2").result.await()

        assertEquals(listOf("tab-2"), server.paramsOf("tab.focus").map { it.getValue("tab_id").jsonPrimitive.content })
        assertEquals(HerdrActionResult.Focused("tab-2", GlobalFocus.Observed), result)
        client.close()
    }

    // ---- Reconnects and missing snapshots --------------------------------------------------------

    @Test
    fun `focus during a reconnect fails at once without using the snapshot of the lost connection`() = withScope {
        val server = serverWith(FakePane("p-1", terminalId = "t-agent"))
        val failures = java.util.concurrent.CopyOnWriteArrayList<HerdrActionResult.Failed>()
        val (client, actions) = started(server, this, onFailure = { failures.add(it) })
        val bootstrap = CompletableDeferred<Unit>()
        server.gateNextSnapshot(bootstrap)
        server.topologySubscriptions().single().channel.finish()
        awaitUntil { server.requestCount("session.snapshot") == 2 }
        // While reconnecting, the last-known snapshot still says p-1, but that connection is gone.
        assertEquals("p-1", client.state.value.snapshot?.panes?.single()?.id)
        server.movePane("p-1", "p-7")

        val result = actions.focusTerminal("t-agent").result.await()
        bootstrap.complete(Unit)

        assertEquals(HerdrActionFailure.Disconnected, (result as HerdrActionResult.Failed).reason)
        assertEquals(0, server.requestCount("pane.focus"))
        awaitUntil { failures.isNotEmpty() }
        assertEquals(result, failures.single())
        client.close()
    }

    @Test
    fun `focus without a live client fails at once instead of guessing`() = withScope {
        val server = serverWith(FakePane("p-1"))
        server.replies["session.snapshot"] = { null }
        val client = clientFor(server, this)
        launch { runCatching { client.start() } }
        val failures = java.util.concurrent.CopyOnWriteArrayList<HerdrActionResult.Failed>()
        val actions = HerdrActions(client, this, onFailure = { failures.add(it) })

        val result = actions.focusTerminal("t-p-1").result.await() as HerdrActionResult.Failed

        assertEquals(HerdrActionFailure.Disconnected, result.reason)
        assertEquals(0, server.requestCount("pane.focus"))
        awaitUntil { failures.isNotEmpty() }
        assertEquals(result, failures.single())
        client.close()
    }

    // ---- Competing intents -----------------------------------------------------------------------

    @Test
    fun `a newer focus supersedes an earlier one before it sends anything`() = withScope {
        val server = serverWith(FakePane("p-1"), FakePane("p-2"), FakePane("p-3"))
        val (client, actions) = started(server, this)
        val reply = CompletableDeferred<Unit>()
        server.gateNextReply("pane.close", reply)
        // The close holds the serial queue while its request is in flight, so both focuses wait behind it.
        // The close targets a third pane, so neither focus races it.
        actions.closePane("t-p-3")
        awaitUntil { server.requestCount("pane.close") == 1 }

        val first = actions.focusTerminal("t-p-1")
        val second = actions.focusTerminal("t-p-2")
        reply.complete(Unit)

        assertEquals(HerdrActionFailure.Superseded, (first.result.await() as HerdrActionResult.Failed).reason)
        assertTrue(second.result.await() is HerdrActionResult.Focused)
        assertEquals(listOf("p-2"), server.paramsOf("pane.focus").map { it.getValue("pane_id").jsonPrimitive.content })
        assertEquals("p-2", server.focusedPaneId)
        assertFalse(actions.isLatest(first.intent))
        assertTrue(actions.isLatest(second.intent))
        client.close()
    }

    @Test
    fun `a sent focus finishes before the newer one, so the newer focus wins on the server`() = withScope {
        val server = serverWith(FakePane("p-1"), FakePane("p-2"))
        val (client, actions) = started(server, this)
        val reply = CompletableDeferred<Unit>()
        server.gateNextReply("pane.focus", reply)

        val first = actions.focusTerminal("t-p-1")
        awaitUntil { server.requestCount("pane.focus") == 1 }
        val second = actions.focusTerminal("t-p-2")
        // The earlier action is superseded at once, while its request is still unanswered.
        assertEquals(HerdrActionFailure.Superseded, (first.result.await() as HerdrActionResult.Failed).reason)
        assertEquals(1, server.requestCount("pane.focus"))
        reply.complete(Unit)

        assertTrue(second.result.await() is HerdrActionResult.Focused)
        assertEquals(
            listOf("p-1", "p-2"),
            server.paramsOf("pane.focus").map { it.getValue("pane_id").jsonPrimitive.content },
        )
        assertEquals("p-2", server.focusedPaneId)
        client.close()
    }

    @Test
    fun `a settled snapshot is dropped when a newer intent arrives while it is fetched`() = withScope {
        val server = serverWith(FakePane("p-1"))
        val (client, actions) = started(server, this)
        val gate = CompletableDeferred<Unit>()
        server.gateNextSnapshot(gate)

        val capture = launchAwait { actions.awaitSettledSnapshot() }
        awaitUntil { server.requestCount("session.snapshot") == 2 }
        actions.supersede()
        gate.complete(Unit)

        assertNull(capture.await())
        client.close()
    }

    // ---- Lifetime --------------------------------------------------------------------------------

    @Test
    fun `an action outlives the caller that awaited it`() = withScope {
        val server = serverWith(FakePane("p-1"))
        val (client, actions) = started(server, this)
        val reply = CompletableDeferred<Unit>()
        server.gateNextReply("pane.focus", reply)

        val ticket = actions.focusTerminal("t-p-1")
        // An activity-scoped observer goes away mid-action.
        val observer = launch { ticket.result.await() }
        awaitUntil { server.requestCount("pane.focus") == 1 }
        observer.cancelAndJoin()
        reply.complete(Unit)

        assertTrue(ticket.result.await() is HerdrActionResult.Focused)
        assertEquals("p-1", server.focusedPaneId)
        client.close()
    }

    @Test
    fun `closing fails running and queued actions as disconnected`() = withScope {
        val server = serverWith(FakePane("p-1"), FakePane("p-2"))
        val (client, actions) = started(server, this)
        val gate = CompletableDeferred<Unit>()
        server.gateNextSnapshot(gate)

        val focus = actions.focusTerminal("t-p-1")
        val close = actions.closePane("t-p-2")
        awaitUntil { server.requestCount("session.snapshot") == 2 }
        actions.close()
        gate.complete(Unit)

        assertEquals(HerdrActionFailure.Disconnected, (focus.result.await() as HerdrActionResult.Failed).reason)
        assertEquals(HerdrActionFailure.Disconnected, (close.result.await() as HerdrActionResult.Failed).reason)
        assertFalse(actions.isLatest(focus.intent))
        val late = actions.focusTerminal("t-p-1").result.await() as HerdrActionResult.Failed
        assertEquals(HerdrActionFailure.Disconnected, late.reason)
        // The focus was acknowledged before close, so it stays sent. Nothing queued behind it, or after close, is sent.
        assertEquals(
            listOf("p-1"),
            server.paramsOf("pane.focus").map { it.getValue("pane_id").jsonPrimitive.content },
        )
        assertEquals(0, server.requestCount("pane.close"))
        client.close()
    }

    @Test
    fun `a closed client fails the action as disconnected`() = withScope {
        val server = serverWith(FakePane("p-1"))
        val (client, actions) = started(server, this)
        client.close()

        val result = actions.focusTerminal("t-p-1").result.await() as HerdrActionResult.Failed

        assertEquals(HerdrActionFailure.Disconnected, result.reason)
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private fun withScope(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(TEST_TIMEOUT_MILLIS) { scope.block() }
        } finally {
            scope.cancel()
        }
    }

    private fun <T> CoroutineScope.launchAwait(block: suspend () -> T) =
        CompletableDeferred<T>().also { result -> launch { result.complete(block()) } }

    private fun serverWith(vararg panes: FakePane) = FakeHerdrServer().also { it.panes += panes }

    private fun clientFor(server: FakeHerdrServer, scope: CoroutineScope) = HerdrControlClient(
        FakeHerdrSession(server),
        SOCKET_PATH,
        scope,
        HerdrClientConfig(
            requestTimeoutMillis = 2_000,
            maxReconnectAttempts = 3,
            reconnectBaseDelayMillis = 1,
            reconnectMaxDelayMillis = 4,
        ),
    )

    private suspend fun started(
        server: FakeHerdrServer,
        scope: CoroutineScope,
        onFailure: (HerdrActionResult.Failed) -> Unit = {},
    ): Pair<HerdrControlClient, HerdrActions> {
        val client = clientFor(server, scope)
        client.start()
        client.state.first { it.phase == HerdrPhase.Live }
        return client to HerdrActions(client, scope, freshTimeoutMillis = TIMEOUT_MILLIS, onFailure = onFailure)
    }

    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(TIMEOUT_MILLIS) {
        while (!condition()) delay(1)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val TEST_TIMEOUT_MILLIS = 20_000L
        const val SOCKET_PATH = "/run/user/501/herdr.sock"
    }
}
