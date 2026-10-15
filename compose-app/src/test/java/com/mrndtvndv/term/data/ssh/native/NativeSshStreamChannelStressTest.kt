package com.mrndtvndv.term.data.ssh.native

import com.mrndtvndv.term.domain.SshStreamChannelException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Threads race reads, writes, and a close against one channel, many times over. The fake native fails the
 * test if any call reaches the handle after release, which is the use-after-free the in-flight count exists to
 * prevent. It also checks that release happens exactly once.
 */
class NativeSshStreamChannelStressTest {

    @Test
    fun racingReadsWritesAndCloseNeverTouchReleasedNativeMemory() {
        var rounds = 0
        repeat(ROUNDS) { round ->
            val native = GuardedNative()
            val channel = NativeSshStreamChannel(HANDLE, PATH, native)
            runBlocking { channel.awaitOpen() }

            val failures = mutableListOf<Throwable>()
            val failuresLock = Any()
            val threads = mutableListOf<Thread>()
            repeat(READERS) {
                threads += Thread {
                    try {
                        runBlocking {
                            val buffer = ByteArray(16)
                            while (channel.read(buffer) != -1) {
                                // Keep reading until the close wakes us.
                            }
                        }
                    } catch (error: Throwable) {
                        synchronized(failuresLock) { failures += error }
                    }
                }
            }
            repeat(WRITERS) { writer ->
                threads += Thread {
                    try {
                        runBlocking {
                            while (true) {
                                channel.write(ByteArray(8) { writer.toByte() })
                            }
                        }
                    } catch (_: SshStreamChannelException) {
                        // Expected once the close lands: the write reports CLOSED.
                    } catch (_: IOException) {
                        // Expected for the same reason.
                    } catch (error: Throwable) {
                        synchronized(failuresLock) { failures += error }
                    }
                }
            }
            val closer = Thread {
                Thread.sleep(Random(round).nextLong(0, 3))
                channel.close()
            }
            threads += closer
            threads.forEach { it.start() }
            threads.forEach { it.join(WAIT_MS) }

            assertTrue("round $round: threads still running", threads.none { it.isAlive })
            assertEquals("round $round: unexpected failures $failures", emptyList<Throwable>(), failures)
            assertEquals("round $round: calls after release", 0, native.violations.get())
            assertEquals("round $round: releases", 1, native.releases.get())
            rounds++
        }
        assertEquals(ROUNDS, rounds)
    }

    @Test
    fun closeBeforeAnyCallReleasesOnceAndLaterCallsAreRejected() {
        val native = GuardedNative()
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)
        channel.close()
        channel.close()
        assertEquals(1, native.releases.get())
        val error = runCatching { runBlocking { channel.write(byteArrayOf(1)) } }.exceptionOrNull()
        assertTrue("write after close should report CLOSED, was $error", error is SshStreamChannelException)
        assertEquals(0, native.violations.get())
    }

    /** Fails the test on any native call after release. Reads and writes stay pending until the close. */
    private class GuardedNative : SshStreamNative {
        @Volatile private var closed = false
        @Volatile private var released = false
        val violations = AtomicInteger()
        val releases = AtomicInteger()

        private fun guard() {
            if (released) violations.incrementAndGet()
        }

        override fun awaitOpen(handle: Long, timeoutMs: Int): Int {
            guard()
            return 1
        }

        override fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            guard()
            if (closed) return -1
            Thread.yield()
            return 0
        }

        override fun write(handle: Long, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            guard()
            if (closed) return -1
            Thread.yield()
            return length
        }

        override fun flush(handle: Long, timeoutMs: Int): Int {
            guard()
            return if (closed) -1 else 1
        }

        override fun close(handle: Long) {
            guard()
            closed = true
        }

        override fun release(handle: Long) {
            releases.incrementAndGet()
            released = true
        }

        override fun failure(handle: Long): Int = 0

        override fun detail(handle: Long): String = ""
    }

    private companion object {
        const val HANDLE = 11L
        const val PATH = "/run/herdr/stress.sock"
        const val ROUNDS = 150
        const val READERS = 3
        const val WRITERS = 2
        const val WAIT_MS = 10_000L
    }
}
