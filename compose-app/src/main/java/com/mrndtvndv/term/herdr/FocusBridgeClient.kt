package com.mrndtvndv.term.herdr

import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshStreamChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
            checkHello(line)
            connection
        }

    private fun checkHello(line: String) {
        val result = HerdrWire.expectType(HerdrWire.replyResult(line, HELLO_ID), "bridge_hello")
        val version = (result["protocol"] as? JsonPrimitive)?.intOrNull
        if (version != PROTOCOL_VERSION) {
            throw HerdrException(HerdrError(HerdrErrorKind.Protocol, "the focus bridge speaks protocol $version"))
        }
    }
}

/**
 * Pane focus through the focus bridge on the SSH host, for one connection.
 *
 * A focus never installs anything. When the helper is off, or its socket does not answer, [tryFocusPane] returns null
 * with nothing written, and the caller uses the direct Herdr socket. A failed connect holds the bridge off for
 * [FAILURE_HOLD_NANOS], so taps during an outage fall back at once instead of each paying for another attempt.
 * Installing and removing are explicit actions on [installer]. One persistent channel carries this connection's focus
 * requests, one at a time.
 *
 * Once the write begins, the outcome is final: Herdr's result, or a [HerdrException]. There is no retry and no
 * fallback, so a focus is never sent twice. A channel that fails after a write is dropped, because its reply stream may
 * no longer line up with the next request.
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

    /**
     * Focuses [paneId] through the bridge and returns Herdr's result object. Returns null, with nothing written, when
     * the bridge is off or does not answer; the caller then uses the direct socket. [timing] receives stage marks with
     * no IDs or paths, so the bridge's latency can be compared with the direct path's. [beforeWrite] runs once, just
     * before the first byte is written, and may throw to stop the focus, for example when a newer intent supersedes it.
     */
    suspend fun tryFocusPane(
        paneId: String,
        timing: ((String) -> Unit)?,
        beforeWrite: () -> Unit,
    ): JsonObject? {
        if (!enabled) return null
        return lock.withLock {
            val current = connectOrNull(timing) ?: return null
            beforeWrite()
            withContext(NonCancellable) { exchange(current, paneId, timing) }
        }
    }

    /** Opens the persistent channel ahead of a tap, without sending a focus or installing anything. */
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
     * Closes the channel this app holds to the helper. The helper process is not stopped, and the next focus reconnects
     * if the helper is still on.
     */
    fun closeChannel() {
        val current = synchronized(state) {
            connection.also { connection = null }
        }
        current?.close()
    }

    /** Closes the channel and refuses further focus requests. Called when the server disconnects. */
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
        paneId: String,
        timing: ((String) -> Unit)?,
    ): JsonObject {
        val id = "focus-${requestIds.incrementAndGet()}"
        val result = try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { writeAndRead(current, id, paneId, timing) }
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
        paneId: String,
        timing: ((String) -> Unit)?,
    ): JsonObject {
        try {
            val request = HerdrWire.encodeRequest(id, "pane.focus", buildJsonObject { put("pane_id", paneId) })
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
        // Short on purpose: a slow connect is a fallback, so it must not hold up the focus for long.
        const val CONNECT_TIMEOUT_MILLIS = 1_000L
        const val FAILURE_HOLD_NANOS = 30 * 1_000_000_000L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
        const val IDLE_RECONNECT_NANOS = 5 * 60 * 1_000_000_000L
    }
}
