package com.mrndtvndv.term.workspace

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalPersistenceTest {
    @get:Rule
    val temp = TemporaryFolder()

    // Every persistence command starts by appending the login shell's PATH.
    private val loginPath =
        "PATH=\"\$PATH:\$(\"\${SHELL:-/bin/sh}\" -l -c 'printenv PATH' 2>/dev/null)\"; export PATH; "

    private fun wrapped(script: String) = "sh -c " + shellQuote(loginPath + script)

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test
    fun noPersistenceStartsLoginShellInCwd() {
        val tab = Tab.Terminal(id = "abc", cwd = "/home/u/my dir")

        assertEquals(
            wrapped("cd ${shellQuote("/home/u/my dir")} 2>/dev/null; exec \"\${SHELL:-/bin/sh}\" -l"),
            NoPersistence.startCommand(tab),
        )
    }

    @Test
    fun dtachStartsOrAttachesSocketInCwd() {
        val tab = Tab.Terminal(id = "abc", cwd = "/srv")

        assertEquals(
            wrapped(
                "mkdir -p \"\$HOME/.cache/ecto/dtach\"; cd '/srv' 2>/dev/null; " +
                    "exec dtach -A \"\$HOME/.cache/ecto/dtach/abc.sock\" -E -z -r winch \"\${SHELL:-/bin/sh}\" -l",
            ),
            DtachPersistence.startCommand(tab),
        )
    }

    @Test
    fun dtachQuotesCwdWithSingleQuote() {
        val tab = Tab.Terminal(id = "abc", cwd = "/srv/it's")

        assertEquals(
            wrapped(
                "mkdir -p \"\$HOME/.cache/ecto/dtach\"; cd ${shellQuote("/srv/it's")} 2>/dev/null; " +
                    "exec dtach -A \"\$HOME/.cache/ecto/dtach/abc.sock\" -E -z -r winch \"\${SHELL:-/bin/sh}\" -l",
            ),
            DtachPersistence.startCommand(tab),
        )
    }

    @Test
    fun dtachTerminateKillsAndRemovesSocket() {
        val tab = Tab.Terminal(id = "abc", cwd = "/srv")
        var command: String? = null

        runBlocking {
            DtachPersistence.terminate(tab) { command = it; "" }
        }

        assertEquals(
            wrapped(
                "pkill -f \"[d]tach -A \$HOME/.cache/ecto/dtach/abc.sock\"; " +
                    "rm -f \"\$HOME/.cache/ecto/dtach/abc.sock\"; true",
            ),
            command,
        )
    }

    @Test
    fun missingToolMessageNamesTheToolWhenServerLacksIt() {
        runBlocking {
            val message = DtachPersistence.missingToolMessage { "missing: dtach\n" }

            assertEquals(
                "dtach isn't installed on this server. Install dtach there, or set this server's persistence to None.",
                message,
            )
        }
    }

    @Test
    fun missingToolMessageIsNullWhenToolIsPresent() {
        runBlocking {
            assertNull(DtachPersistence.missingToolMessage { "" })
        }
    }

    @Test
    fun missingToolMessageSkipsBackendsThatNeedNoTool() {
        runBlocking {
            assertNull(NoPersistence.missingToolMessage { error("exec should not run") })
        }
    }

    @Test
    fun toolInLoginShellPathIsFoundFromBareExecEnvironment() {
        val root = fakeLoginShell(toolDir = "tools", loginPath = "/usr/bin:/bin")
        File(root, "tools/dtach").apply { writeText("#!/bin/sh\n"); setExecutable(true) }

        assertNull(runPreflight(root))
    }

    @Test
    fun toolMissingFromLoginShellPathIsReported() {
        val root = fakeLoginShell(toolDir = "tools", loginPath = "/usr/bin:/bin")

        assertNotNull(runPreflight(root))
    }

    @Test
    fun kindCreatesMatchingPersistence() {
        assertSame(NoPersistence, TerminalPersistenceKind.None.create())
        assertSame(DtachPersistence, TerminalPersistenceKind.Dtach.create())
    }

    /** A fake login shell that prints [loginPath] for `printenv PATH`, like `fish -l -c` would. */
    private fun fakeLoginShell(toolDir: String, loginPath: String): File {
        val root = temp.root
        File(root, toolDir).mkdirs()
        File(root, "login-shell").apply {
            writeText("#!/bin/sh\necho \"${root.path}/$toolDir:$loginPath\"\n")
            setExecutable(true)
        }
        return root
    }

    /** Runs the real preflight through `sh` with sshd's bare PATH and the fake login shell. */
    private fun runPreflight(root: File): String? = runBlocking {
        DtachPersistence.missingToolMessage { command ->
            ProcessBuilder("sh", "-c", command)
                .apply {
                    environment()["PATH"] = "/usr/bin:/bin"
                    environment()["SHELL"] = File(root, "login-shell").path
                }
                .start()
                .inputStream.bufferedReader().readText()
        }
    }
}
