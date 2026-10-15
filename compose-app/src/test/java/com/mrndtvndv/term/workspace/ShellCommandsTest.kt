package com.mrndtvndv.term.workspace

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ShellCommandsTest {
    @get:Rule
    val temp = TemporaryFolder()

    // Every command starts by appending the login shell's PATH, then runs through sh.
    private val loginPath =
        "PATH=\"\$PATH:\$(\"\${SHELL:-/bin/sh}\" -l -c 'printenv PATH' 2>/dev/null)\"; export PATH; "

    private fun wrapped(script: String) = "sh -c " + shellQuote(loginPath + script)

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test
    fun plainShellStartsLoginShellInCwd() {
        val tab = Tab.Terminal(id = "abc", cwd = "/home/u/my dir")

        assertEquals(
            wrapped("cd ${shellQuote("/home/u/my dir")} 2>/dev/null; exec \"\${SHELL:-/bin/sh}\" -l"),
            plainShellCommand(tab),
        )
    }

    @Test
    fun plainShellQuotesCwdWithSingleQuote() {
        val tab = Tab.Terminal(id = "abc", cwd = "/srv/it's")

        assertEquals(
            wrapped("cd ${shellQuote("/srv/it's")} 2>/dev/null; exec \"\${SHELL:-/bin/sh}\" -l"),
            plainShellCommand(tab),
        )
    }

    @Test
    fun attachRunsDirectlyWithoutTakeover() {
        val root = temp.root
        stubHerdr(root)

        val (output, exit) = run(root, herdrAttachCommand("term_x", "/run/herdr.sock"))

        assertEquals(0, exit)
        assertEquals("ATTACHED term_x", output)
        assertEquals("terminal attach term_x", File(root, "log").readLines().last())
    }

    @Test
    fun attachExportsTheControlSocket() {
        val root = temp.root
        stubHerdr(root)

        run(root, herdrAttachCommand("term_x", "/run/herdr.sock"))

        assertEquals("/run/herdr.sock", File(root, "socket").readText().trim())
    }

    @Test
    fun attachFindsHerdrOnTheLoginShellPathOnly() {
        // herdr lives in a directory only the login shell's PATH knows about, as with a Homebrew install.
        val root = temp.root
        stubHerdr(root)
        assertTrue(File(root, "tools/herdr").canExecute())

        val (_, exit) = run(root, herdrAttachCommand("term_x", "/run/herdr.sock"))

        assertEquals(0, exit)
    }

    @Test
    fun attachFailsWhenHerdrIsNowhereOnThePath() {
        val root = temp.root

        val (_, exit) = run(root, herdrAttachCommand("term_x", "/run/herdr.sock"))

        assertTrue("attach must fail when herdr is missing", exit != 0)
    }

    /** A fake login shell that prints the tools directory plus the usual PATH, like `fish -l -c` would. */
    private fun loginShell(root: File): File = File(root, "login-shell").apply {
        writeText("#!/bin/sh\necho \"${root.path}/tools:/usr/bin:/bin\"\n")
        setExecutable(true)
    }

    private fun stubHerdr(root: File) {
        File(root, "tools").mkdirs()
        File(root, "tools/herdr").apply {
            writeText(STUB_HERDR)
            setExecutable(true)
        }
    }

    /**
     * Runs [command] the way sshd does: a bare PATH, with the fake login shell supplying the rest. Returns the
     * output and the exit status.
     */
    private fun run(root: File, command: String): Pair<String, Int> {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .apply {
                environment()["PATH"] = "/usr/bin:/bin"
                environment()["STUB"] = root.path
                environment()["SHELL"] = loginShell(root).path
            }
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor(15, TimeUnit.SECONDS)) { "command timed out" }
        return output to process.exitValue()
    }

    private companion object {
        /** Logs its arguments and the socket it was given, then answers an attach like herdr 0.9 does. */
        val STUB_HERDR = """
            #!/bin/sh
            echo "${'$'}*" >> "${'$'}STUB/log"
            echo "${'$'}HERDR_SOCKET_PATH" > "${'$'}STUB/socket"
            case "${'$'}1 ${'$'}2" in
            "terminal attach") echo "ATTACHED ${'$'}3";;
            *) echo "unexpected ${'$'}*"; exit 2;;
            esac
        """.trimIndent() + "\n"
    }
}
