package com.mrndtvndv.term.data.ssh.native

import com.mrndtvndv.term.domain.*
import com.mrndtvndv.term.domain.SshStreamChannelException.Reason
import com.termux.terminal.GhosttyNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Start result from the native layer when the admission limit is reached. Mirrors `start_refused_busy` in
 * jni_exports.zig. A retry can succeed once queued operations drain.
 */
internal const val NativeStartBusy = -1L

/**
 * Maps a native start result to an op handle. 0 means the session is not running, and [NativeStartBusy]
 * means the admission limit is reached. Both throw with an actionable message.
 */
internal fun requireStartedHandle(handle: Long, what: String): Long = when (handle) {
    0L -> throw IOException("Failed to open $what: the SSH session is not running")
    NativeStartBusy -> throw IOException(
        "Too many SSH channel operations are pending on this session; try again shortly",
    )
    else -> handle
}

class NativeSshSession : SshSession {
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected
    override val isAlive: Boolean
        get() {
            val handle = nativeSessionHandle
            return handle != 0L && GhosttyNative.nativeSshIsConnected(handle)
        }

    private var config: SshConfig? = null
    private var socket: Socket? = null
    @Volatile
    var nativeSessionHandle: Long = 0L
        private set

    private val lock = Any()
    private val channelOps = ChannelOpAwaiter()
    private var activeSftpClient: NativeSftpClient? = null
    private val shells = mutableSetOf<NativeSshShellChannel>()
    // Own lock: closing a stream runs on the main thread and must not wait behind a long exec under [lock].
    private val streamLock = Any()
    private val streams = mutableSetOf<NativeSshStreamChannel>()

    override suspend fun connect(config: SshConfig) {
        this.config = config
        withContext(Dispatchers.IO) {
            val s = Socket()
            // SSH carries its own framing; Nagle only adds delayed-ACK latency
            // to interactive round trips. Mirrored in native configureSocketLiveness
            // for the fd, set here too in case the socket outlives the native side.
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(config.host, config.port), config.connectionTimeoutMs)
            socket = s
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun authenticate(auth: SshAuth) {
        withContext(Dispatchers.IO) {
            val s = socket ?: throw IllegalStateException("Not connected")
            val currentConfig = config ?: throw IllegalStateException("Config missing")
            val fd = ParcelFileDescriptor.fromSocket(s).detachFd()
            val isPassword = auth is SshAuth.Password
            val passwordOrKey = when (auth) {
                is SshAuth.Password -> String(auth.password)
                is SshAuth.PublicKey -> auth.privateKeyPem
            }

            synchronized(lock) {
                val handle = try {
                    GhosttyNative.nativeSshInit(fd, currentConfig.username, passwordOrKey, isPassword)
                } catch (e: Throwable) {
                    com.termux.terminal.JNI.close(fd)
                    throw e
                }
                if (handle == 0L) {
                    com.termux.terminal.JNI.close(fd)
                    throw IllegalStateException("Failed to initialize native SSH session")
                }
                nativeSessionHandle = handle
                GhosttyNative.nativeSshStart(handle)
            }
            _isConnected.value = true
        }
    }

    override suspend fun openShellChannel(
        termType: String,
        cols: Int,
        rows: Int,
        command: String?,
    ): SshShellChannel {
        val op = withSessionLockOnIo {
            val sessionHandle = nativeSessionHandle
            check(sessionHandle != 0L) { "SSH session is not connected" }
            requireStartedHandle(
                GhosttyNative.nativeSshStartShell(sessionHandle, termType, cols, rows, command),
                "SSH shell channel",
            )
        }
        return channelOps.await(op, "SSH shell channel") { opHandle ->
            // Take and register under one lock: disconnect() tears the shell list down under it too.
            synchronized(lock) {
                val shellHandle = GhosttyNative.nativeSshOpTakeShell(opHandle)
                if (shellHandle == 0L) throw IOException("The SSH session ended before the shell channel opened")
                NativeSshShellChannel(this@NativeSshSession, shellHandle).also { shells += it }
            }
        }
    }

    override suspend fun openUnixSocketChannel(socketPath: String): SshStreamChannel {
        requireRemoteSocketPath(socketPath)
        // The native open only links the request; [NativeSshStreamChannel.awaitOpen] waits for the result.
        val streamHandle = withSessionLockOnIo {
            val sessionHandle = nativeSessionHandle
            if (sessionHandle == 0L) {
                throw SshStreamChannelException(Reason.DISCONNECTED, "SSH session is not connected")
            }
            GhosttyNative.nativeSshOpenStreamLocal(sessionHandle, socketPath.toByteArray(Charsets.UTF_8))
        }
        if (streamHandle == NativeStartBusy) {
            throw SshStreamChannelException(
                Reason.FAILED,
                "Too many Unix socket channels are opening on this session; try again shortly",
            )
        }
        if (streamHandle == 0L) {
            throw SshStreamChannelException(
                Reason.DISCONNECTED,
                "The SSH session ended before the Unix socket channel to $socketPath could open",
            )
        }
        val channel = NativeSshStreamChannel(streamHandle, socketPath, onClose = ::untrackStream)
        synchronized(streamLock) { streams += channel }
        var opened = false
        try {
            channel.awaitOpen()
            opened = true
        } finally {
            // Also runs on cancellation, so an abandoned open still closes the channel.
            if (!opened) channel.close()
        }
        return channel
    }

    private fun untrackStream(channel: NativeSshStreamChannel) {
        synchronized(streamLock) { streams -= channel }
    }

    override suspend fun openSftpClient(): SftpClient {
        val op = withSessionLockOnIo {
            val handle = nativeSessionHandle
            if (handle == 0L) throw IllegalStateException("Not connected")
            requireStartedHandle(GhosttyNative.nativeSshStartSftp(handle), "the SFTP subsystem")
        }
        return channelOps.await(op, "SFTP subsystem") { opHandle ->
            synchronized(lock) {
                val handle = nativeSessionHandle
                if (handle == 0L) throw IllegalStateException("Not connected")
                val sftpHandle = GhosttyNative.nativeSshOpTakeSftp(opHandle)
                if (sftpHandle == 0L) throw IOException("Failed to initialize native SFTP subsystem")
                activeSftpClient?.close()
                NativeSftpClient(sftpHandle, handle).also { activeSftpClient = it }
            }
        }
    }

    override suspend fun execCommand(command: String): String {
        val op = withSessionLockOnIo {
            val handle = nativeSessionHandle
            check(handle != 0L) { "SSH session is not connected" }
            requireStartedHandle(GhosttyNative.nativeSshStartExec(handle, posixExecCommand(command)), "the SSH command")
        }
        return channelOps.await(op, "SSH command") { opHandle ->
            val exitStatus = GhosttyNative.nativeSshOpExitStatus(opHandle)
            val stdout = GhosttyNative.nativeSshOpOutput(opHandle).orEmpty()
            if (exitStatus > 0) {
                val stderr = GhosttyNative.nativeSshOpErrorOutput(opHandle).orEmpty()
                throw IllegalStateException(commandFailureMessage(exitStatus, stderr, stdout))
            }
            stdout
        }
    }

    /**
     * Runs [block] under [lock] on the IO dispatcher. [disconnect] holds [lock] while the native loop is joined,
     * so a caller on the main thread must not wait for it there.
     */
    private suspend fun <T> withSessionLockOnIo(block: () -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) { block() }
    }

