package com.mrndtvndv.term.data.ssh.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class NativeStartHandleTest {
    @Test
    fun validHandlePassesThrough() {
        assertEquals(42L, requireStartedHandle(42L, "SSH shell channel"))
    }

    @Test
    fun zeroMeansTheSessionIsNotRunning() {
        val error = ioFailure { requireStartedHandle(0L, "the SSH command") }

        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("not running"))
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("the SSH command"))
    }

    @Test
    fun busyIsReportedAsRetryable() {
        val error = ioFailure { requireStartedHandle(NativeStartBusy, "the SFTP subsystem") }

        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("try again"))
    }

    @Test
    fun busySentinelIsMinusOneLikeTheNativeLayer() {
        assertEquals(-1L, NativeStartBusy)
    }

    private fun ioFailure(block: () -> Unit): IOException {
        try {
            block()
        } catch (error: IOException) {
            return error
        }
        fail("Expected IOException")
        error("unreachable")
    }
}
