package com.mrndtvndv.term.workspace

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Runs the generated commands through a real `sh` against a stub `herdr` that logs its calls. */
class HerdrPersistenceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val tab = Tab.Terminal(id = "tab-1", cwd = "/")

    private fun stub(): File {
        val root = temp.root
        File(root, "state").mkdirs()
        File(root, "home").mkdirs()
        val herdr = File(root, "bin/herdr")
        herdr.parentFile.mkdirs()
        herdr.writeText(STUB)
        herdr.setExecutable(true)
        return root
    }

    private fun run(root: File, command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .apply {
                environment()["STUB"] = root.path
                environment()["HOME"] = File(root, "home").path
                environment()["PATH"] = "${root.path}/bin:/usr/bin:/bin"
                environment()["SHELL"] = "/bin/echo"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(15, TimeUnit.SECONDS)) { "command timed out" }
        return output.trim()
    }

    private fun log(root: File): List<String> = File(root, "log").readLines()

    @Test
    fun coldStartLaunchesServerCreatesTabAndAttaches() {
        val root = stub()

        val output = run(root, HerdrPersistence.startCommand(tab)!!)

        assertEquals("ATTACHED term_new1", output)
        assertTrue("herdr server" in log(root))
        assertEquals("term_new1", File(root, "home/.cache/ecto/herdr/tab-1").readText().trim())
    }

    @Test
    fun reattachSkipsCreation() {
        val root = stub()
        run(root, HerdrPersistence.startCommand(tab)!!)
        File(root, "log").writeText("")

        val output = run(root, HerdrPersistence.startCommand(tab)!!)

        assertEquals("ATTACHED term_new1", output)
        assertFalse(log(root).any { it.startsWith("herdr tab create") || it == "herdr server" })
    }

    @Test
    fun terminateClosesTheTabsPaneAndForgetsIt() {
        val root = stub()
        run(root, HerdrPersistence.startCommand(tab)!!)
        File(root, "log").writeText("")

        runBlocking { HerdrPersistence.terminate(tab) { run(root, it) } }

        assertEquals(listOf("herdr pane list", "herdr pane close w1:p2"), log(root))
        assertFalse(File(root, "home/.cache/ecto/herdr/tab-1").exists())
    }

    @Test
    fun missingHerdrFallsBackToLoginShell() {
        val root = stub()
        File(root, "bin/herdr").delete()

        assertEquals("-l", run(root, HerdrPersistence.startCommand(tab)!!))
    }

    private companion object {
        val STUB = """
            #!/bin/sh
            st="${'$'}STUB/state"; echo "herdr ${'$'}*" >> "${'$'}STUB/log"
            case "${'$'}1 ${'$'}2" in
            "status server") [ -f "${'$'}st/up" ] && echo "status: running" || echo "status: not running";;
            "server ") touch "${'$'}st/up";;
            "pane list") cat "${'$'}st/panes" 2>/dev/null || echo '{"result":{"panes":[]}}';;
            "workspace get") [ "${'$'}3" = "w1" ] && [ -f "${'$'}st/ws" ] && exit 0; exit 1;;
            "workspace create") touch "${'$'}st/ws"; echo '{"result":{"workspace":{"workspace_id":"w1"},"root_pane":{"terminal_id":"term_root"}}}';;
            "tab create") echo '{"result":{"tab":{"tab_id":"w1:t2"},"root_pane":{"agent_session":{"a":"b"},"cwd":"/x, y","pane_id":"w1:p2","scroll":{"m":1},"terminal_id":"term_new1"}}}'
              echo '{"result":{"panes":[{"pane_id":"w1:p1","scroll":{"m":0},"terminal_id":"term_root"},{"pane_id":"w1:p2","scroll":{"m":0},"terminal_id":"term_new1"}]}}' > "${'$'}st/panes";;
            "terminal attach") echo "ATTACHED ${'$'}4";;
            "pane close") ;;
            *) echo "unexpected ${'$'}*"; exit 2;;
            esac
        """.trimIndent() + "\n"
    }
}