    private fun commandFailureMessage(exitStatus: Int, stderr: String, stdout: String): String {
        val errorDetail = stderr.trim()
        if (errorDetail.isNotEmpty()) return errorDetail
        val outputDetail = stdout.trim()
        if (outputDetail.isNotEmpty()) return outputDetail
        return "Remote command exited with status $exitStatus"
    }


    internal fun writeToShell(shellHandle: Long, data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            if (shellHandle != 0L && nativeSessionHandle != 0L) {
                GhosttyNative.nativeSshWrite(shellHandle, data, offset, length)
            }
        }
    }

    internal fun resizeShell(shellHandle: Long, cols: Int, rows: Int) {
        synchronized(lock) {
            if (shellHandle != 0L && nativeSessionHandle != 0L) {
                GhosttyNative.nativeSshResize(shellHandle, cols, rows)
            }
        }
    }

    internal fun closeShell(shell: NativeSshShellChannel) {
        synchronized(lock) {
            if (!shells.remove(shell)) return
            val shellHandle = shell.handle
            shell.handle = 0L
            val sessionHandle = nativeSessionHandle
            if (sessionHandle != 0L) GhosttyNative.nativeSshCloseShell(sessionHandle, shellHandle)
        }
    }

    override fun disconnect() {
        // Close streams before deinit. Their native close still reaches the loop, which retires them while the
        // session is alive.
        val openStreams = synchronized(streamLock) { streams.toList().also { streams.clear() } }
        openStreams.forEach { it.close() }
        synchronized(lock) {
            activeSftpClient?.close()
            activeSftpClient = null
            // Deinit frees every native shell, so invalidate the Kotlin handles first.
            shells.forEach { it.handle = 0L }
            shells.clear()
            val handle = nativeSessionHandle
            nativeSessionHandle = 0L
            if (handle != 0L) {
                GhosttyNative.nativeSshDeinit(handle)
            }
        }
        try {
            socket?.close()
        } catch (e: Exception) {}
        _isConnected.value = false
    }
}

/**
 * One PTY channel on a [NativeSshSession]. Close the [com.termux.terminal.TerminalSession]
 * bound to [handle] before calling [close]; the native handle is freed there.
 */
class NativeSshShellChannel internal constructor(
    private val session: NativeSshSession,
    handle: Long,
) : SshShellChannel {
    @Volatile
    var handle: Long = handle
        internal set

    override val inputStream: InputStream = object : InputStream() {
        override fun read(): Int = -1
    }

    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            session.writeToShell(handle, b, off, len)
        }
    }

    override fun resizeWindow(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        session.resizeShell(handle, cols, rows)
    }

    override fun close() {
        session.closeShell(this)
    }
}
