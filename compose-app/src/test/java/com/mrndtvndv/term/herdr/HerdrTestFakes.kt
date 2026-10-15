package com.mrndtvndv.term.herdr

import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SshAuth
import com.mrndtvndv.term.domain.SshConfig
import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshShellChannel
import com.mrndtvndv.term.domain.SshStreamChannel
import com.mrndtvndv.term.domain.SshStreamChannelException
import com.mrndtvndv.term.domain.requireRemoteSocketPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory stream channel. [feed] queues inbound bytes in chunks. Bytes the client writes are
 * split on newlines and handed to [onLine] one request line at a time.
 */
internal class FakeStreamChannel(
    private val onLine: suspend (FakeStreamChannel, String) -> Unit,
) : SshStreamChannel {
    private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
    private var pending = ByteArray(0)
    private val outgoing = StringBuilder()

    @Volatile
    var closed = false
        private set

    fun feed(text: String, chunkSize: Int = Int.MAX_VALUE) = feedBytes(text.toByteArray(Charsets.UTF_8), chunkSize)

    fun feedBytes(bytes: ByteArray, chunkSize: Int = Int.MAX_VALUE) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + chunkSize)
            inbound.trySend(bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    /** Server hang-up: the client sees EOF once the queued bytes are drained. */
    fun finish() {
        inbound.close()
    }

    override suspend fun read(buffer: ByteArray): Int {
        if (pending.isEmpty()) {
            pending = inbound.receiveCatching().getOrNull() ?: return -1
        }
        val count = minOf(buffer.size, pending.size)
        pending.copyInto(buffer, 0, 0, count)
        pending = pending.copyOfRange(count, pending.size)
        return count
    }

    override suspend fun write(data: ByteArray) {
        outgoing.append(String(data, Charsets.UTF_8))
        while (true) {
            val newline = outgoing.indexOf("\n")
            if (newline < 0) break
            val line = outgoing.substring(0, newline)
            outgoing.delete(0, newline + 1)
            onLine(this, line)
        }
    }

    override fun close() {
        closed = true
        inbound.close()
    }
}

/**
 * A pane in the fake server's topology. [status] is what the snapshot reports. [id] is positional upstream and can
 * change; [terminalId] is the stable identity.
 */
internal class FakePane(
    @Volatile var id: String,
    var status: String = "idle",
    val terminalId: String = "t-$id",
    val tabId: String = "tab-1",
)

/** One `events.subscribe` connection. Topology connections have no [statusPanes]. */
internal class FakeSubscription(
    val channel: FakeStreamChannel,
    val kinds: Set<String>,
    val statusPanes: Set<String>,
)

/**
 * Scripted Herdr server with the upstream behaviours the client depends on:
 * - Ordinary requests get one reply line, then the server hangs up.
 * - `events.subscribe` validates every item. A pane-scoped status item probes the pane, and a
 *   missing pane fails the whole subscribe, as upstream's setup probe does.
 * - Events reach only the subscriptions that asked for them.
 */
internal class FakeHerdrServer {
    val panes = CopyOnWriteArrayList<FakePane>()
    val requests = CopyOnWriteArrayList<String>()
    val channels = CopyOnWriteArrayList<FakeStreamChannel>()
    val subscriptions = CopyOnWriteArrayList<FakeSubscription>()

    /** Event kinds the simulated server does not know. Subscribing to one returns an unknown-variant error. */
    val rejectedEvents = CopyOnWriteArraySet<String>()

    /** Replaces the default reply for a method. Returning null means the server stays silent. */
    val replies = mutableMapOf<String, (String) -> String?>()

    /** Chunk size for every server reply and event. 1 makes each byte its own read. */
    @Volatile
    var fragmentSize: Int = Int.MAX_VALUE

    /** When set, every channel open fails with this exception, as sshd refusing the forward would. */
    @Volatile
    var openFailure: SshStreamChannelException? = null

    /** When set, every channel open waits for this gate first. */
    @Volatile
    var openGate: CompletableDeferred<Unit>? = null

    /** When true, the gated open keeps waiting even if the client cancels it, so it can complete after close. */
    @Volatile
    var openGateIgnoresCancel: Boolean = false

    val openAttempts = AtomicInteger()

    /** Every request's method and params, in arrival order. */
    val requestLog = CopyOnWriteArrayList<Pair<String, JsonObject>>()

    /** Server-global focused pane, reported in snapshots and changed by `pane.focus` and `tab.focus`. */
    @Volatile
    var focusedPaneId: String? = null

    /** When true, `pane.focus` acknowledges without changing focus, as a focus Herdr overrides at once would. */
    @Volatile
    var focusAcksWithoutChange: Boolean = false

