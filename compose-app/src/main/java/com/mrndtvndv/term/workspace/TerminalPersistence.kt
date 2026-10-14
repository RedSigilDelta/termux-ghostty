package com.mrndtvndv.term.workspace

import kotlinx.serialization.Serializable

/** How a terminal tab's remote shell outlives its SSH channel. */
interface TerminalPersistence {
    /** Command for the shell channel's exec request; null runs the plain login shell. */
    fun startCommand(tab: Tab.Terminal): String?

    /** Ends the tab's remote session when the user closes the tab. */
    suspend fun terminate(tab: Tab.Terminal, exec: suspend (String) -> String)
}

@Serializable
enum class TerminalPersistenceKind { None, Dtach }

fun TerminalPersistenceKind.create(): TerminalPersistence = when (this) {
    TerminalPersistenceKind.None -> NoPersistence
    TerminalPersistenceKind.Dtach -> DtachPersistence
}

/** POSIX single-quote quoting. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

// sshd runs exec commands through the user's login shell, which may be fish, so wrap everything in sh.
private fun posix(script: String) = "sh -c " + shellQuote(script)

object NoPersistence : TerminalPersistence {
    override fun startCommand(tab: Tab.Terminal): String? =
        posix("cd ${shellQuote(tab.cwd)} 2>/dev/null; exec \"\${SHELL:-/bin/sh}\" -l")

    override suspend fun terminate(tab: Tab.Terminal, exec: suspend (String) -> String) = Unit
}

object DtachPersistence : TerminalPersistence {
    private const val DIR = "\$HOME/.cache/ecto/dtach"

    private fun socket(tab: Tab.Terminal) = "\"$DIR/${tab.id}.sock\""

    // -E disables dtach's detach key, -z passes Ctrl-Z through, -r winch redraws on reattach.
    override fun startCommand(tab: Tab.Terminal): String? = posix(
        "mkdir -p \"$DIR\"; cd ${shellQuote(tab.cwd)} 2>/dev/null; " +
            "if command -v dtach >/dev/null 2>&1; then exec dtach -A ${socket(tab)} -E -z -r winch " +
            "\"\${SHELL:-/bin/sh}\" -l; else exec \"\${SHELL:-/bin/sh}\" -l; fi",
    )

    // "[d]tach" keeps pkill from matching the wrapping sh, whose command line contains the pattern.
    override suspend fun terminate(tab: Tab.Terminal, exec: suspend (String) -> String) {
        runCatching { exec(posix("pkill -f \"[d]tach -A $DIR/${tab.id}.sock\"; rm -f ${socket(tab)}; true")) }
    }
}
