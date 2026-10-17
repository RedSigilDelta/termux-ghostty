package com.mrndtvndv.term.herdr

import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshStreamChannelException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * Removes the focus helper from a server. Runs only from an explicit Remove action, and the caller closes its own
 * channel to the helper first.
 *
 * Only the helper's own sockets are stopped, and only after each one completes the protocol handshake as a focus bridge
 * of this version. A socket that answers anything else is left alone. Nothing else on the host is signalled: there is
 * no process-id lookup and no kill. Every script first checks that the helper directory is a real, private directory
 * owned by this user, so a directory that is not ours is never stopped or deleted. Files are deleted only after every
 * socket is gone, and only the helper's own names are removed. The directory goes only when it is empty.
 */
object FocusBridgeMaintenance {
    suspend fun remove(
        session: SshSession,
        homeDir: String,
        onStage: (String) -> Unit,
    ): Result<Unit> = focusResultOf {
        val installDir = FocusBridgePaths.installDirFor(homeDir)
        onStage("Stopping helper")
        for (name in bridgeSocketNames(session, installDir)) {
            stop(session, "$installDir/$name")
        }
        onStage("Removing files")
        session.execCommand(cleanupScript(installDir))
    }

    /**
    * Socket names in the helper directory. A missing directory has none. A directory that fails the guard fails here.
     */
    private suspend fun bridgeSocketNames(session: SshSession, installDir: String): List<String> {
        val dir = shellQuote(installDir)
        val output = session.execCommand(
            """
            set -u
            [ -e $dir ] || exit 0
            ${helperDirectoryGuard(dir)}
            cd $dir || exit 4
            for f in bridge-*.sock; do [ -S "${'$'}f" ] && echo "${'$'}f"; done
            exit 0
            """.trimIndent(),
        )
        return output.lines().map { it.trim() }.filter { it.startsWith("bridge-") && it.endsWith(".sock") }
    }

    private suspend fun stop(session: SshSession, socketPath: String) {
        val connection = try {
            FocusBridgeWire.open(session, socketPath)
        } catch (error: SshStreamChannelException) {
            // Nothing listens on a leftover socket, so there is no process to stop.
            if (error.reason == SshStreamChannelException.Reason.UNREACHABLE) return
            throw error
        }
        try {
            val stopRequest = HerdrWire.encodeRequest(STOP_ID, "bridge.stop", JsonObject(emptyMap()))
            connection.channel.write(FocusBridgeWire.frame(stopRequest))
            val reply = connection.reader.readLine()
                ?: throw IOException("the focus bridge closed the connection before stopping")
            HerdrWire.expectType(HerdrWire.replyResult(reply, STOP_ID), "bridge_stopping")
        } finally {
            connection.close()
        }
        awaitSocketGone(session, socketPath)
    }

    private suspend fun awaitSocketGone(session: SshSession, socketPath: String) {
        repeat(STOP_POLLS) {
            val state = session.execCommand("test -S ${shellQuote(socketPath)} && echo present || echo gone").trim()
            if (state != "present") return
            delay(STOP_POLL_MILLIS)
        }
        throw IOException("the focus bridge did not exit after it was asked to stop")
    }

    /** Deletes only the helper's own files, and removes the directory only if nothing else is left in it. */
    private fun cleanupScript(installDir: String): String {
        val dir = shellQuote(installDir)
        return """
            set -u
            [ -e $dir ] || exit 0
            ${helperDirectoryGuard(dir)}
            ( cd $dir && rm -f -- bridge-* upload-*.tmp ) || exit 4
            rmdir $dir 2>/dev/null || true
            """.trimIndent()
    }

    private const val STOP_ID = "stop"
    private const val STOP_POLLS = 40
    private const val STOP_POLL_MILLIS = 250L
}