    /** When set, `pane.focus` acknowledges with this pane's info instead, as a reused positional ID would. */
    @Volatile
    var focusAckPaneOverride: String? = null

    private val replyGates = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Holds the next reply to [method] until [gate] completes. The request is still logged on arrival. */
    fun gateNextReply(method: String, gate: CompletableDeferred<Unit>) {
        replyGates[method] = gate
    }

    /** Params of every request to [method], in arrival order. */
    fun paramsOf(method: String): List<JsonObject> = requestLog.filter { it.first == method }.map { it.second }

    /** Gives a pane a new public ID, keeping its terminal, and announces the move. */
    fun movePane(oldId: String, newId: String) {
        panes.first { it.id == oldId }.id = newId
        if (focusedPaneId == oldId) focusedPaneId = newId
        emitTopology("pane.moved")
    }

    /** The next status probe of this pane finds it gone: the pane leaves the topology and the probe fails. */
    @Volatile
    var probeVanishes: String? = null

    /** Status probes of these panes always fail. The panes stay in the snapshot. */
    val alwaysFailingProbes = CopyOnWriteArraySet<String>()

    @Volatile
    private var snapshotGate: CompletableDeferred<Unit>? = null

    /** Holds the next `session.snapshot` reply until [gate] completes. */
    fun gateNextSnapshot(gate: CompletableDeferred<Unit>) {
        snapshotGate = gate
    }

    fun openChannel(): SshStreamChannel {
        val channel = FakeStreamChannel { ch, line -> handle(ch, line) }
        channels += channel
        return channel
    }

    fun requestCount(method: String): Int = requests.count { it == method }

    /** Topology subscriptions whose channel is still open. */
    fun topologySubscriptions(): List<FakeSubscription> =
        subscriptions.filter { it.statusPanes.isEmpty() && !it.channel.closed }

    /** Status subscriptions whose channel is still open. */
    fun statusSubscriptions(): List<FakeSubscription> =
        subscriptions.filter { it.statusPanes.isNotEmpty() && !it.channel.closed }

    /** Every topology subscription ever acknowledged, so reconnects are countable. */
    fun topologyOpened(): Int = subscriptions.count { it.statusPanes.isEmpty() }

    /** Changes a pane's status and sends the event to every status subscription that covers the pane. */
    fun setStatus(paneId: String, status: String) {
        panes.first { it.id == paneId }.status = status
        val line = """{"event":"pane.agent_status_changed","data":{"type":"pane_agent_status_changed",""" +
            """"pane_id":"$paneId","workspace_id":"ws-1","agent_status":"$status"}}"""
        statusSubscriptions()
            .filter { paneId in it.statusPanes }
            .forEach { it.channel.feed(line + "\n", fragmentSize) }
    }

    fun addPane(pane: FakePane) {
        panes += pane
        emitTopology("pane.created")
    }

    /** Removes a pane and announces it. Status subscriptions for the pane go quiet, as upstream's do. */
    fun removePane(paneId: String) {
        panes.removeIf { it.id == paneId }
        emitTopology("pane.closed")
    }

    fun emitTopology(kind: String, rawLine: String? = null) {
        val line = rawLine ?: """{"event":"$kind","data":{"type":"${kind.replace('.', '_')}"}}"""
        topologySubscriptions()
            .filter { kind in it.kinds }
            .forEach { it.channel.feed(line + "\n", fragmentSize) }
    }

    private suspend fun handle(channel: FakeStreamChannel, line: String) {
        val request = Json.parseToJsonElement(line).jsonObject
        val id = request.getValue("id").jsonPrimitive.content
        val method = request.getValue("method").jsonPrimitive.content
        requests += method
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        requestLog += method to params
        replyGates.remove(method)?.await()

        replies[method]?.let { reply ->
            val text = reply(id) ?: return
            respond(channel, text, closeAfter = true)
            return
        }

        when (method) {
            "ping" -> respond(channel, pongReply(id), closeAfter = true)
            "session.snapshot" -> {
                val gate = snapshotGate
                snapshotGate = null
                gate?.await()
                respond(channel, snapshotLine(id), closeAfter = true)
            }
            "events.subscribe" -> subscribe(channel, id, request.getValue("params").jsonObject)
            "pane.focus" -> respond(channel, focusPane(id, params.getValue("pane_id").jsonPrimitive.content), true)
            "tab.focus" -> respond(channel, focusTab(id, params.getValue("tab_id").jsonPrimitive.content), true)
            else -> respond(channel, unknownVariantReply(id, method), closeAfter = true)
        }
    }

