package com.mrndtvndv.term.workspace

/** POSIX single-quote quoting. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

// sshd runs exec commands through the user's login shell, which may be fish, so wrap everything in sh.
// That shell skips login setup, so Homebrew and similar dirs can be missing from PATH. Append the PATH
// a login shell would have, so tools resolve the same way they do in a terminal.
private fun loginScript(script: String): String = "sh -c " + shellQuote(
    "PATH=\"\$PATH:\$(\"\${SHELL:-/bin/sh}\" -l -c 'printenv PATH' 2>/dev/null)\"; export PATH; $script",
)

/** A plain login shell in the tab's directory. Ends with the SSH connection, as it always has. */
fun plainShellCommand(tab: Tab.Terminal): String =
    loginScript("cd ${shellQuote(tab.cwd)} 2>/dev/null; exec \"\${SHELL:-/bin/sh}\" -l")

/**
 * Direct attach to a herdr terminal over a PTY. The attach client finds its socket from HERDR_SOCKET_PATH, so
 * exporting the control socket points both clients at the same server. Never takes over another client: an
 * occupied terminal fails the attach, and the tab reports that it ended.
 */
fun herdrAttachCommand(terminalId: String, controlSocketPath: String): String = loginScript(
    "HERDR_SOCKET_PATH=${shellQuote(controlSocketPath)}; export HERDR_SOCKET_PATH; " +
        "exec herdr terminal attach ${shellQuote(terminalId)}",
)

/**
 * Herdr's normal full UI on the persistent session, on the configured control socket. Unlike [herdrAttachCommand],
 * it launches the normal frontend rather than attaching a single pane or requesting takeover.
 */
fun herdrFullUiCommand(controlSocketPath: String): String = loginScript(
    "HERDR_SOCKET_PATH=${shellQuote(controlSocketPath)}; export HERDR_SOCKET_PATH; exec herdr",
)
