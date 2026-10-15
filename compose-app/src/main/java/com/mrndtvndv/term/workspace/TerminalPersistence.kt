package com.mrndtvndv.term.workspace

import kotlinx.serialization.Serializable

/** How a terminal tab's remote shell outlives its SSH channel. */
interface TerminalPersistence {
    /** Binary the server must have for this backend; null when it needs none. */
    val tool: String? get() = null

    /** Command for the shell channel's exec request; null runs the plain login shell. */
    fun startCommand(tab: Tab.Terminal): String?

    /** Ends the tab's remote session when the user closes the tab. */
    suspend fun terminate(tab: Tab.Terminal, exec: suspend (String) -> String)
}

/**
 * Message to show instead of opening the shell when the server lacks [TerminalPersistence.tool];
 * null when it can run. Checked up front so a missing tool fails the tab rather than silently
 * giving up persistence.
 */
suspend fun TerminalPersistence.missingToolMessage(exec: suspend (String) -> String): String? {
    val name = tool ?: return null
    if ("missing: $name" !in exec(posix("command -v $name >/dev/null 2>&1 || echo 'missing: $name'"))) return null
    return "$name isn't installed on this server. Install $name there, or set this server's persistence to None."
}

@Serializable
enum class TerminalPersistenceKind { None, Dtach, Herdr }

fun TerminalPersistenceKind.create(): TerminalPersistence = when (this) {
    TerminalPersistenceKind.None -> NoPersistence
    TerminalPersistenceKind.Dtach -> DtachPersistence
    TerminalPersistenceKind.Herdr -> HerdrPersistence
}

/** POSIX single-quote quoting. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

// sshd runs exec commands through the user's login shell, which may be fish, so wrap everything in sh.
// That shell skips login setup, so Homebrew and similar dirs can be missing from PATH. Append the PATH
// a login shell would have, so tools resolve the same way they do in a terminal.
private fun posix(script: String) = "sh -c " + shellQuote(
    "PATH=\"\$PATH:\$(\"\${SHELL:-/bin/sh}\" -l -c 'printenv PATH' 2>/dev/null)\"; export PATH; $script",
)

object NoPersistence : TerminalPersistence {
    override fun startCommand(tab: Tab.Terminal): String? =
        posix("cd ${shellQuote(tab.cwd)} 2>/dev/null; exec \"\${SHELL:-/bin/sh}\" -l")

    override suspend fun terminate(tab: Tab.Terminal, exec: suspend (String) -> String) = Unit
}

object DtachPersistence : TerminalPersistence {
    private const val DIR = "\$HOME/.cache/ecto/dtach"

    override val tool = "dtach"

    private fun socket(tab: Tab.Terminal) = "\"$DIR/${tab.id}.sock\""

    // -E disables dtach's detach key, -z passes Ctrl-Z through, -r winch redraws on reattach.
    override fun startCommand(tab: Tab.Terminal): String? = posix(
        "mkdir -p \"$DIR\"; cd ${shellQuote(tab.cwd)} 2>/dev/null; " +
            "exec dtach -A ${socket(tab)} -E -z -r winch \"\${SHELL:-/bin/sh}\" -l",
    )

    // "[d]tach" keeps pkill from matching the wrapping sh, whose command line contains the pattern.
    override suspend fun terminate(tab: Tab.Terminal, exec: suspend (String) -> String) {
        runCatching { exec(posix("pkill -f \"[d]tach -A $DIR/${tab.id}.sock\"; rm -f ${socket(tab)}; true")) }
    }
}

/**
 * Runs each tab as a server-owned herdr terminal and attaches to it directly.
 * `~/.cache/ecto/herdr/<tabId>` maps the tab to its herdr terminal id; tabs live in an "Ecto"
 * herdr workspace. The server is started detached when it isn't running. If the terminal can't be
 * created, the tab says so and falls back to a login shell.
 */
object HerdrPersistence : TerminalPersistence {
    override val tool = "herdr"

    override fun startCommand(tab: Tab.Terminal): String? = posix(
        "cd ${shellQuote(tab.cwd)} 2>/dev/null\n" +
        "d=\"\$HOME/.cache/ecto/herdr\"; f=\"\$d/${tab.id}\"; mkdir -p \"\$d\"\n" +
        "field() { tr ',' '\\n' | awk -F'\"' -v k=\"\$1\" '\$2==k{print \$4; exit}'; }\n" +
        "running() { herdr status server 2>/dev/null | grep -q 'status: running'; }\n" +
        "if ! running; then\n" +
        "  if command -v setsid >/dev/null 2>&1; then (setsid herdr server </dev/null >/dev/null 2>&1 &); else " +
        "(trap \"\" HUP; exec herdr server </dev/null >/dev/null 2>&1) & fi\n" +
        "  i=0; while [ \$i -lt 50 ] && ! running; do sleep 0.1; i=\$((i+1)); done\n" +
        "fi\n" +
        "term=\$(cat \"\$f\" 2>/dev/null)\n" +
        "if [ -z \"\$term\" ] || ! herdr pane list 2>/dev/null | grep -q \"\\\"terminal_id\\\":\\\"\$term\\\"\"; " +
        "then\n" +
        "  ws=\$(cat \"\$d/workspace\" 2>/dev/null)\n" +
        "  if [ -z \"\$ws\" ] || ! herdr workspace get \"\$ws\" >/dev/null 2>&1; then\n" +
        "    ws=\$(herdr workspace create --label Ecto --cwd \"\$HOME\" --no-focus 2>/dev/null | field " +
        "workspace_id)\n" +
        "    echo \"\$ws\" > \"\$d/workspace\"\n" +
        "  fi\n" +
        "  term=\$(herdr tab create --workspace \"\$ws\" --cwd \"\$PWD\" --label ecto --no-focus 2>/dev/null | " +
        "field terminal_id)\n" +
        "  [ -n \"\$term\" ] || { echo \"herdr couldn't create a terminal; this shell won't persist\" >&2; " +
        "exec \"\${SHELL:-/bin/sh}\" -l; }\n" +
        "  echo \"\$term\" > \"\$f\"\n" +
        "fi\n" +
        // herdr 0.9 reads a flag before the id as the id, so the id has to come first.
        "exec herdr terminal attach \"\$term\" --takeover",
    )

    override suspend fun terminate(tab: Tab.Terminal, exec: suspend (String) -> String) {
        runCatching {
            exec(
                posix(
                "f=\"\$HOME/.cache/ecto/herdr/${tab.id}\"; term=\$(cat \"\$f\" 2>/dev/null)\n" +
                "if [ -n \"\$term\" ] && command -v herdr >/dev/null 2>&1; then\n" +
                "  pane=\$(herdr pane list 2>/dev/null | tr ',' '\\n' | awk -F'\"' -v t=\"\$term\" " +
                "'\$2==\"pane_id\"{p=\$4} \$2==\"terminal_id\"&&\$4==t{print p; exit}')\n" +
                "  [ -n \"\$pane\" ] && herdr pane close \"\$pane\" >/dev/null 2>&1\n" +
                "fi\n" +
                "rm -f \"\$f\"; true",
                ),
            )
        }
    }
}
