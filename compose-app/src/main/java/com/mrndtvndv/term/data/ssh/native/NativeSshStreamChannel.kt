package com.mrndtvndv.term.data.ssh.native

import com.mrndtvndv.term.domain.SshStreamChannel
import com.mrndtvndv.term.domain.SshStreamChannelException
import com.mrndtvndv.term.domain.SshStreamChannelException.Reason
import com.termux.terminal.GhosttyNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The native calls behind a stream. The seam lets JVM tests drive [NativeSshStreamChannel]
 * without loading libtermux-ghostty.so. Status codes are documented on [GhosttyNative].
 */
internal interface SshStreamNative {
    fun awaitOpen(handle: Long, timeoutMs: Int): Int
    fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun write(handle: Long, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun flush(handle: Long, timeoutMs: Int): Int
    fun close(handle: Long)
    fun release(handle: Long)
    fun failure(handle: Long): Int
    fun detail(handle: Long): String
}

internal object GhosttySshStreamNative : SshStreamNative {
    override fun awaitOpen(handle: Long, timeoutMs: Int): Int =
        GhosttyNative.nativeSshStreamAwaitOpen(handle, timeoutMs)

    override fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        GhosttyNative.nativeSshStreamRead(handle, buffer, offset, length, timeoutMs)

    override fun write(handle: Long, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int =
        GhosttyNative.nativeSshStreamWrite(handle, data, offset, length, timeoutMs)

    override fun flush(handle: Long, timeoutMs: Int): Int = GhosttyNative.nativeSshStreamFlush(handle, timeoutMs)

    override fun close(handle: Long) = GhosttyNative.nativeSshStreamClose(handle)

    override fun release(handle: Long) = GhosttyNative.nativeSshStreamRelease(handle)

    override fun failure(handle: Long): Int = GhosttyNative.nativeSshStreamFailure(handle)

    override fun detail(handle: Long): String = GhosttyNative.nativeSshStreamDetail(handle).orEmpty()
}

/**
 * A direct Unix-socket channel. The native side owns the bytes and the libssh2 channel. This class
 * makes the calls safe from any thread.
 *
 * Every native call runs inside an in-flight count. [close] marks the channel closed and wakes any
 * blocked call. The native handle is released only after close has run and the last in-flight call
 * has returned, so a call can never reach freed memory. Native waits are bounded by [POLL_MS], so
 * cancellation takes effect within one poll.
 */
class NativeSshStreamChannel internal constructor(
    private val handle: Long,
    private val socketPath: String,
    private val native: SshStreamNative = GhosttySshStreamNative,
    private val onClose: (NativeSshStreamChannel) -> Unit = {},
) : SshStreamChannel {
    private val inFlight = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    // One writer at a time, so a message's bytes are never interleaved with another writer's.
    private val writes = Mutex()

    /**
     * Waits until the channel opens. Throws when sshd refuses it, the path is unreachable, or
     * [OPEN_TIMEOUT_MS] passes.
     */
    internal suspend fun awaitOpen() {
        val deadline = System.nanoTime() + OPEN_TIMEOUT_MS * NANOS_PER_MILLI
        while (true) {
            currentCoroutineContext().ensureActive()
            val status = withContext(Dispatchers.IO) {
                withNative { native.awaitOpen(handle, POLL_MS) } ?: -1
            }
            when (status) {
                1 -> return
                0 -> if (System.nanoTime() >= deadline) throw openTimeoutError()
                else -> throw if (status == -1) closedError() else failureError()
            }
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        require(buffer.isNotEmpty()) { "Read buffer is empty" }
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = withContext(Dispatchers.IO) {
                withNative { native.read(handle, buffer, 0, buffer.size, POLL_MS) } ?: -1
            }
            when {
                count > 0 -> return count
                count == -1 -> return -1
                count == -2 -> throw failureError()
                // Nothing arrived within the poll. Loop, so cancellation is checked again.
            }
        }
    }

    /**
     * Returns once every byte has been written through libssh2, not merely queued natively, so a
     * later [close] cannot discard it. Bounded waits keep cancellation prompt.
     */
    override suspend fun write(data: ByteArray) {
        if (data.isEmpty()) return
        writes.withLock {
            var offset = 0
            var drained = false
            try {
                while (offset < data.size) {
                    currentCoroutineContext().ensureActive()
                    val accepted = withContext(Dispatchers.IO) {
                        withNative { native.write(handle, data, offset, data.size - offset, POLL_MS) } ?: -1
                    }
                    when {
                        accepted >= 0 -> offset += accepted
                        accepted == -1 -> throw closedError()
                        else -> throw failureError()
                    }
                }
                while (!drained) {
                    currentCoroutineContext().ensureActive()
                    val status = withContext(Dispatchers.IO) {
                        withNative { native.flush(handle, POLL_MS) } ?: -1
                    }
                    when (status) {
                        1 -> drained = true
                        0 -> Unit
                        -1 -> throw closedError()
                        else -> throw failureError()
                    }
                }
            } finally {
                // A write that stopped partway (cancelled, failed, or queued but not drained) leaves the
                // peer with a truncated message, so the stream has to end.
                if (!drained && offset > 0) close()
            }
        }
    }

    override fun close() {
        // Count this call as in flight so the release cannot run before the native close does.
        inFlight.incrementAndGet()
        try {
            if (closed.compareAndSet(false, true)) {
                native.close(handle)
                onClose(this)
            }
        } finally {
            leave()
        }
    }

    /** Runs [call] with the handle held in flight. Returns null once the channel is closed. */
    private inline fun <T> withNative(call: () -> T): T? {
        inFlight.incrementAndGet()
        try {
            if (closed.get()) return null
            return call()
        } finally {
            leave()
        }
    }

    private fun leave() {
        if (inFlight.decrementAndGet() == 0 && closed.get()) releaseNative()
    }

    private fun releaseNative() {
        if (released.compareAndSet(false, true)) native.release(handle)
    }

    private fun openTimeoutError() = SshStreamChannelException(
        Reason.FAILED,
        "Timed out after ${OPEN_TIMEOUT_MS / 1000} s opening $socketPath. The open is not cancelled natively: " +
            "libssh2 keeps it until it completes, and the native loop closes the SSH session if it stays pending " +
            "for ${OPEN_TIMEOUT_MS / 1000} s.",
    )

    private fun closedError() = SshStreamChannelException(Reason.CLOSED, "Unix socket channel to $socketPath is closed")

    private fun failureError(): SshStreamChannelException {
        val (code, detail) = withNative { native.failure(handle) to native.detail(handle) }
            ?: return closedError()
        val suffix = if (detail.isEmpty()) "" else " (native detail: $detail)"
        return when (code) {
            FAILURE_DENIED -> SshStreamChannelException(
                Reason.DENIED,
                "The SSH server refused a Unix socket channel to $socketPath$suffix. " +
                    "This is a server policy refusal; check the server's forwarding settings and whether this user " +
                    "may open Unix socket channels.",
            )
            FAILURE_UNREACHABLE -> SshStreamChannelException(
                Reason.UNREACHABLE,
                "Nothing is listening on $socketPath on the remote host$suffix. " +
                    "Start the service that owns that socket and try again.",
            )
            FAILURE_DISCONNECTED -> SshStreamChannelException(
                Reason.DISCONNECTED,
                "The remote side closed the stream or the SSH connection dropped while using $socketPath$suffix",
            )
            else -> SshStreamChannelException(
                Reason.FAILED,
                "Unix socket channel to $socketPath failed$suffix",
            )
        }
    }

    internal companion object {
        /** Longest single native wait. Cancellation and close are noticed within this bound. */
        const val POLL_MS = 100
        const val OPEN_TIMEOUT_MS = 30_000L

        private const val NANOS_PER_MILLI = 1_000_000L

        // Codes from GhosttyNative.nativeSshStreamFailure.
        const val FAILURE_DENIED = 1
        const val FAILURE_UNREACHABLE = 2
        const val FAILURE_DISCONNECTED = 3
    }
}
