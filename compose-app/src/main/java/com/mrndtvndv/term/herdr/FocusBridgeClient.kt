package com.mrndtvndv.term.herdr

import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshStreamChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** What the focus helper controls show for one server. [Done] and [Failed] carry a message for the user. */
sealed interface FocusBridgeUiState {
    data object Idle : FocusBridgeUiState
    data class Working(val stage: String) : FocusBridgeUiState
    data class Done(val message: String) : FocusBridgeUiState
    data class Failed(val message: String) : FocusBridgeUiState
}

/**
 * Runs [block] and returns a failure as a Result. A cancellation is rethrown, so a cancelled caller still stops.
 */
internal inline fun <T> focusResultOf(block: () -> T): Result<T> {
    val result = runCatching { block() }
    val failure = result.exceptionOrNull()
    if (failure is CancellationException) throw failure
    return result
}

/** One handshaked channel to the focus bridge. Closing it leaves the helper process running. */
internal class FocusBridgeConnection(val channel: SshStreamChannel, val reader: HerdrLineReader) {
    var lastUsedNanos = System.nanoTime()
    var supportedMethods: Set<String> = emptySet()

    fun close() = channel.close()
}

/** Opening and framing for the focus bridge's protocol version 1. */
internal object FocusBridgeWire {
    const val PROTOCOL_VERSION = 1
    const val MAX_REPLY_BYTES = 1024 * 1024
    private const val HELLO_ID = "hello"

    /**
     * Opens the bridge socket and completes the handshake. Throws `SshStreamChannelException` with reason UNREACHABLE
     * when nothing listens on the socket. A peer that is not a compatible bridge fails the handshake.
     */
    suspend fun open(session: SshSession, socketPath: String): FocusBridgeConnection {
        val channel = session.openUnixSocketChannel(socketPath)
        val connection = FocusBridgeConnection(channel, HerdrLineReader(channel, MAX_REPLY_BYTES))
        return handshake(connection).onFailure { connection.close() }.getOrThrow()
    }

    fun frame(json: String): ByteArray = (json + "\n").toByteArray(Charsets.UTF_8)

    private suspend fun handshake(connection: FocusBridgeConnection): Result<FocusBridgeConnection> =
        focusResultOf {
            val hello = HerdrWire.encodeRequest(
                HELLO_ID,
                "bridge.hello",
                buildJsonObject { put("protocol", PROTOCOL_VERSION) },
            )
            connection.channel.write(frame(hello))
            val line = connection.reader.readLine()
                ?: throw IOException("the focus bridge closed the connection during the handshake")
            connection.supportedMethods = checkHello(line)
            connection
        }

    private fun checkHello(line: String): Set<String> {
        val result = HerdrWire.expectType(HerdrWire.replyResult(line, HELLO_ID), "bridge_hello")
        val version = (result["protocol"] as? JsonPrimitive)?.intOrNull
        if (version != PROTOCOL_VERSION) {
            throw HerdrException(HerdrError(HerdrErrorKind.Protocol, "the focus bridge speaks protocol $version"))
        }
        val methods = result["methods"] as? JsonArray
            ?: throw HerdrException(HerdrError(HerdrErrorKind.Protocol, "the focus bridge did not list its methods"))
        return methods.map { value ->
            methodName(value)
        }.toSet()
    }

    private fun methodName(value: JsonElement): String =
        (value as? JsonPrimitive)?.contentOrNull
            ?: throw HerdrException(HerdrError(HerdrErrorKind.Protocol, "the focus bridge listed an invalid method"))
}

/**
 * Selected Herdr actions through the focus bridge on the SSH host, for one connection.
 *
 * An action never installs anything. When the helper is off, its socket does not answer, or it does not advertise the
 * method, [tryRequest] returns null with nothing written, and the caller uses the direct Herdr socket. A failed
 * connect holds the bridge off for [FAILURE_HOLD_NANOS], so actions during an outage fall back at once instead of each
 * paying for another attempt. Installing and removing are explicit actions on [installer]. One persistent channel
 * carries this connection's supported requests, one at a time.
 *
 * Once the write begins, the outcome is final: Herdr's result, or a [HerdrException]. There is no retry and no
 * fallback, so an action is never sent twice. A channel that fails after a write is dropped, because its reply stream
 * may no longer line up with the next request.
 */
