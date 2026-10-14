package com.mrndtvndv.term.data.ssh.native

import com.mrndtvndv.term.domain.*
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
    private var activeSftpClient: NativeSftpClient? = null
    private val shells = mutableSetOf<NativeSshShellChannel>()

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
    ): SshShellChannel = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val sessionHandle = nativeSessionHandle
            check(sessionHandle != 0L) { "SSH session is not connected" }
            val shellHandle = GhosttyNative.nativeSshOpenShell(sessionHandle, termType, cols, rows, command)
            if (shellHandle == 0L) throw IOException("Failed to open SSH shell channel")
            NativeSshShellChannel(this@NativeSshSession, shellHandle).also { shells += it }
        }
    }

    override suspend fun openSftpClient(): SftpClient = synchronized(lock) {
        val handle = nativeSessionHandle
        if (handle == 0L) throw IllegalStateException("Not connected")
        activeSftpClient?.close()
        val sftpHandle = GhosttyNative.nativeSftpInit(handle)
        if (sftpHandle == 0L) throw IOException("Failed to initialize native SFTP subsystem")
        val client = NativeSftpClient(sftpHandle, handle)
        activeSftpClient = client
        client
    }

    override suspend fun execCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                val handle = nativeSessionHandle
                check(handle != 0L) { "SSH session is not connected" }
                checkNotNull(GhosttyNative.nativeSshExec(handle, command)) {
                    "SSH command execution failed"
                }
            }
        }
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
