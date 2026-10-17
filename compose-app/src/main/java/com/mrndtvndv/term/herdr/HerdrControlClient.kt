package com.mrndtvndv.term.herdr

import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshStreamChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Herdr control client for one server connection, scoped to [scope].
 *
 * Channels, all over the same SSH session:
 * - Ordinary requests, at most [HerdrClientConfig.maxConcurrentRequests] at a time, one channel each.
 * - One topology subscription (workspace, tab, pane, layout facts). It is event-driven and cheap upstream.
 * - Status is SAMPLED: while Live, one `session.snapshot` per [STATUS_SAMPLE_INTERVAL_MILLIS] when no
 *   other fetch has just completed. Upstream has no cheap pushed status, because each pane-scoped status
 *   subscription polls its pane about every 100 ms. No per-pane subscriptions are kept.
 *
 * Topology events invalidate, and refetches are coalesced: at most one in flight, and starts at least
 * [MIN_REFRESH_INTERVAL_MILLIS] apart. Every completed fetch refreshes status and re-arms the sample timer.
 */
@Suppress("TooManyFunctions")
class HerdrControlClient(
    private val session: SshSession,
    private val socketPath: String,
    private val scope: CoroutineScope,
    private val config: HerdrClientConfig = HerdrClientConfig(),
) {
    private val lock = Any()
    private val _state = MutableStateFlow(HerdrState())
    val state: StateFlow<HerdrState> = _state.asStateFlow()

    /** Called on the IO scope. Only semantic endpoint notifications carry trustworthy pane provenance. */
    @Volatile internal var onEndpointNotification: ((HerdrEndpointWire.Message.Notification) -> Unit)? = null
    private data class EndpointNotificationRecord(
        val notification: HerdrEndpointWire.Message.Notification,
        val receivedAtNanos: Long,
    )

    private val deliveredEndpointNotifications = ArrayDeque<EndpointNotificationRecord>()

    /** OSC is only deduplicated, never used to infer a pane. Unmatched OSC remains session-scoped. */
    fun isDuplicateEndpointNotification(title: String?, body: String?): Boolean = synchronized(lock) {
        val cutoff = System.nanoTime() - 30_000_000_000L
        while (deliveredEndpointNotifications.firstOrNull()?.receivedAtNanos?.let { it < cutoff } == true) {
            deliveredEndpointNotifications.removeFirst()
        }
        val match = deliveredEndpointNotifications.firstOrNull { record ->
            val notification = record.notification
            (title == notification.title && body == notification.body) ||
                (title.isNullOrEmpty() && body == listOfNotNull(notification.title, notification.body)
                    .joinToString(": "))
        } ?: return@synchronized false
        deliveredEndpointNotifications.remove(match)
        true
    }

    private val requestIds = AtomicLong()
    private val eventGeneration = AtomicLong()
    private val requestPermits = Semaphore(config.maxConcurrentRequests)

    /** Completed by [close]. Wakes every bounded call, including ones waiting for a permit or an open. */
    private val closeSignal = CompletableDeferred<Unit>()

    // Guarded by [lock]. Holders never suspend while holding it.
    private var closed = false
    private var supervisor: Job? = null
    private var connection: Connection? = null
    private val openChannels = HashSet<SshStreamChannel>()

    /**
     * Starts the supervisor unless one is running, then suspends until the first outcome
     * (Live, Reconnecting, Unsupported, Failed or Closed). Concurrent callers share one supervisor.
     */
    suspend fun start() {
        synchronized(lock) {
            if (closed) throw closedError()
            if (supervisor?.isActive != true) {
                publishLocked { it.copy(phase = HerdrPhase.Connecting, error = null) }
                supervisor = scope.launch { supervise() }
            }
        }
        _state.first { it.phase in OUTCOME_PHASES }
    }

    /**
     * One ordinary request on its own channel. [HerdrClientConfig.requestTimeoutMillis] covers the
     * wait for a request permit, the channel open, the write and the reply. Throws [HerdrException].
     */
    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timing: ((String) -> Unit)? = null,
    ): JsonObject = requestWithin(method, params, config.requestTimeoutMillis, timing)

    /** Requests a coalesced snapshot refresh through the existing serialized refresh loop. */
    fun refresh() {
        synchronized(lock) {
            if (closed) return
            connection?.invalidate()
        }
    }

    /**
     * Invalidates through the coalesced refresh loop, then waits for a snapshot whose fetch started after that
     * invalidation. A fetch already in flight and the last-known snapshot kept across a reconnect never satisfy it.
     * While disconnected, the next connection's bootstrap snapshot does. Events after the fetch started may already
     * have made the result stale; callers that act on IDs from it must verify the reply.
     */
    suspend fun awaitFreshSnapshot(
        timeoutMillis: Long = FRESH_SNAPSHOT_TIMEOUT_MILLIS,
        requireCoherent: Boolean = false,
    ): HerdrFreshSnapshot {
        val token = synchronized(lock) {
            if (closed) return HerdrFreshSnapshot.Unavailable(closedError().error)
            // Connected: the generation this invalidation reached. Otherwise the next connection's bootstrap.
            val conn = connection
            if (conn != null) {
                conn.invalidate()
                eventGeneration.get()
            } else {
                eventGeneration.get() + 1
            }
        }
        val state = withTimeoutOrNull(timeoutMillis) {
            _state.first {
                it.phase in TERMINAL_PHASES || (
                    it.snapshot != null && it.snapshotGeneration >= token && (!requireCoherent || it.snapshotFresh)
                )
            }
        } ?: return HerdrFreshSnapshot.Unavailable(
            HerdrError(HerdrErrorKind.Timeout, "Timed out waiting for a fresh Herdr snapshot"),
        )
        val snapshot = state.snapshot
        return if (state.phase in TERMINAL_PHASES || snapshot == null) {
            HerdrFreshSnapshot.Unavailable(
                state.error ?: HerdrError(HerdrErrorKind.Disconnected, "Herdr control is ${state.phase}"),
            )
        } else {
            HerdrFreshSnapshot.Ready(state, snapshot)
        }
    }

    internal suspend fun requestWithin(
        method: String,
        params: JsonObject,
        timeoutMillis: Long,
        timing: ((String) -> Unit)? = null,
    ): JsonObject {
        if (isClosed()) throw closedError()
        val id = nextId()
        timing?.invoke("permit_wait_started")
        return bounded(timeoutMillis) {
            requestPermits.withPermit {
                timing?.invoke("channel_open_started")
                val channel = openChannel()
                timing?.invoke("channel_open_finished")
                try {
                    channel.write(HerdrWire.encodeRequest(id, method, params).toByteArray(Charsets.UTF_8) + NEWLINE)
                    timing?.invoke("request_written")
                    val line = HerdrLineReader(channel, config.maxLineBytes).readLine()
                        ?: throw disconnected("Herdr closed the connection before answering $method")
                    timing?.invoke("reply_received")
                    HerdrWire.replyResult(line, id)
                } finally {
                    releaseChannel(channel)
                }
            }
        }
    }

    /**
     * Stops the supervisor, cancels the sample timer and refetches, and closes every owned channel.
     * Idempotent.
     */
    fun close() {
        val channels = synchronized(lock) {
            if (closed) return
            closed = true
            closeSignal.complete(Unit)
            supervisor?.cancel()
            supervisor = null
            connection = null
            val owned = openChannels.toList()
            openChannels.clear()
            _state.value = _state.value
                .copy(phase = HerdrPhase.Closed, snapshotFresh = false, error = null)
                .withFreshness()
            deliveredEndpointNotifications.clear()
            owned
        }
        channels.forEach { it.close() }
    }

    private suspend fun supervise() {
        var failures = 0
        while (true) {
            publish { it.copy(phase = HerdrPhase.Connecting, snapshotFresh = false) }
            val failure = try {
                mapTransportFailures { connectOnce() }
                HerdrError(HerdrErrorKind.Disconnected, "Herdr event stream ended")
            } catch (error: HerdrException) {
                error.error
            }

            if (failure.kind == HerdrErrorKind.Unsupported) {
                publish { it.copy(phase = HerdrPhase.Unsupported, error = failure, snapshotFresh = false) }
                return
            }

            // Phase is still Live only if this attempt reached Live before it failed.
            if (_state.value.phase == HerdrPhase.Live) failures = 0
            failures++
            if (failures > config.maxReconnectAttempts) {
                publish {
                    it.copy(phase = HerdrPhase.Failed, error = failure, snapshotFresh = false, attempt = failures)
                }
                return
            }
            publish {
                it.copy(phase = HerdrPhase.Reconnecting, error = failure, snapshotFresh = false, attempt = failures)
            }
            delay(backoffMillis(failures, config.reconnectBaseDelayMillis, config.reconnectMaxDelayMillis))
        }
    }

    /** Topology subscription first, then the first snapshot. Returns only by throwing. */
    private suspend fun connectOnce() {
        val server = fetchServerInfo()
        val topology = openTopology()
        try {
            coroutineScope {
                val conn = Connection(this)
                synchronized(lock) {
                    connection = conn
                    publishLocked { it.copy(server = server, optionalEventsUnsupported = topology.optionalUnsupported) }
                }
                conn.invalidate()
                launch { refreshLoop(conn) }
                if (server?.capabilities?.endpointProtocolGeneration == 1L) {
                    launch { watchEndpointStatuses() }
                }
                readStream(topology.subscription.reader) { conn.invalidate() }
            }
        } finally {
            // Sampling stops with the scope (timer is a child of it). Last-known statuses and their sample time stay.
            synchronized(lock) {
                connection = null
                publishLocked {
                    it.copy(
                        phase = if (it.phase == HerdrPhase.Live) HerdrPhase.Connecting else it.phase,
                        snapshotFresh = false,
                    )
                }
            }
            releaseChannel(topology.subscription.channel)
        }
    }

    private suspend fun fetchServerInfo(): HerdrServerInfo? =
        try {
            HerdrWire.decodePong(request("ping"))
        } catch (error: HerdrException) {
            if (error.error.kind == HerdrErrorKind.Unsupported) null else throw error
        }

    /** Subscribes to the essential kinds plus whichever optional kinds the server accepts. */
    private suspend fun openTopology(): Topology {
        val optional = OPTIONAL_TOPOLOGY_EVENTS.toMutableList()
        val rejected = HashSet<String>()
        while (true) {
            try {
                val kinds = ESSENTIAL_TOPOLOGY_EVENTS + optional
                return Topology(openSubscription(kinds.map(::topologySubscription)), rejected.toSet())
            } catch (error: HerdrException) {
                val name = rejectedEventName(error.error) ?: throw error
                if (name !in optional) {
                    throw HerdrException(
                        HerdrError(
                            HerdrErrorKind.Unsupported,
                            "Herdr does not support the '$name' event",
                            error.error.serverCode,
                        ),
                    )
                }
                optional.remove(name)
                rejected.add(name)
            }
        }
    }

    /**
     * Refetches the snapshot for each invalidation. Invalidations are conflated, so a burst costs one fetch.
     * Starts are at least [MIN_REFRESH_INTERVAL_MILLIS] apart. Each completed fetch refreshes the sample
     * time and re-arms the sample timer, so no timer fetch follows a fetch that just completed. Refetches
     * never overlap, because this loop is the only caller of `session.snapshot`.
     */
    private suspend fun refreshLoop(conn: Connection) {
        var lastEndNanos = 0L
        while (true) {
            conn.invalidations.receive()
            if (lastEndNanos != 0L) {
                val waitMillis = MIN_REFRESH_INTERVAL_MILLIS - (System.nanoTime() - lastEndNanos) / NANOS_PER_MILLI
                if (waitMillis > 0) delay(waitMillis)
            }
            synchronized(lock) {
                if (connection !== conn) return
                conn.cancelSampleTimer()
            }
            val generation = eventGeneration.get()
            val snapshot = HerdrWire.decodeSnapshot(request("session.snapshot"))
            lastEndNanos = System.nanoTime()
            synchronized(lock) {
                if (connection !== conn) return
                publishLocked {
                    it.copy(
                        phase = HerdrPhase.Live,
                        snapshot = snapshot,
                        snapshotFresh = generation == eventGeneration.get(),
                        snapshotGeneration = generation,
                        agentStatusSampledAtMillis = System.currentTimeMillis(),
                        error = null,
                        attempt = 0,
                    )
                }
                conn.scheduleSample()
            }
        }
    }

    private suspend fun readStream(reader: HerdrLineReader, onEvent: () -> Unit) {
        while (true) {
            val line = reader.readLine() ?: throw disconnected("Herdr event stream closed")
            HerdrWire.eventOf(line)
            onEvent()
        }
    }

    /** Passive shell endpoint: pushed status changes wake the authoritative JSON snapshot loop. */
    private suspend fun watchEndpointStatuses() {
        val parent = socketPath.substringBeforeLast('/')
        val name = socketPath.substringAfterLast('/').substringBeforeLast('.')
        val clientSocket = "$parent/$name-client.sock"
        while (coroutineContext.isActive) {
            var channel: SshStreamChannel? = null
            try {
                channel = openChannel(clientSocket)
                channel.write(HerdrEndpointWire.hello())
                HerdrEndpointWire.validateWelcome(HerdrEndpointWire.read(channel))
                readEndpointStatuses(channel)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Keep the five-second JSON sample as fallback on old or unavailable endpoint sockets.
            } finally {
                synchronized(lock) { deliveredEndpointNotifications.clear() }
                channel?.let(::releaseChannel)
            }
            delay(30_000)
        }
    }

    private suspend fun readEndpointStatuses(channel: SshStreamChannel) {
        var previous: Map<String, String>? = null
        while (true) {
            val message = HerdrEndpointWire.read(channel) ?: return
            when (message) {
                is HerdrEndpointWire.Message.Notification -> receiveEndpointNotification(message)
                is HerdrEndpointWire.Message.Control -> previous = updateEndpointStatuses(message, previous)
                HerdrEndpointWire.Message.Other -> Unit
            }
        }
    }

    private fun updateEndpointStatuses(
        message: HerdrEndpointWire.Message.Control,
        previous: Map<String, String>?,
    ): Map<String, String>? {
        if (message.kind != "shell.snapshot.v1") return previous
        val statuses = HerdrEndpointWire.statuses(message.data)
        if (previous != null && previous != statuses) refresh()
        return statuses
    }

    private fun receiveEndpointNotification(message: HerdrEndpointWire.Message.Notification) {
        synchronized(lock) {
            if (deliveredEndpointNotifications.size == 32) deliveredEndpointNotifications.removeFirst()
            deliveredEndpointNotifications.addLast(EndpointNotificationRecord(message, System.nanoTime()))
        }
        onEndpointNotification?.invoke(message)
    }

    /** Opens a subscription and waits for its ack. The caller owns the returned channel and must release it. */
    private suspend fun openSubscription(subscriptions: List<JsonObject>): Subscription {
        val id = nextId()
        return bounded(config.requestTimeoutMillis) {
            val channel = openChannel()
            var handedOff = false
            try {
                val params = JsonObject(mapOf("subscriptions" to JsonArray(subscriptions)))
                val request = HerdrWire.encodeRequest(id, "events.subscribe", params)
                channel.write(request.toByteArray(Charsets.UTF_8) + NEWLINE)
                val reader = HerdrLineReader(channel, config.maxLineBytes)
                val line = reader.readLine()
                    ?: throw disconnected("Herdr closed the event subscription before its ack")
                HerdrWire.subscriptionAck(line, id)
                Subscription(channel, reader).also { handedOff = true }
            } finally {
                if (!handedOff) releaseChannel(channel)
            }
        }
    }

    /**
     * Opens a streamlocal channel and registers it with this client. A close that raced the open
     * closes the channel before anyone can use it.
     */
    private suspend fun openChannel(path: String = socketPath): SshStreamChannel {
        val channel = mapTransportFailures { session.openUnixSocketChannel(path) }
        val active = coroutineContext.isActive
        val tracked = synchronized(lock) { !closed && active && openChannels.add(channel) }
        if (!tracked) {
            channel.close()
            coroutineContext.ensureActive()
            throw closedError()
        }
        return channel
    }

    private fun releaseChannel(channel: SshStreamChannel) {
        synchronized(lock) { openChannels.remove(channel) }
        channel.close()
    }

    /**
     * Runs [block] under [timeoutMillis]. [close] cancels the block even while it waits for a permit
     * or a channel open. The caller then gets a closed error, not a timeout. Transport failures map to
     * [HerdrException] inside the work. A cancelled caller always sees its own cancellation.
     */
    private suspend fun <T> bounded(timeoutMillis: Long, block: suspend () -> T): T = supervisorScope {
        val work = async { mapTransportFailures { block() } }
        val closeWatch = launch {
            closeSignal.await()
            work.cancel()
        }
        try {
            withTimeoutOrNull(timeoutMillis) { work.await() }
                ?: failWith(HerdrException(HerdrError(HerdrErrorKind.Timeout, "Herdr request timed out")))
        } catch (error: CancellationException) {
            // Work cancelled while this caller is still active means close(). Otherwise the caller was cancelled.
            failWith(if (coroutineContext.isActive) closedError() else error)
        } catch (error: HerdrException) {
            failWith(if (isClosed()) closedError() else error)
        } finally {
            closeWatch.cancel()
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun nextId(): String = "ecto-${requestIds.incrementAndGet()}"

    private fun publish(transform: (HerdrState) -> HerdrState) = synchronized(lock) { publishLocked(transform) }

    /** Caller holds [lock]. Publishing after close is a no-op, so a late writer cannot resurrect a phase. */
    private fun publishLocked(transform: (HerdrState) -> HerdrState) {
        if (!closed) _state.value = transform(_state.value).withFreshness()
    }

    /** One connection attempt. [invalidations] carries topology events and sample ticks. [lock] guards the timer. */
    private inner class Connection(val scope: CoroutineScope) {
        val invalidations = Channel<Unit>(Channel.CONFLATED)
        private var sampleTimer: Job? = null

        /** Event-driven: marks the snapshot stale and wakes the refresh loop. */
        fun invalidate() {
            eventGeneration.incrementAndGet()
            invalidations.trySend(Unit)
            publish { if (it.snapshotFresh) it.copy(snapshotFresh = false) else it }
        }

        /** Caller holds [lock]. At most one timer exists, and it is armed only while no fetch is in flight. */
        fun scheduleSample() {
            cancelSampleTimer()
            sampleTimer = scope.launch {
                delay(STATUS_SAMPLE_INTERVAL_MILLIS)
                invalidations.trySend(Unit)
            }
        }

        /** Caller holds [lock]. */
        fun cancelSampleTimer() {
            sampleTimer?.cancel()
            sampleTimer = null
        }
    }

    private class Subscription(val channel: SshStreamChannel, val reader: HerdrLineReader)

    private class Topology(val subscription: Subscription, val optionalUnsupported: Set<String>)

    companion object {
        /**
         * Status sampling period while Live. A v1 constant, not a config knob. Status is at most this long
         * stale, plus one request.
         */
        const val STATUS_SAMPLE_INTERVAL_MILLIS = 5_000L

        /** Minimum gap between snapshot fetch starts, so an event burst cannot drive one fetch per event. */
        const val MIN_REFRESH_INTERVAL_MILLIS = 250L

        /** Upper bound for [awaitFreshSnapshot]: one coalescing delay plus a request, with room for a reconnect. */
        const val FRESH_SNAPSHOT_TIMEOUT_MILLIS = 15_000L

        private val TERMINAL_PHASES = setOf(HerdrPhase.Unsupported, HerdrPhase.Failed, HerdrPhase.Closed)
        private val OUTCOME_PHASES = setOf(
            HerdrPhase.Live,
            HerdrPhase.Reconnecting,
            HerdrPhase.Unsupported,
            HerdrPhase.Failed,
            HerdrPhase.Closed,
        )
        private const val NANOS_PER_MILLI = 1_000_000L
        private val NEWLINE = byteArrayOf('\n'.code.toByte())
    }
}

data class HerdrClientConfig(
    val requestTimeoutMillis: Long = 10_000,
    val maxLineBytes: Int = 8 * 1024 * 1024,
    val maxReconnectAttempts: Int = 6,
    val reconnectBaseDelayMillis: Long = 500,
    val reconnectMaxDelayMillis: Long = 30_000,
    /** Ordinary requests in flight per client. Subscription channels are not counted. */
    val maxConcurrentRequests: Int = 2,
) {
    init {
        require(requestTimeoutMillis > 0 && maxLineBytes > 0) { "timeouts and limits must be positive" }
        require(maxConcurrentRequests > 0) { "maxConcurrentRequests must be positive" }
        require(maxReconnectAttempts >= 0) { "maxReconnectAttempts must not be negative" }
        require(reconnectBaseDelayMillis >= 0 && reconnectMaxDelayMillis >= reconnectBaseDelayMillis) {
            "reconnect delays must be non-negative and ordered"
        }
    }
}

private const val MaxBackoffExponent = 20

/** Delay before retry [failures]: exponential from [base], capped at [max]. */
private fun backoffMillis(failures: Int, base: Long, max: Long): Long {
    val exponent = (failures - 1).coerceAtMost(MaxBackoffExponent)
    return (base shl exponent).coerceAtMost(max)
}

/** Throws [error] from a helper, so each caller keeps a single throw site. */
private fun failWith(error: Throwable): Nothing = throw error

/**
 * Converts any failure from the transport into a typed [HerdrException]. The transport can fail in ways the
 * client does not enumerate (native, IO, argument checks), and none may escape the supervisor. Cancellation and
 * typed Herdr errors pass through unchanged.
 */
@Suppress("TooGenericExceptionCaught")
private inline fun <T> mapTransportFailures(block: () -> T): T =
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: HerdrException) {
        throw error
    } catch (error: Exception) {
        failWith(transportFailure(error))
    }
