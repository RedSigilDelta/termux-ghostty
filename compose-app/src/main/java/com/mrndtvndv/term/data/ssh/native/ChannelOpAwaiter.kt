package com.mrndtvndv.term.data.ssh.native

import com.termux.terminal.GhosttyNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** The native calls [ChannelOpAwaiter] makes. A seam so JVM tests can drive the wait policy without the library. */
internal interface ChannelOpNative {
    /**
     * 1 when done, -2 when failed, 0 while still waiting. With `untilDone` false, 2 once an exec command is
     * running. Mirrors `Java_..._nativeSshOpAwait` in jni_exports.zig.
     */
    fun await(op: Long, timeoutMs: Int, untilDone: Boolean): Int
    fun detail(op: Long): String
    fun release(op: Long)
}

internal object GhosttyChannelOpNative : ChannelOpNative {
    override fun await(op: Long, timeoutMs: Int, untilDone: Boolean): Int =
        GhosttyNative.nativeSshOpAwait(op, timeoutMs, untilDone)

    override fun detail(op: Long): String = GhosttyNative.nativeSshOpDetail(op).orEmpty()

    override fun release(op: Long) = GhosttyNative.nativeSshOpRelease(op)
}

/**
 * Waits for a channel op that the native loop is opening or running.
 *
 * Opening is bounded by [openTimeoutNanos]. That deadline covers the wait for the native open slot, so a
 * queued open cannot stall past it. A running exec command is different: its length belongs to the command,
 * so once it runs only cancellation ends the wait. [take] runs once the op is done. The op is released on
 * every exit path, including failure, timeout, and cancellation, and the native loop cleans up what it held.
 */
internal class ChannelOpAwaiter(
    private val native: ChannelOpNative = GhosttyChannelOpNative,
    private val openTimeoutNanos: Long = DEFAULT_OPEN_TIMEOUT_NANOS,
    private val pollMs: Int = DEFAULT_POLL_MS,
) {
    suspend fun <T> await(op: Long, label: String, take: (Long) -> T): T {
        try {
            val deadline = System.nanoTime() + openTimeoutNanos
            var status = poll(op, untilDone = false)
            while (status == 0) {
                if (System.nanoTime() >= deadline) {
                    throw IOException(
                        "Timed out after ${openTimeoutNanos / 1_000_000_000} s waiting for the $label to open. " +
                            "The open is not cancelled natively: libssh2 keeps it until it completes, " +
                            "and the native loop closes the SSH session if it stays pending for 30 s.",
                    )
                }
                status = poll(op, untilDone = false)
            }
            if (status == 2) {
                // Running: no deadline. Only cancellation or failure ends the wait early.
                do {
                    status = poll(op, untilDone = true)
                } while (status == 0)
            }
            if (status == 1) return withContext(Dispatchers.IO) { take(op) }
            val detail = native.detail(op)
            throw IOException(if (detail.isEmpty()) "The $label failed" else "The $label failed: $detail")
        } finally {
            native.release(op)
        }
    }

    private suspend fun poll(op: Long, untilDone: Boolean): Int {
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO) { native.await(op, pollMs, untilDone) }
    }

    internal companion object {
        const val DEFAULT_OPEN_TIMEOUT_NANOS = 30_000_000_000L
        const val DEFAULT_POLL_MS = 100
    }
}
