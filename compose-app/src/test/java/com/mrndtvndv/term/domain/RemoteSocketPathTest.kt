package com.mrndtvndv.term.domain

import org.junit.Assert.fail
import org.junit.Test

class RemoteSocketPathTest {
    @Test
    fun acceptsAbsolutePaths() {
        requireRemoteSocketPath("/run/user/1000/herdr.sock")
        requireRemoteSocketPath("/")
    }

    @Test
    fun rejectsEmptyAndRelativePaths() {
        assertRejected("")
        assertRejected("herdr.sock")
        assertRejected("./herdr.sock")
    }

    @Test
    fun rejectsTildeBecauseTheRemoteShellIsNotInvolved() {
        assertRejected("~/.config/herdr/herdr.sock")
    }

    @Test
    fun rejectsNulBytesAndOverlongPaths() {
        assertRejected("/tmp/herdr\u0000.sock")
        assertRejected("/" + "a".repeat(MaxRemoteSocketPathBytes))
    }

    private fun assertRejected(path: String) {
        try {
            requireRemoteSocketPath(path)
        } catch (expected: IllegalArgumentException) {
            return
        }
        fail("Accepted invalid socket path: $path")
    }
}
