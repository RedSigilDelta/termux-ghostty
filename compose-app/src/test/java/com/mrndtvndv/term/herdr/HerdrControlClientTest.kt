package com.mrndtvndv.term.herdr

import com.mrndtvndv.term.domain.SshStreamChannelException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrControlClientTest {
    // ---- Sampled status --------------------------------------------------------------------------

    @Test
    fun `start subscribes the topology before the first snapshot and records a sample time`() = withScope {
        val server = serverWith("p-1")
        val client = clientFor(server, this)

        client.start()

        val state = client.state.value
        assertEquals("ping", server.requests.first())
        assertTrue(server.requests.indexOf("events.subscribe") < server.requests.indexOf("session.snapshot"))
        assertTrue(state.agentStatusFresh)
        assertNotNull(state.agentStatusSampledAtMillis)
        assertEquals(HerdrAgentStatus.Idle, state.snapshot?.panes?.single()?.agentStatus)
        // Status is sampled, so no pane-scoped subscription exists at all.
        assertTrue(server.subscriptions.none { it.statusPanes.isNotEmpty() })
        client.close()
    }

    @Test
    fun `a status change is sampled, not pushed, so it shows up on the next sample`() = withScope {
        val server = serverWith("p-1")
        val client = clientFor(server, this)
        client.start()

        server.setStatus("p-1", "working")

        // The first sample was just taken, so the next one is about five seconds away.
        assertEquals(HerdrAgentStatus.Idle, client.state.value.snapshot?.panes?.single()?.agentStatus)
        client.awaitState(SAMPLE_WAIT_MILLIS) {
            it.agentStatusFresh && it.snapshot?.panes?.single()?.agentStatus == HerdrAgentStatus.Working
        }
        client.close()
    }

    @Test
    fun `a fetch that just completed suppresses the timer fetch that was due`() = withScope {
        val server = serverWith("p-1")
        val client = clientFor(server, this)
        client.start()

        delay(3_000)
        server.emitTopology("tab.created")
        awaitUntil { server.requestCount("session.snapshot") == 2 }

        // The timer armed at start would have fired at five seconds. The event fetch re-armed it.
        delay(2_500)
        assertEquals(2, server.requestCount("session.snapshot"))
        awaitUntil(SAMPLE_WAIT_MILLIS) { server.requestCount("session.snapshot") == 3 }
        client.close()
    }

    @Test
    fun `a burst of topology events costs a bounded number of snapshot fetches`() = withScope {
        val server = serverWith("p-1")
        val client = clientFor(server, this)
        client.start()

        repeat(20) { server.emitTopology("tab.created") }
        delay(800)

        // The initial fetch, plus at most two more: the events coalesce, and fetch starts are spaced.
        assertTrue(server.requestCount("session.snapshot") <= 3)
        client.awaitState { it.snapshotFresh }
        client.close()
    }

    @Test
    fun `sampling stops when the connection drops and resumes only after it is re-established`() = withScope {
        val server = serverWith("p-1")
        val client = clientFor(
            server,
            this,
            fastConfig().copy(reconnectBaseDelayMillis = 8_000, reconnectMaxDelayMillis = 8_000),
        )
        client.start()

        server.topologySubscriptions().single().channel.finish()
        client.awaitState { it.phase == HerdrPhase.Reconnecting }
        val snapshotsAtDrop = server.requestCount("session.snapshot")
        delay(5_500)

        // The reconnect backoff is longer than the window, so no sample may happen inside it.
        assertEquals(snapshotsAtDrop, server.requestCount("session.snapshot"))
        val state = client.state.value
        assertFalse(state.agentStatusFresh)
        assertNotNull(state.agentStatusSampledAtMillis)
        assertEquals(HerdrAgentStatus.Idle, state.snapshot?.panes?.single()?.agentStatus)
        client.close()
    }

    @Test
    fun `close stops sampling`() = withScope {
        val server = serverWith("p-1")
        val client = clientFor(server, this)
        client.start()

        client.close()
        val snapshotsAtClose = server.requestCount("session.snapshot")
        delay(5_500)

        assertEquals(snapshotsAtClose, server.requestCount("session.snapshot"))
        assertEquals(HerdrPhase.Closed, client.state.value.phase)
        assertFalse(client.state.value.agentStatusFresh)
    }

    @Test
    fun `a failed sample reconnects, and sampling resumes once the server answers again`() = withScope {
        val server = serverWith("p-1")
        val client = clientFor(
            server,
            this,
            fastConfig().copy(reconnectBaseDelayMillis = 300, reconnectMaxDelayMillis = 300),
        )
        client.start()
        val firstSampleAt = client.state.value.agentStatusSampledAtMillis

        server.replies["session.snapshot"] = { id -> errorReply(id, "internal_error", "boom") }
        client.awaitState(SAMPLE_WAIT_MILLIS) { it.phase == HerdrPhase.Reconnecting }
        server.replies.remove("session.snapshot")

        val state = client.awaitState(SAMPLE_WAIT_MILLIS) { it.phase == HerdrPhase.Live && it.agentStatusFresh }
        assertTrue(state.agentStatusSampledAtMillis!! >= firstSampleAt!!)
        assertEquals(2, server.topologyOpened())
        client.close()
    }

    // ---- Topology --------------------------------------------------------------------------------

    @Test
    fun `a pane added or removed reaches the snapshot through its topology event`() = withScope {
        val server = serverWith("p-1")
        val client = clientFor(server, this)
        client.start()

        server.addPane(FakePane("p-2"))
        client.awaitState { it.snapshot?.panes?.map { pane -> pane.id } == listOf("p-1", "p-2") }

        server.removePane("p-1")
        client.awaitState { it.snapshot?.panes?.map { pane -> pane.id } == listOf("p-2") }
        client.close()
    }

    @Test
    fun `an optional event kind the server rejects is dropped and reported`() = withScope {
        val server = serverWith()
        server.rejectedEvents += "workspace.reordered"
        val client = clientFor(server, this)

        client.start()

        assertEquals(setOf("workspace.reordered"), client.state.value.optionalEventsUnsupported)
        assertFalse("workspace.reordered" in server.topologySubscriptions().single().kinds)
        assertEquals(HerdrPhase.Live, client.state.value.phase)
        client.close()
    }

    @Test
    fun `an essential event kind the server rejects is a terminal Unsupported state`() = withScope {
        val server = serverWith()
        server.rejectedEvents += "tab.created"
        val client = clientFor(server, this)

        client.start()
        val state = client.awaitState { it.phase == HerdrPhase.Unsupported }

        assertEquals(HerdrErrorKind.Unsupported, state.error?.kind)
        assertTrue(state.error?.message.orEmpty().contains("tab.created"))
        client.close()
    }

    @Test
    fun `fragmented replies and events decode with multibyte labels intact`() = withScope {
        val server = serverWith("p-1")
        server.fragmentSize = 1
        val client = clientFor(server, this)

        client.start()
        assertEquals("é🚀", client.state.value.snapshot?.panes?.single()?.label)

        val before = server.requestCount("session.snapshot")
        server.emitTopology("tab.created")

        awaitUntil { server.requestCount("session.snapshot") > before }
        client.awaitState { it.snapshotFresh }
        client.close()
    }

    @Test
    fun `an event during a snapshot fetch causes exactly one more fetch`() = withScope {
        val server = serverWith()
        val gate = CompletableDeferred<Unit>()
        server.gateNextSnapshot(gate)
        val client = clientFor(server, this)

        val starting = launch { client.start() }
        awaitUntil { server.requestCount("session.snapshot") == 1 }
        server.emitTopology("tab.created")
        gate.complete(Unit)
        starting.join()

        client.awaitState { it.snapshotFresh && server.requestCount("session.snapshot") == 2 }
        assertEquals(HerdrPhase.Live, client.state.value.phase)
        client.close()
    }

    // ---- Reconnects ------------------------------------------------------------------------------

    @Test
    fun `events_lost on the topology stream resubscribes and bootstraps again`() = withScope {
        val server = serverWith()
        val client = clientFor(server, this)
        client.start()

        server.topologySubscriptions().single().channel.feed("${errorReply("sub", "events_lost", "fell behind")}\n")

        awaitUntil { server.topologyOpened() == 2 }
        client.awaitState { it.phase == HerdrPhase.Live && it.snapshotFresh }
        assertTrue(server.subscriptions.first().channel.closed)
        client.close()
    }

    @Test
    fun `EOF on the topology stream reconnects`() = withScope {
        val server = serverWith()
        val client = clientFor(server, this)
        client.start()

        server.topologySubscriptions().single().channel.finish()

        awaitUntil { server.topologyOpened() == 2 }
        client.awaitState { it.phase == HerdrPhase.Live && it.snapshotFresh }
        client.close()
    }

    @Test
    fun `malformed event line reconnects`() = withScope {
        val server = serverWith()
        val client = clientFor(server, this)
        client.start()

        server.topologySubscriptions().single().channel.feed("not json\n")

        awaitUntil { server.topologyOpened() == 2 }
        client.awaitState { it.phase == HerdrPhase.Live && it.snapshotFresh }
        client.close()
    }

    @Test
    fun `oversized event line fails the connection instead of buffering forever`() = withScope {
        val server = serverWith()
        val client = clientFor(server, this, fastConfig().copy(maxLineBytes = 4_096))
        client.start()

        server.topologySubscriptions().single().channel.feed("x".repeat(5_000))

        awaitUntil { server.topologyOpened() == 2 }
        client.awaitState { it.phase == HerdrPhase.Live && it.snapshotFresh }
        client.close()
    }

    @Test
    fun `a failed resubscribe keeps the last-known snapshot and reports it not fresh`() = withScope {
        val server = serverWith("p-1")
        val client = clientFor(server, this)
        client.start()
        server.replies["events.subscribe"] = { id -> errorReply(id, "internal_error", "boom") }

        server.topologySubscriptions().single().channel.feed("${errorReply("sub", "events_lost", "fell behind")}\n")

        val state = client.awaitState {
            it.phase == HerdrPhase.Reconnecting && it.error?.serverCode == "internal_error"
        }
        assertEquals(HerdrAgentStatus.Idle, state.snapshot?.panes?.single()?.agentStatus)
        assertNotNull(state.agentStatusSampledAtMillis)
        assertFalse(state.snapshotFresh)
        assertFalse(state.agentStatusFresh)
        client.close()
    }

    @Test
    fun `an unsupported ping is tolerated and leaves server info empty`() = withScope {
        val server = serverWith("p-1")
        server.replies["ping"] = { id -> unknownVariantReply(id, "ping") }
        val client = clientFor(server, this)

        client.start()

        assertTrue(client.state.value.agentStatusFresh)
        assertNull(client.state.value.server)
        client.close()
    }

    @Test
    fun `an unsupported snapshot is terminal, closes the topology subscription, and does not sample`() = withScope {
        val server = serverWith()
        server.replies["session.snapshot"] = { id -> unknownVariantReply(id, "session.snapshot") }
        val client = clientFor(server, this)

        client.start()

        assertEquals(HerdrPhase.Unsupported, client.state.value.phase)
        awaitClosed(server.subscriptions.single().channel)
        delay(300)
        assertEquals(1, server.requestCount("session.snapshot"))
        client.close()
    }

    @Test
    fun `reconnects are bounded and end in Failed`() = withScope {
        val server = serverWith()
        server.replies["events.subscribe"] = { id -> errorReply(id, "internal_error", "boom") }
        val client = clientFor(server, this, fastConfig().copy(maxReconnectAttempts = 2))

        client.start()

        client.awaitState { it.phase == HerdrPhase.Failed }
        assertEquals(3, server.requestCount("events.subscribe"))
        client.close()
    }

    @Test
    fun `refused forwarding during start ends in Failed within the reconnect bound`() = withScope {
        val server = serverWith()
        server.openFailure = SshStreamChannelException(SshStreamChannelException.Reason.DENIED, "denied")
        val client = clientFor(server, this, fastConfig().copy(maxReconnectAttempts = 1))

        client.start()

        assertEquals(HerdrErrorKind.Transport, client.state.value.error?.kind)
        client.awaitState { it.phase == HerdrPhase.Failed }
        client.close()
    }

    // ---- Ordinary requests -----------------------------------------------------------------------

    @Test
    fun `request returns the result and closes its own channel`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { id -> okPaneReply(id) }
        val client = clientFor(server, this)

        val result = client.request("pane.get", JsonObject(mapOf("pane_id" to JsonPrimitive("p-1"))))

        assertEquals("pane_info", result["type"]?.jsonPrimitive?.content)
        assertTrue(server.channels.last().closed)
        client.close()
    }

    @Test
    fun `request surfaces the server error code`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { id -> errorReply(id, "pane_not_found", "no such pane") }
        val client = clientFor(server, this)

        val error = expectFailure<HerdrException> { client.request("pane.get") }

        assertEquals(HerdrErrorKind.Rejected, error.error.kind)
        assertEquals("pane_not_found", error.error.serverCode)
        client.close()
    }

    @Test
    fun `request with another response id is a protocol error`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { _ -> pongReply("someone-else") }
        val client = clientFor(server, this)

        val error = expectFailure<HerdrException> { client.request("pane.get") }

        assertEquals(HerdrErrorKind.Protocol, error.error.kind)
        client.close()
    }

    @Test
    fun `a silent server times out the request`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { _ -> null }
        val client = clientFor(server, this, fastConfig().copy(requestTimeoutMillis = 50))

        val error = expectFailure<HerdrException> { client.request("pane.get") }

        assertEquals(HerdrErrorKind.Timeout, error.error.kind)
        client.close()
    }

    @Test
    fun `an unknown method on a request is unsupported`() = withScope {
        val server = FakeHerdrServer()
        val client = clientFor(server, this)

        val error = expectFailure<HerdrException> { client.request("nope") }

        assertEquals(HerdrErrorKind.Unsupported, error.error.kind)
        client.close()
    }

    @Test
    fun `refused forwarding surfaces as a transport error with the transport message`() = withScope {
        val server = FakeHerdrServer()
        server.openFailure = SshStreamChannelException(
            SshStreamChannelException.Reason.DENIED,
            "streamlocal forwarding denied by sshd",
        )
        val client = clientFor(server, this)

        val error = expectFailure<HerdrException> { client.request("ping") }

        assertEquals(HerdrErrorKind.Transport, error.error.kind)
        assertEquals("streamlocal forwarding denied by sshd", error.error.message)
        client.close()
    }

    @Test
    fun `an SSH disconnect during a request is reported as Disconnected`() = withScope {
        val server = FakeHerdrServer()
        server.openFailure = SshStreamChannelException(SshStreamChannelException.Reason.DISCONNECTED, "ssh dropped")
        val client = clientFor(server, this)

        val error = expectFailure<HerdrException> { client.request("ping") }

        assertEquals(HerdrErrorKind.Disconnected, error.error.kind)
        client.close()
    }

    @Test
    fun `at most two ordinary requests hold channels at once`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { id -> okPaneReply(id) }
        val gate = CompletableDeferred<Unit>()
        server.openGate = gate
        val client = clientFor(server, this)

        val calls = List(3) { async { client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT) } }
        awaitUntil { server.openAttempts.get() == 2 }
        delay(50)
        assertEquals(2, server.openAttempts.get())

        gate.complete(Unit)
        calls.awaitAll()
        assertEquals(3, server.openAttempts.get())
        client.close()
    }

    @Test
    fun `the request deadline covers the wait for a permit`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { id -> okPaneReply(id) }
        val gate = CompletableDeferred<Unit>()
        server.openGate = gate
        val client = clientFor(server, this)

        val holders = List(2) { async { client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT) } }
        awaitUntil { server.openAttempts.get() == 2 }

        val error = expectFailure<HerdrException> { client.requestWithin("pane.get", EMPTY_PARAMS, 200) }

        assertEquals(HerdrErrorKind.Timeout, error.error.kind)
        assertEquals(2, server.openAttempts.get())
        gate.complete(Unit)
        holders.awaitAll()
        client.close()
    }

    @Test
    fun `the request deadline covers a channel open that never completes, and frees the permit`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { id -> okPaneReply(id) }
        server.openGate = CompletableDeferred()
        val client = clientFor(server, this, fastConfig().copy(maxConcurrentRequests = 1))

        val error = expectFailure<HerdrException> { client.requestWithin("pane.get", EMPTY_PARAMS, 200) }
        assertEquals(HerdrErrorKind.Timeout, error.error.kind)
        assertEquals(1, server.openAttempts.get())

        server.openGate = null
        client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT)
        assertEquals(2, server.openAttempts.get())
        client.close()
    }

    @Test
    fun `cancelling a request that waits for a permit never opens its channel`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { id -> okPaneReply(id) }
        val gate = CompletableDeferred<Unit>()
        server.openGate = gate
        val client = clientFor(server, this)

        val holders = List(2) { async { client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT) } }
        awaitUntil { server.openAttempts.get() == 2 }
        val waiting = launch { client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT) }
        delay(50)
        waiting.cancelAndJoin()

        gate.complete(Unit)
        holders.awaitAll()
        server.openGate = null
        client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT)
        assertEquals(3, server.openAttempts.get())
        client.close()
    }

    @Test
    fun `cancelling a request mid-read closes its channel and frees its permit`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { _ -> null }
        val client = clientFor(server, this, fastConfig().copy(maxConcurrentRequests = 1))

        val job = launch { client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT) }
        awaitUntil { server.requestCount("pane.get") == 1 }
        job.cancelAndJoin()
        awaitClosed(server.channels.last())

        server.replies["pane.get"] = { id -> okPaneReply(id) }
        client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT)
        client.close()
    }

    // ---- Close and start races -------------------------------------------------------------------

    @Test
    fun `close cancels an in-flight request and closes its channel`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { _ -> null }
        val client = clientFor(server, this)

        val outcome = async {
            runCatching { client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT) }.exceptionOrNull()
        }
        awaitUntil { server.requestCount("pane.get") == 1 }
        client.close()

        val error = outcome.await() as HerdrException
        assertEquals(HerdrErrorKind.Transport, error.error.kind)
        assertTrue(error.error.message.contains("closed"))
        awaitClosed(server.channels.last())
    }

    @Test
    fun `a channel that opens after close is closed on arrival`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { id -> okPaneReply(id) }
        val gate = CompletableDeferred<Unit>()
        server.openGate = gate
        // The open keeps running after close, so it completes and produces a channel nobody may use.
        server.openGateIgnoresCancel = true
        val client = clientFor(server, this)

        val outcome = async {
            runCatching { client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT) }.exceptionOrNull()
        }
        awaitUntil { server.openAttempts.get() == 1 }
        client.close()
        gate.complete(Unit)

        val error = outcome.await() as HerdrException
        assertEquals(HerdrErrorKind.Transport, error.error.kind)
        awaitUntil { server.channels.isNotEmpty() }
        awaitClosed(server.channels.last())
    }

    @Test
    fun `close cancels an open that is still waiting, so no channel is ever created`() = withScope {
        val server = FakeHerdrServer()
        server.replies["pane.get"] = { id -> okPaneReply(id) }
        server.openGate = CompletableDeferred()
        val client = clientFor(server, this)

        val outcome = async {
            runCatching { client.requestWithin("pane.get", EMPTY_PARAMS, LONG_TIMEOUT) }.exceptionOrNull()
        }
        awaitUntil { server.openAttempts.get() == 1 }
        client.close()

        val error = outcome.await() as HerdrException
        assertEquals(HerdrErrorKind.Transport, error.error.kind)
        assertTrue(error.error.message.contains("closed"))
        delay(100)
        assertTrue(server.channels.isEmpty())
    }

    @Test
    fun `close stops the supervisor, closes the topology subscription, and rejects later calls`() = withScope {
        val server = serverWith("p-1")
        val client = clientFor(server, this)
        client.start()
        val topology = server.topologySubscriptions().single()

        client.close()

        assertEquals(HerdrPhase.Closed, client.state.value.phase)
        awaitClosed(topology.channel)
        val error = expectFailure<HerdrException> { client.request("ping") }
        assertEquals(HerdrErrorKind.Transport, error.error.kind)
    }

    @Test
    fun `start after close is rejected`() = withScope {
        val server = FakeHerdrServer()
        val client = clientFor(server, this)
        client.close()

        expectFailure<HerdrException> { client.start() }
    }

    @Test
    fun `concurrent starts share one supervisor`() = withScope {
        val server = FakeHerdrServer()
        val client = clientFor(server, this)

        List(10) { launch { client.start() } }.joinAll()

        assertEquals(HerdrPhase.Live, client.state.value.phase)
        assertEquals(1, server.requestCount("ping"))
        assertEquals(1, server.topologyOpened())
        client.close()
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private fun withScope(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            scope.block()
        } finally {
            scope.cancel()
        }
    }

    private fun serverWith(vararg paneIds: String) = FakeHerdrServer().also { server ->
        paneIds.forEach { server.panes += FakePane(it) }
    }

    private fun clientFor(
        server: FakeHerdrServer,
        scope: CoroutineScope,
        config: HerdrClientConfig = fastConfig(),
    ) = HerdrControlClient(FakeHerdrSession(server), SOCKET_PATH, scope, config)

    private fun fastConfig() = HerdrClientConfig(
        requestTimeoutMillis = 2_000,
        maxReconnectAttempts = 3,
        reconnectBaseDelayMillis = 1,
        reconnectMaxDelayMillis = 4,
    )

    private suspend fun HerdrControlClient.awaitState(
        timeoutMillis: Long = TIMEOUT_MILLIS,
        predicate: (HerdrState) -> Boolean,
    ): HerdrState = withTimeout(timeoutMillis) { state.first(predicate) }

    private suspend fun awaitUntil(timeoutMillis: Long = TIMEOUT_MILLIS, condition: () -> Boolean) =
        withTimeout(timeoutMillis) {
            while (!condition()) delay(1)
        }

    private suspend fun awaitClosed(channel: FakeStreamChannel) = awaitUntil { channel.closed }

    private suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("expected ${T::class.simpleName}")
    }

    private fun okPaneReply(id: String) = """{"id":"$id","result":{"type":"pane_info","pane":{}}}"""

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L

        /** Long enough to cover one sample interval plus the request that answers it. */
        const val SAMPLE_WAIT_MILLIS = 7_000L
        const val LONG_TIMEOUT = 10_000L
        const val SOCKET_PATH = "/run/user/501/herdr.sock"
        val EMPTY_PARAMS = JsonObject(emptyMap())
    }
}
