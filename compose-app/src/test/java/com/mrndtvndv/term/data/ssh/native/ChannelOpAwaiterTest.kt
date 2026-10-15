package com.mrndtvndv.term.data.ssh.native

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ChannelOpAwaiterTest {
    @Test
    fun runningCommandIsNotBoundedByTheOpenDeadline() {
        // Open deadline of 10 ms, and the command keeps running for about 50 ms after it opens.
        val native = FakeChannelOpNative().apply {
            openStatuses.add(2)
            runningStatuses.addAll(List(50) { 0 } + listOf(1))
            pollSleepMs = 1
        }
        val awaiter = ChannelOpAwaiter(native, openTimeoutNanos = 10_000_000L, pollMs = 1)

        val result = runBlocking { awaiter.await(HANDLE, "SSH command") { "output" } }

        assertEquals("output", result)
        assertEquals(1, native.releases)
        assertEquals(false, native.calls.first())
        assertTrue(native.calls.drop(1).all { it })
    }

    @Test
    fun unopenedOpTimesOutAndIsReleased() {
        val native = FakeChannelOpNative()
        val awaiter = ChannelOpAwaiter(native, openTimeoutNanos = 20_000_000L, pollMs = 1)
        var taken = false

        val error = ioFailure { awaiter.await(HANDLE, "SSH shell channel") { taken = true } }

        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Timed out"))
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("SSH shell channel"))
        assertEquals(false, taken)
        assertEquals(1, native.releases)
    }

    @Test
    fun nativeFailureSurfacesItsDetail() {
        val native = FakeChannelOpNative().apply {
            openStatuses.add(-2)
            detailText = "Channel open failure (connect failed)"
        }
        val awaiter = ChannelOpAwaiter(native, pollMs = 1)

        val error = ioFailure { awaiter.await(HANDLE, "SFTP subsystem") { Unit } }

        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("connect failed"))
        assertEquals(1, native.releases)
    }

    @Test
    fun takeRunsOnceWhenDone() {
        val native = FakeChannelOpNative().apply { openStatuses.add(1) }
        val awaiter = ChannelOpAwaiter(native, pollMs = 1)
        var takes = 0

        val result = runBlocking {
            awaiter.await(HANDLE, "SSH shell channel") {
                takes++
                "shell"
            }
        }

        assertEquals("shell", result)
        assertEquals(1, takes)
        assertEquals(1, native.releases)
    }

    @Test
    fun cancellationWhileOpeningReleasesTheOp() {
        val native = FakeChannelOpNative().apply { pollSleepMs = 1 }
        val awaiter = ChannelOpAwaiter(native, pollMs = 1)

        runBlocking {
            val job = launch { awaiter.await(HANDLE, "SSH shell channel") { Unit } }
            delay(20)
            job.cancelAndJoin()
        }

        assertEquals(1, native.releases)
    }

    @Test
    fun cancellationWhileRunningReleasesTheOp() {
        val native = FakeChannelOpNative().apply {
            openStatuses.add(2)
            pollSleepMs = 1
        }
        val awaiter = ChannelOpAwaiter(native, pollMs = 1)

        runBlocking {
            val job = launch { awaiter.await(HANDLE, "SSH command") { Unit } }
            delay(20)
            job.cancelAndJoin()
        }

        assertEquals(1, native.releases)
    }

    private fun ioFailure(block: suspend () -> Unit): IOException {
        try {
            runBlocking { block() }
        } catch (error: IOException) {
            return error
        }
        fail("Expected IOException")
        error("unreachable")
    }

    private companion object {
        const val HANDLE = 9L
    }
}

/**
 * Scripted native. [openStatuses] answers the open-phase polls (`untilDone` false) and [runningStatuses]
 * the running-phase polls. Once a script runs out, polls return 0 (still waiting).
 */
private class FakeChannelOpNative : ChannelOpNative {
    val openStatuses = ArrayDeque<Int>()
    val runningStatuses = ArrayDeque<Int>()
    var detailText = ""
    var pollSleepMs = 0L
    val calls = mutableListOf<Boolean>()
    @Volatile var releases = 0

    override fun await(op: Long, timeoutMs: Int, untilDone: Boolean): Int {
        val status = synchronized(this) {
            calls += untilDone
            (if (untilDone) runningStatuses else openStatuses).removeFirstOrNull() ?: 0
        }
        if (status == 0 && pollSleepMs > 0) Thread.sleep(pollSleepMs)
        return status
    }

    override fun detail(op: Long): String = detailText

    override fun release(op: Long) {
        releases++
    }
}
