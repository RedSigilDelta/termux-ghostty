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
    suspend fun openSftpClient(): SftpClient
    suspend fun execCommand(command: String): String
    fun disconnect()
}
