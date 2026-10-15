package com.mrndtvndv.term.data.ssh.native

import org.junit.Assert.assertEquals
import org.junit.Test

class SshExecCommandTest {
    @Test
    fun selectsPosixShellWithoutExpandingTheScriptInTheLoginShell() {
        assertEquals(
            "/bin/sh -c 'export PATH=\$PATH:/opt/homebrew/bin; printf \"%s\" \"\$PATH\"'",
            posixExecCommand("export PATH=\$PATH:/opt/homebrew/bin; printf \"%s\" \"\$PATH\""),
        )
    }

    @Test
    fun quotesApostrophesAsOneShellArgument() {
        assertEquals("/bin/sh -c 'printf '\\''%s'\\'' '\\''a b'\\'''", posixExecCommand("printf '%s' 'a b'"))
    }

    @Test
    fun preservesExpansionQuotingAndExitStatus() {
        val script = "value='a b'; printf '%s\\n' \"\$value\"; exit 7"
        val process = ProcessBuilder("/bin/sh", "-c", posixExecCommand(script)).start()
        assertEquals("a b\n", process.inputStream.bufferedReader().use { it.readText() })
        assertEquals(7, process.waitFor())
    }
}