    private fun subscribe(channel: FakeStreamChannel, id: String, params: JsonObject) {
        val items = (params.getValue("subscriptions") as JsonArray).map { it as JsonObject }
        val kinds = HashSet<String>()
        val statusPanes = LinkedHashSet<String>()
        for (item in items) {
            val type = item.getValue("type").jsonPrimitive.content
            if (type in rejectedEvents) {
                respond(channel, unknownVariantReply(id, type), closeAfter = true)
                return
            }
            if (type == AgentStatusEvent) {
                val paneId = item.getValue("pane_id").jsonPrimitive.content
                if (!probe(paneId)) {
                    respond(channel, errorReply(id, "pane_not_found", "no pane $paneId"), closeAfter = true)
                    return
                }
                statusPanes += paneId
            } else {
                kinds += type
            }
        }
        subscriptions += FakeSubscription(channel, kinds, statusPanes)
        respond(channel, ackReply(id), closeAfter = false)
    }

    /** Upstream's setup probe. A missing pane fails the whole subscribe. */
    private fun probe(paneId: String): Boolean {
        if (paneId in alwaysFailingProbes) return false
        if (probeVanishes == paneId) {
            probeVanishes = null
            panes.removeIf { it.id == paneId }
            return false
        }
        return panes.any { it.id == paneId }
    }

    private fun focusPane(id: String, paneId: String): String {
        val pane = panes.firstOrNull { it.id == paneId } ?: return errorReply(id, "pane_not_found", "no pane $paneId")
        if (!focusAcksWithoutChange) focusedPaneId = pane.id
        val acked = focusAckPaneOverride?.let { other -> panes.first { it.id == other } } ?: pane
        return """{"id":"$id","result":{"type":"pane_info","pane":${paneJson(acked)}}}"""
    }

    private fun focusTab(id: String, tabId: String): String {
        val pane = panes.firstOrNull { it.tabId == tabId } ?: return errorReply(id, "tab_not_found", "no tab $tabId")
        focusedPaneId = pane.id
        return """{"id":"$id","result":{"type":"tab_info","tab":{"tab_id":"$tabId","workspace_id":"ws-1",""" +
            """"number":1,"label":"$tabId","focused":true,"pane_count":1,"agent_status":"idle"}}}"""
    }

    private fun paneJson(pane: FakePane): String {
        val label = if (pane.id == "p-1") "é🚀" else pane.id
        return """{"pane_id":"${pane.id}","terminal_id":"${pane.terminalId}","workspace_id":"ws-1",""" +
            """"tab_id":"${pane.tabId}","focused":${pane.id == focusedPaneId},"label":"$label",""" +
            """"agent_status":"${pane.status}","revision":1}"""
    }

    private fun snapshotLine(id: String) =
        """{"id":"$id","result":{"type":"session_snapshot","snapshot":${snapshotJson()}}}"""

    /**
     * One workspace, its tabs, and the current panes. Pane `p-1` carries a multibyte label. Focus fields appear
     * only once a pane is focused.
     */
    private fun snapshotJson(): String {
        val focused = panes.firstOrNull { it.id == focusedPaneId }
        val focusedTab = focused?.tabId ?: "tab-1"
        val tabIds = (listOf("tab-1") + panes.map { it.tabId }).distinct()
        val tabJson = tabIds.mapIndexed { index, tabId ->
            val count = panes.count { it.tabId == tabId }
            """{"tab_id":"$tabId","workspace_id":"ws-1","number":${index + 1},"label":"shell",""" +
                """"focused":${tabId == focusedTab},"pane_count":$count,"agent_status":"idle"}"""
        }.joinToString(",")
        val focusJson = focused?.let {
            """"focused_workspace_id":"ws-1","focused_tab_id":"${it.tabId}","focused_pane_id":"${it.id}","""
        }.orEmpty()
        return """{"version":"0.9.2","protocol":7,$focusJson""" +
            """"workspaces":[{"workspace_id":"ws-1","number":1,"label":"main","focused":true,""" +
            """"pane_count":${panes.size},"tab_count":${tabIds.size},"active_tab_id":"$focusedTab",""" +
            """"agent_status":"idle"}],""" +
            """"tabs":[$tabJson],""" +
            """"panes":[${panes.joinToString(",") { paneJson(it) }}],"layouts":[],"agents":[]}"""
    }

    private fun respond(channel: FakeStreamChannel, line: String, closeAfter: Boolean) {
        require('\n' !in line) { "fake reply must be one NDJSON line: $line" }
        channel.feed(line + "\n", fragmentSize)
        if (closeAfter) channel.finish()
    }
}

internal class FakeHerdrSession(private val server: FakeHerdrServer) : SshSession {
    override val isConnected: StateFlow<Boolean> = MutableStateFlow(true)
    override val isAlive: Boolean = true

