package com.mrndtvndv.term.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TerminalPersistenceTest {

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test
    fun noPersistenceStartsLoginShellInCwd() {
        val tab = Tab.Terminal(id = "abc", cwd = "/home/u/my dir")

        assertEquals(
            "sh -c 'cd '\\''/home/u/my dir'\\'' 2>/dev/null; exec \"\${SHELL:-/bin/sh}\" -l'",
            NoPersistence.startCommand(tab),
        )
    }

    @Test
    fun dtachStartsOrAttachesSocketInCwd() {
        val tab = Tab.Terminal(id = "abc", cwd = "/srv")

        assertEquals(
            "sh -c 'mkdir -p \"\$HOME/.cache/ecto/dtach\"; cd '\\''/srv'\\'' 2>/dev/null; " +
                "if command -v dtach >/dev/null 2>&1; then exec dtach -A \"\$HOME/.cache/ecto/dtach/abc.sock\" " +
                "-E -z -r winch \"\${SHELL:-/bin/sh}\" -l; else exec \"\${SHELL:-/bin/sh}\" -l; fi'",
            DtachPersistence.startCommand(tab),
        )
    }

    @Test
    fun dtachQuotesCwdWithSingleQuote() {
        val tab = Tab.Terminal(id = "abc", cwd = "/srv/it's")

        assertEquals(
            "sh -c 'mkdir -p \"\$HOME/.cache/ecto/dtach\"; cd '\\''/srv/it'\\''\\'\\'''\\''s'\\'' 2>/dev/null; " +
                "if command -v dtach >/dev/null 2>&1; then exec dtach -A \"\$HOME/.cache/ecto/dtach/abc.sock\" " +
                "-E -z -r winch \"\${SHELL:-/bin/sh}\" -l; else exec \"\${SHELL:-/bin/sh}\" -l; fi'",
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
            "sh -c 'pkill -f \"[d]tach -A \$HOME/.cache/ecto/dtach/abc.sock\"; " +
                "rm -f \"\$HOME/.cache/ecto/dtach/abc.sock\"; true'",
            command,
        )
    }

    @Test
    fun kindCreatesMatchingPersistence() {
        assertSame(NoPersistence, TerminalPersistenceKind.None.create())
        assertSame(DtachPersistence, TerminalPersistenceKind.Dtach.create())
    }
}
