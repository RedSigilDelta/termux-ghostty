package com.mrndtvndv.term.service

/** SSH is reported by connection, not by terminal. Local terminals keep their own count. */
internal object SshSessionNotificationFormatter {
    fun formatTitleAndText(
        sshConnectionCount: Int,
        localCount: Int,
        wakeLockHeld: Boolean,
    ): Pair<String, String> {
        if (sshConnectionCount + localCount == 0) {
            val title = if (wakeLockHeld) "Wake Lock Active" else "Terminal Service"
            val text = if (wakeLockHeld) "Wake lock held (no active sessions)" else "No active sessions"
            return Pair(title, text)
        }

        val title = when {
            sshConnectionCount > 0 -> if (sshConnectionCount > 1) "SSH Connections Active" else "SSH Connection Active"
            else -> if (localCount > 1) "Local Sessions Active" else "Local Session Active"
        }

        val sshText = if (sshConnectionCount > 1) {
            "$sshConnectionCount active SSH connections"
        } else {
            "1 active SSH connection"
        }
        val localText = if (localCount > 1) {
            "$localCount local terminal sessions"
        } else {
            "1 local terminal session"
        }

        val baseText = when {
            sshConnectionCount > 0 && localCount > 0 -> "$sshText and $localText"
            sshConnectionCount > 0 -> sshText
            localCount > 1 -> "Maintaining $localCount active local terminal sessions"
            else -> "Maintaining active local terminal session"
        }

        val text = if (wakeLockHeld) "$baseText (wake lock held)" else baseText
        return Pair(title, text)
    }
}
