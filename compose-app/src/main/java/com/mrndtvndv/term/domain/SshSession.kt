package com.mrndtvndv.term.domain

import kotlinx.coroutines.flow.StateFlow

interface SshSession {
    val isConnected: StateFlow<Boolean>
    /** False once the connection died; checked from the native side, so it can change without a callback. */
    val isAlive: Boolean
    suspend fun connect(config: SshConfig)
    suspend fun authenticate(auth: SshAuth)
    /** Opens a PTY channel running [command], or the login shell when null. */
    suspend fun openShellChannel(
        termType: String,
        cols: Int,
        rows: Int,
        command: String? = null,
    ): SshShellChannel
    /**
     * Opens a direct channel to the Unix socket at [socketPath] on the remote host, over this connection.
     * Throws [SshStreamChannelException] when sshd refuses the channel or nothing listens on the path.
     * Throws [IllegalArgumentException] for a path that is not absolute (see [requireRemoteSocketPath]).
     */
    suspend fun openUnixSocketChannel(socketPath: String): SshStreamChannel
    suspend fun openSftpClient(): SftpClient
    /** Executes a POSIX shell script through /bin/sh, independent of the user's login shell. */
    suspend fun execCommand(command: String): String
    fun disconnect()
}
