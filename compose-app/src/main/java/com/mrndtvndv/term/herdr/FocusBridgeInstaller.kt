package com.mrndtvndv.term.herdr

import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SshSession
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Where the focus bridge lives on the SSH host. Everything sits in one private directory under the home directory, and
 * file names carry the first 12 hex digits of the embedded build's hash, so two builds never share a binary or socket.
 */
class FocusBridgePaths(homeDir: String, val herdrSocketPath: String) {
    val installDir: String = installDirFor(homeDir)
    val binary: String = "$installDir/bridge-$SHORT_HASH"
    val socket: String = "$installDir/bridge-$SHORT_HASH.sock"
    val log: String = "$installDir/bridge-$SHORT_HASH.log"
    val upload: String = "$installDir/upload-$SHORT_HASH.tmp"

    init {
        // A Unix socket path is limited to 103 bytes on macOS, so a long home directory fails here, not in bind().
        require(socket.toByteArray(Charsets.UTF_8).size <= MAX_SOCKET_PATH_BYTES) {
            "the home directory is too long for the focus helper socket"
        }
    }

    companion object {
        private val SHORT_HASH = FocusBridgeArtifact.SHA256.take(12)
        private const val MAX_SOCKET_PATH_BYTES = 103

        /** The helper directory for [homeDir]. Refuses the root directory, which is what an unknown home reports. */
        fun installDirFor(homeDir: String): String {
            require(homeDir.startsWith("/") && homeDir != "/") { "the home directory is unknown" }
            return "${homeDir.trimEnd('/')}/.config/herdr-focus-bridge"
        }
    }
}

/**
 * Shell check that the helper directory is a real directory, owned by the current user, with mode 0700. Every script
 * that stops or deletes anything runs it first, so a directory that is not ours is never touched. [quotedDir] is
 * already shell-quoted.
 */
internal fun helperDirectoryGuard(quotedDir: String): String = """
    fail() { echo "${'$'}1" >&2; exit 4; }
    [ -d $quotedDir ] && [ ! -L $quotedDir ] || fail 'focus helper directory is missing or a symlink'
    [ "${'$'}(stat -f %u $quotedDir)" = "${'$'}(id -u)" ] || fail 'focus helper directory is not owned by this user'
    [ "${'$'}(stat -f %Lp $quotedDir)" = 700 ] || fail 'focus helper directory is not private'
    """.trimIndent()

/**
 * Installs the focus helper on the SSH host. Runs only from the explicit Install action, never from a focus.
 *
 * The binary is uploaded only when the remote copy's hash differs. The helper then starts in the background, and the
 * install succeeds only once the helper answers its handshake. The helper runs only on macOS arm64; any other host is
 * refused before anything is uploaded. Stopping is [FocusBridgeMaintenance]'s job.
 */
class FocusBridgeInstaller(
    private val session: SshSession,
    private val sftp: SftpClient,
    val paths: FocusBridgePaths,
    private val openAsset: () -> InputStream,
    private val cacheDir: File,
) {
    /**
    * Installs and starts the helper, reporting each stage through [onStage]. Failures come back as a failed Result.
     */
    suspend fun install(onStage: (String) -> Unit): Result<Unit> = focusResultOf {
        onStage("Checking host")
        val state = session.execCommand(prepareScript()).trim()
        if (state != "present") {
            onStage("Uploading helper")
            val size = FocusBridgeArtifact.SIZE_BYTES
            sftp.uploadFile(stagedBinary(), paths.upload) { sent ->
                onStage("Uploading helper ${(sent * 100 / size).coerceIn(0L, 100L)}%")
            }
            onStage("Installing helper")
            session.execCommand(installScript())
        }
        onStage("Starting helper")
        session.execCommand(startScript())
        onStage("Checking helper")
        awaitAnswering()
    }

    /** The embedded binary, copied into the app cache and checked against the build hash before any upload. */
    private fun stagedBinary(): File {
        val file = File(cacheDir, "herdr-focus-bridge-${FocusBridgeArtifact.SHA256.take(12)}")
        val matches = file.isFile && file.length() == FocusBridgeArtifact.SIZE_BYTES &&
            file.sha256() == FocusBridgeArtifact.SHA256
        if (matches) {
            return file
        }
        val partial = File(file.path + ".partial")
        openAsset().use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
        check(partial.length() == FocusBridgeArtifact.SIZE_BYTES && partial.sha256() == FocusBridgeArtifact.SHA256) {
            "the bundled focus helper does not match its build hash"
        }
        check(partial.renameTo(file)) { "could not stage the focus helper in the app cache" }
        return file
    }

    /** Waits briefly for the started helper to answer its handshake. Fails with the log's location if it never does. */
    private suspend fun awaitAnswering() {
        repeat(CHECK_ATTEMPTS) {
            if (focusResultOf { FocusBridgeWire.open(session, paths.socket).close() }.isSuccess) return
            delay(CHECK_RETRY_MILLIS)
        }
        throw IOException("the focus helper did not start; its log is at ${paths.log}")
    }

    /**
     * Prints `present` when the helper directory is private and already holds this build. Refuses any other host, and
     * refuses a directory that is not ours before anything else touches it.
     */
    private fun prepareScript(): String {
        val dir = shellQuote(paths.installDir)
        val binary = shellQuote(paths.binary)
        return """
            set -u
            [ "${'$'}(uname -s)" = Darwin ] && [ "${'$'}(uname -m)" = arm64 ] || {
                echo "focus helper needs macOS arm64; this server reports ${'$'}(uname -s) ${'$'}(uname -m)" >&2
                exit 2
            }
            [ -e $dir ] || mkdir -p -m 700 $dir || exit 3
            ${helperDirectoryGuard(dir)}
            hash="${'$'}(shasum -a 256 $binary 2>/dev/null | cut -d ' ' -f 1)"
            if [ "${'$'}hash" = ${FocusBridgeArtifact.SHA256} ]; then echo present; else echo missing; fi
            """.trimIndent()
    }

    private fun installScript(): String {
        val upload = shellQuote(paths.upload)
        val binary = shellQuote(paths.binary)
        return """
            set -u
            hash="${'$'}(shasum -a 256 $upload | cut -d ' ' -f 1)"
            if [ "${'$'}hash" != ${FocusBridgeArtifact.SHA256} ]; then
                rm -f $upload
                echo 'uploaded focus helper failed its checksum' >&2
                exit 5
            fi
            chmod 700 $upload && mv -f $upload $binary
            """.trimIndent()
    }

    /** Starts the helper detached, with every descriptor redirected so the exec call returns at once. */
    private fun startScript(): String {
        val binary = shellQuote(paths.binary)
        val socket = shellQuote(paths.socket)
        val herdr = shellQuote(paths.herdrSocketPath)
        val log = shellQuote(paths.log)
        return """
            set -u
            [ -x $binary ] || exit 6
            nohup $binary -socket $socket -herdr-socket $herdr </dev/null >>$log 2>&1 &
            """.trimIndent()
    }

    private companion object {
        const val CHECK_ATTEMPTS = 50
        const val CHECK_RETRY_MILLIS = 100L
    }
}

/** Single-quotes [value] for a POSIX shell. The whole script is quoted again by the SSH exec wrapper. */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

internal fun File.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(readBytes())
    .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