    override suspend fun connect(config: SshConfig) = Unit
    override suspend fun authenticate(auth: SshAuth) = Unit
    override suspend fun openShellChannel(termType: String, cols: Int, rows: Int, command: String?): SshShellChannel =
        error("not used by Herdr tests")
    override suspend fun openSftpClient(): SftpClient = error("not used by Herdr tests")
    override suspend fun execCommand(command: String): String = error("not used by Herdr tests")
    override fun disconnect() = Unit

    /** Same path rules as the native session, then the server's scripted refusal or gate. */
    override suspend fun openUnixSocketChannel(socketPath: String): SshStreamChannel {
        requireRemoteSocketPath(socketPath)
        server.openAttempts.incrementAndGet()
        val gate = server.openGate
        if (gate != null) {
            if (server.openGateIgnoresCancel) withContext(NonCancellable) { gate.await() } else gate.await()
        }
        server.openFailure?.let { throw it }
        return server.openChannel()
    }
}

internal fun pongReply(id: String) =
    """{"id":"$id","result":{"type":"pong","version":"0.9.2","protocol":7,"capabilities":{"live_handoff":false,""" +
        """"detached_server_daemon":false,"endpoint_protocol_generation":1,"surface_interest":true,""" +
        """"health_check":true,"ssh_agent_registration":false}}}"""

internal fun ackReply(id: String) = """{"id":"$id","result":{"type":"subscription_started"}}"""

internal fun errorReply(id: String, code: String, message: String) =
    """{"id":"$id","error":{"code":"$code","message":"$message"}}"""

internal fun unknownVariantReply(id: String, name: String) = errorReply(
    id,
    "invalid_request",
    "invalid request: unknown variant `$name`, expected one of `ping`, `session.snapshot`",
)

/** Stand-alone snapshot reply for wire-level tests. The stream tests use [FakeHerdrServer]'s live snapshot. */
internal fun snapshotReply(id: String) =
    """{"id":"$id","result":{"type":"session_snapshot","snapshot":$snapshotLineFixture}}"""

internal const val EventLineFixture: String =
    """{"event":"tab_created","data":{"type":"tab_created","tab":{"tab_id":"tab-3"}}}"""

/** Two tabs, two panes (one with a multibyte label and one unknown agent state), one layout. */
internal const val SnapshotJsonFixture: String = """{
  "version":"0.9.2","protocol":7,
  "focused_workspace_id":"ws-1","focused_tab_id":"tab-1","focused_pane_id":"p-1",
  "workspaces":[{"workspace_id":"ws-1","number":1,"label":"main","focused":true,"pane_count":2,"tab_count":2,
    "active_tab_id":"tab-1","agent_status":"idle","tokens":{"branch":"main"},"future_field":true}],
  "tabs":[
    {"tab_id":"tab-2","workspace_id":"ws-1","number":2,"label":"logs","focused":false,
      "pane_count":1,"agent_status":"done"},
    {"tab_id":"tab-1","workspace_id":"ws-1","number":1,"label":"shell","focused":true,
      "pane_count":1,"agent_status":"idle"}
  ],
  "panes":[
    {"pane_id":"p-1","terminal_id":"t-1","workspace_id":"ws-1","tab_id":"tab-1","focused":true,"cwd":"/home/u/work",
      "label":"é🚀","agent":"claude","agent_status":"working","state_labels":{"working":"Working"},"revision":5,
      "scroll":{"offset_from_bottom":0,"max_offset_from_bottom":10,"viewport_rows":24}},
    {"pane_id":"p-2","terminal_id":"t-2","workspace_id":"ws-1","tab_id":"tab-2","focused":false,
      "agent_status":"from_the_future","revision":1}
  ],
  "layouts":[{"workspace_id":"ws-1","tab_id":"tab-1","zoomed":false,"area":{"x":0,"y":0,"width":80,"height":24},
    "focused_pane_id":"p-1","panes":[{"pane_id":"p-1","focused":true,"rect":{"x":0,"y":0,"width":40,"height":24}}],
    "splits":[{"id":"s-1","direction":"right","ratio":0.5,"rect":{"x":0,"y":0,"width":80,"height":24}}]}],
  "agents":[{"terminal_id":"t-1","pane_id":"p-1","workspace_id":"ws-1","tab_id":"tab-1","focused":true,
    "agent":"claude","agent_status":"working","revision":5}]
}"""

/** Upstream writes one JSON object per line, so the fixture is compacted onto a single line. */
internal val snapshotLineFixture: String = SnapshotJsonFixture.lines().joinToString("") { it.trim() }
