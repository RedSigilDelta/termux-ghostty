package com.mrndtvndv.term.domain

import java.io.IOException

/**
 * A byte stream forwarded over an SSH connection to a Unix socket on the remote host.
 *
 * [read] and [write] suspend and honor cancellation. [close] is idempotent and never blocks.
 * It wakes a suspended reader, which then returns -1, and a suspended writer, which then throws.
 */
interface SshStreamChannel {
    /** Reads 1 to `buffer.size` bytes in order. Returns -1 at end of stream or after [close]. */
    suspend fun read(buffer: ByteArray): Int

    /**
     * Writes all of [data] in order before returning. Throws [SshStreamChannelException] when the
     * channel is closed or failed. If the write is cancelled or fails after part of [data] was
     * queued, the channel is closed, so the peer never reads a truncated message.
     */
    suspend fun write(data: ByteArray)

    fun close()
}

/** A stream operation failed. [reason] tells callers what to show or retry. */
class SshStreamChannelException(
    val reason: Reason,
    message: String,
) : IOException(message) {
    enum class Reason {
        /** sshd refused the channel. StreamLocal forwarding is disabled or denied for this user. */
        DENIED,

        /** Nothing is listening on the socket path on the remote host. */
        UNREACHABLE,

        /** The SSH connection dropped. */
        DISCONNECTED,

        /** The channel was closed locally. */
        CLOSED,

        /** Any other failure. The message includes libssh2's detail when it has one. */
        FAILED,
    }
}

/** Longest socket path in UTF-8 bytes that the native layer accepts. */
internal const val MaxRemoteSocketPathBytes = 4096

/**
 * Throws [IllegalArgumentException] unless [socketPath] is absolute, non-empty, NUL-free, and
 * within [MaxRemoteSocketPathBytes]. Paths are sent to the server verbatim, so `~` is not expanded.
 */
fun requireRemoteSocketPath(socketPath: String) {
    require(socketPath.isNotEmpty()) { "Socket path is empty" }
    require(socketPath.startsWith('/')) {
        "Socket path must be absolute (the remote shell's ~ is not expanded): $socketPath"
    }
    require('\u0000' !in socketPath) { "Socket path contains a NUL byte" }
    require(socketPath.toByteArray(Charsets.UTF_8).size <= MaxRemoteSocketPathBytes) {
        "Socket path is longer than $MaxRemoteSocketPathBytes bytes"
    }
}
