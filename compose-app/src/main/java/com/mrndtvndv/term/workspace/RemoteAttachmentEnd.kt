package com.mrndtvndv.term.workspace

import com.termux.terminal.ShellEnd

/**
 * Text for a remote attachment that ended, built only from native end facts. Never parses terminal text.
 * An unknown end stays unknown: it is not reported as a success or as an ordinary process failure.
 */
fun remoteAttachmentEndText(end: ShellEnd?): String {
    if (end == null) return "Attachment ended"
    return when (end.kind) {
        ShellEnd.Kind.EXITED -> "Attachment ended with code ${end.exitCode}"
        ShellEnd.Kind.SIGNALED -> "Attachment ended by signal ${end.signalName}"
        ShellEnd.Kind.TRANSPORT_LOST -> "Connection to the server was lost"
        ShellEnd.Kind.LOCAL_DETACH -> "Detached"
        ShellEnd.Kind.ENDED_UNKNOWN -> "Attachment ended"
    }
}