class FocusBridgeClient(
    private val session: SshSession,
    val installer: FocusBridgeInstaller,
    enabled: Boolean,
) {
    @Volatile
    var enabled: Boolean = enabled
        private set

    // Held for one request. A request waits only for the previous one, never for an install.
    private val lock = Mutex()
    private val requestIds = AtomicLong()

    // Guards [connection], [closed], and [holdOffUntilNanos]. The channel itself is used only under [lock].
    private val state = Any()
    private var connection: FocusBridgeConnection? = null
    private var closed = false
    private var holdOffUntilNanos: Long? = null

    /** Sends one supported Herdr action. [beforeWrite] runs before the first request byte, after connection setup. */
    suspend fun tryRequest(
        method: String,
        params: JsonObject,
        beforeWrite: () -> Unit,
        timing: ((String) -> Unit)? = null,
    ): JsonObject? {
        if (!enabled || method !in BRIDGED_METHODS) return null
        return lock.withLock {
            val current = connectOrNull(timing)
            if (current == null || method !in current.supportedMethods) return@withLock null
            beforeWrite()
            withContext(NonCancellable) { exchange(current, method, params, timing) }
        }
    }

    /** Focuses [paneId] through the bridge, with timing marks for its existing debug trace. */
    suspend fun tryFocusPane(
        paneId: String,
        timing: ((String) -> Unit)?,
        beforeWrite: () -> Unit,
    ): JsonObject? = tryRequest(
        method = METHOD_PANE_FOCUS,
        params = buildJsonObject { put("pane_id", paneId) },
        beforeWrite = beforeWrite,
        timing = timing,
    )

    /** Opens the persistent channel ahead of an action, without sending a request or installing anything. */
    suspend fun warmUp() {
        if (!enabled) return
        lock.withLock { connectOrNull(null) }
    }

    /**
     * Turns the bridge on or off for this connection. Turning it on clears any hold-off from an earlier failure.
     * Turning it off also closes the channel.
     */
    fun markEnabled(value: Boolean) {
        synchronized(state) { holdOffUntilNanos = null }
        enabled = value
        if (!value) closeChannel()
    }

    /**
     * Closes the channel this app holds to the helper. The helper process is not stopped. The next action reconnects if
     * the helper is still on.
     */
    fun closeChannel() {
        val current = synchronized(state) {
            connection.also { connection = null }
        }
        current?.close()
    }

    /** Closes the channel and refuses further bridge requests. Called when the server disconnects. */
    fun close() {
        synchronized(state) { closed = true }
        closeChannel()
    }

    private suspend fun connectOrNull(timing: ((String) -> Unit)?): FocusBridgeConnection? =
        reusable() ?: openFresh(timing)

    private suspend fun openFresh(timing: ((String) -> Unit)?): FocusBridgeConnection? {
        if (synchronized(state) { closed } || heldOff()) return null
        timing?.invoke("bridge_open_started")
        val opened = focusResultOf {
            withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { FocusBridgeWire.open(session, installer.paths.socket) }
        }.getOrNull()
        if (opened == null) {
            timing?.invoke("bridge_open_failed")
            synchronized(state) { holdOffUntilNanos = System.nanoTime() + FAILURE_HOLD_NANOS }
            return null
        }
        timing?.invoke("bridge_open_finished")
        return store(opened)
    }

    private fun heldOff(): Boolean = synchronized(state) {
        holdOffUntilNanos?.let { System.nanoTime() < it } == true
    }

    /** The open channel when it was used recently. The bridge drops idle connections, so an older one is closed. */
    private fun reusable(): FocusBridgeConnection? {
        val stale = synchronized(state) {
            val current = connection ?: return null
            if (System.nanoTime() - current.lastUsedNanos < IDLE_RECONNECT_NANOS) return current
            connection = null
            current
        }
        stale.close()
        return null
    }

    private fun store(fresh: FocusBridgeConnection): FocusBridgeConnection? {
        val accepted = synchronized(state) {
            if (closed) {
                false
            } else {
                connection = fresh
                true
            }
        }
        if (!accepted) {
            fresh.close()
            return null
        }
        return fresh
    }

    /** Writes one request and reads its reply. Runs without cancellation, so a written request always finishes. */
    private suspend fun exchange(
        current: FocusBridgeConnection,
        method: String,
        params: JsonObject,
        timing: ((String) -> Unit)?,
    ): JsonObject {
        val id = "bridge-${requestIds.incrementAndGet()}"
        val result = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { writeAndRead(current, id, method, params, timing) }
                ?: throw HerdrException(HerdrError(HerdrErrorKind.Timeout, "the focus bridge did not answer in time"))
        } catch (error: HerdrException) {
            if (error.error.kind == HerdrErrorKind.Rejected) {
                synchronized(state) { current.lastUsedNanos = System.nanoTime() }
            } else {
                drop(current)
            }
            throw error
        }
        synchronized(state) { current.lastUsedNanos = System.nanoTime() }
        return result
    }

    private suspend fun writeAndRead(
        current: FocusBridgeConnection,
        id: String,
        method: String,
        params: JsonObject,
        timing: ((String) -> Unit)?,
    ): JsonObject {
        try {
            val request = HerdrWire.encodeRequest(id, method, params)
            current.channel.write(FocusBridgeWire.frame(request))
            timing?.invoke("bridge_request_written")
            val line = current.reader.readLine()
                ?: throw HerdrException(
                    HerdrError(HerdrErrorKind.Disconnected, "the focus bridge closed the connection"),
                )
            timing?.invoke("bridge_reply_received")
            validateReplyId(line, id)
            return HerdrWire.replyResult(line, id)
        } catch (error: IOException) {
            throw HerdrException(HerdrError(HerdrErrorKind.Transport, "focus bridge request failed"), error)
        }
    }

    private fun validateReplyId(line: String, id: String) {
        val replyId = (HerdrWire.parseLine(line)["id"] as? JsonPrimitive)?.contentOrNull
        if (replyId != id) {
            throw HerdrException(HerdrError(HerdrErrorKind.Protocol, "the focus bridge reply id did not match"))
        }
    }

    private fun drop(current: FocusBridgeConnection) {
        synchronized(state) {
            if (connection === current) connection = null
        }
        current.close()
    }

    private companion object {
        const val METHOD_PANE_FOCUS = "pane.focus"
        val BRIDGED_METHODS =
            setOf(METHOD_PANE_FOCUS, "pane.close", "pane.split", "tab.create", "workspace.create")

        // Short on purpose: a slow connect is a fallback, so it must not hold up the action for long.
        const val CONNECT_TIMEOUT_MILLIS = 1_000L
        const val FAILURE_HOLD_NANOS = 30 * 1_000_000_000L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val IDLE_RECONNECT_NANOS = 5 * 60 * 1_000_000_000L
    }
}
