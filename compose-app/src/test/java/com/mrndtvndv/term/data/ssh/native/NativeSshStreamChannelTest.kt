package com.mrndtvndv.term.data.ssh.native

import com.mrndtvndv.term.domain.SshStreamChannelException
import com.mrndtvndv.term.domain.SshStreamChannelException.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

class NativeSshStreamChannelTest {
    @Test
    fun readReturnsBytesTheNativeLoopBuffered() {
        val native = FakeStreamNative().apply { queueInbound(byteArrayOf(1, 2, 3)) }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        val buffer = ByteArray(8)
        val count = runBlocking {
            channel.awaitOpen()
            channel.read(buffer)
        }

        assertEquals(3, count)
        assertArrayEquals(byteArrayOf(1, 2, 3), buffer.copyOf(count))
    }

    @Test
    fun readReturnsMinusOneAtEndOfStream() {
        val native = FakeStreamNative().apply { eof = true }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        val count = runBlocking {
            channel.awaitOpen()
            channel.read(ByteArray(4))
        }

        assertEquals(-1, count)
    }

    @Test
    fun readKeepsPollingUntilBytesArrive() {
        val native = FakeStreamNative().apply { emptyPollsBeforeData = 3; queueInbound(byteArrayOf(9)) }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        val buffer = ByteArray(4)
        val count = runBlocking {
            channel.awaitOpen()
            channel.read(buffer)
        }

        assertEquals(1, count)
        assertEquals(9, buffer[0].toInt())
        assertEquals(4, native.readCalls)
    }

    @Test
    fun writeDeliversEveryByteInOrderWhenNativeAcceptsPartially() {
        val native = FakeStreamNative().apply { acceptPerWrite = 3 }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)
        // All 256 byte values, so sign handling and chunk boundaries both get exercised.
        val payload = ByteArray(256) { it.toByte() }

        runBlocking {
            channel.awaitOpen()
            channel.write(payload)
        }

        assertArrayEquals(payload, native.written())
    }

    @Test
    fun writeReturnsOnlyOnceBytesAreFlushedNotJustAccepted() {
        val native = FakeStreamNative().apply { flushStalls = true }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)
        val payload = byteArrayOf(4, 5, 6)

        runBlocking {
            channel.awaitOpen()
            val writer = async(Dispatchers.IO) { channel.write(payload) }
            delay(WRITE_SETTLE_MS)
            // Every byte is in the native ring, but libssh2 has not written them yet.
            assertTrue("write returned before flush", !writer.isCompleted)
            assertEquals(3, native.written().size)

            native.flushStalls = false
            withTimeout(WAKE_TIMEOUT_MS) { writer.await() }
        }
    }

    @Test
    fun cancelledFlushAfterAcceptanceClosesTheChannel() {
        val native = FakeStreamNative().apply { flushStalls = true }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        runBlocking {
            channel.awaitOpen()
            val writer = launch(Dispatchers.IO) { channel.write(ByteArray(10)) }
            delay(WRITE_SETTLE_MS)
            writer.cancelAndJoin()
        }

        // The bytes are queued natively but not confirmed written, so the stream must end.
        assertEquals(1, native.closes)
    }

    @Test
    fun writeAfterCloseFailsAsClosed() {
        val native = FakeStreamNative()
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)
        runBlocking { channel.awaitOpen() }
        channel.close()

        val error = streamFailure { channel.write(byteArrayOf(1)) }

        assertEquals(Reason.CLOSED, error.reason)
    }

    @Test
    fun closeWakesSuspendedReaderWithEndOfStream() {
        val native = FakeStreamNative()
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        val count = runBlocking {
            channel.awaitOpen()
            val reader = async(Dispatchers.IO) { channel.read(ByteArray(4)) }
            delay(20)
            channel.close()
            withTimeout(WAKE_TIMEOUT_MS) { reader.await() }
        }

        assertEquals(-1, count)
    }

    @Test
    fun nativeReleaseWaitsForTheInFlightRead() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val native = FakeStreamNative().apply { blockReadsOn(gate, started) }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        runBlocking {
            channel.awaitOpen()
            val reader = async(Dispatchers.IO) { channel.read(ByteArray(4)) }
            started.await()

            channel.close()
            // The read is still inside native code, so freeing the handle now would be a use-after-free.
            assertEquals(0, native.releases)

            gate.countDown()
            assertEquals(-1, withTimeout(WAKE_TIMEOUT_MS) { reader.await() })
        }

        assertEquals(1, native.releases)
    }

    @Test
    fun closeIsIdempotentAndReleasesOnce() {
        val native = FakeStreamNative()
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        channel.close()
        channel.close()

        assertEquals(1, native.closes)
        assertEquals(1, native.releases)
    }

    @Test
    fun cancelledPartialWriteClosesTheChannel() {
        // Three bytes make it into the native ring, then the ring stays full.
        val native = FakeStreamNative().apply {
            acceptPerWrite = 3
            acceptLimit = 3
        }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        runBlocking {
            channel.awaitOpen()
            val writer = launch(Dispatchers.IO) { channel.write(ByteArray(10)) }
            delay(WRITE_SETTLE_MS)
            writer.cancelAndJoin()
        }

        assertEquals(3, native.written().size)
        assertEquals(1, native.closes)
    }

    @Test
    fun cancelledWriteWithNothingQueuedLeavesTheChannelOpen() {
        val native = FakeStreamNative().apply { acceptLimit = 0 }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        runBlocking {
            channel.awaitOpen()
            val writer = launch(Dispatchers.IO) { channel.write(ByteArray(10)) }
            delay(WRITE_SETTLE_MS)
            writer.cancelAndJoin()
        }

        assertEquals(0, native.closes)
    }

    @Test
    fun refusedOpenMapsToDeniedWithServerHint() {
        val native = FakeStreamNative().apply {
            openStatuses.add(FAILED)
            failureCode = DENIED_CODE
            detailText = "Channel open failure (administratively prohibited)"
        }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        val error = streamFailure { channel.awaitOpen() }

        assertEquals(Reason.DENIED, error.reason)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(PATH))
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("administratively prohibited"))
        // A policy refusal does not identify which server option caused it, so the message must not name one.
        assertFalse(error.message.orEmpty(), error.message.orEmpty().contains("AllowStreamLocalForwarding"))
    }

    @Test
    fun missingListenerMapsToUnreachable() {
        val native = FakeStreamNative().apply {
            openStatuses.add(FAILED)
            failureCode = UNREACHABLE_CODE
        }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        val error = streamFailure { channel.awaitOpen() }

        assertEquals(Reason.UNREACHABLE, error.reason)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Nothing is listening"))
    }

    @Test
    fun droppedSessionMapsToDisconnected() {
        val native = FakeStreamNative().apply {
            openStatuses.add(FAILED)
            failureCode = DISCONNECTED_CODE
        }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        val error = streamFailure { channel.awaitOpen() }

        assertEquals(Reason.DISCONNECTED, error.reason)
    }

    @Test
    fun otherNativeFailureKeepsLibssh2Detail() {
        val native = FakeStreamNative().apply {
            openStatuses.add(FAILED)
            failureCode = OTHER_CODE
            detailText = "Unable to allocate memory"
        }
        val channel = NativeSshStreamChannel(HANDLE, PATH, native)

        val error = streamFailure { channel.awaitOpen() }

        assertEquals(Reason.FAILED, error.reason)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Unable to allocate memory"))
    }

    /** Runs [block] and returns the stream error it throws. Fails the test if none is thrown. */
    private fun streamFailure(block: suspend () -> Unit): SshStreamChannelException {
        try {
            runBlocking { block() }
        } catch (error: SshStreamChannelException) {
            return error
        }
        fail("Expected SshStreamChannelException")
        error("unreachable")
    }

    private companion object {
        const val HANDLE = 7L
        const val PATH = "/run/herdr/herdr.sock"
        const val WRITE_SETTLE_MS = 50L
        const val WAKE_TIMEOUT_MS = 2_000L
        const val FAILED = -2
        const val DENIED_CODE = 1
        const val UNREACHABLE_CODE = 2
        const val DISCONNECTED_CODE = 3
        const val OTHER_CODE = 4
    }
}

/**
 * Stands in for the native loop. Reads and writes return immediately, the way the native waits do
 * after their timeout, so tests run quickly without loading libtermux-ghostty.so.
 */
private class FakeStreamNative : SshStreamNative {
    val openStatuses = ArrayDeque<Int>()
    var failureCode = 0
    var detailText = ""

    private val inbound = ConcurrentLinkedQueue<Byte>()
    private val sink = ByteArrayOutputStream()

    @Volatile var eof = false
    @Volatile var acceptPerWrite = Int.MAX_VALUE
    /** Total bytes the fake accepts before reporting no room. */
    @Volatile var acceptLimit = Int.MAX_VALUE
    @Volatile var emptyPollsBeforeData = 0
    /** Accepted bytes stay unflushed (not yet through libssh2) while this is set. */
    @Volatile var flushStalls = false
    @Volatile var readCalls = 0
    @Volatile var closes = 0
    @Volatile var releases = 0

    @Volatile private var closed = false
    @Volatile private var accepted = 0
    private var blockGate: CountDownLatch? = null
    private var blockStarted: CountDownLatch? = null

    fun queueInbound(bytes: ByteArray) {
        bytes.forEach { inbound.add(it) }
    }

    fun blockReadsOn(gate: CountDownLatch, started: CountDownLatch) {
        blockGate = gate
        blockStarted = started
    }

    fun written(): ByteArray = synchronized(sink) { sink.toByteArray() }

    override fun awaitOpen(handle: Long, timeoutMs: Int): Int = synchronized(openStatuses) {
        openStatuses.removeFirstOrNull() ?: 1
    }

    override fun read(handle: Long, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        readCalls++
        blockGate?.let { gate ->
            blockStarted?.countDown()
            gate.await()
            return 0
        }
        return when {
            closed -> -1
            emptyPollsBeforeData > 0 -> {
                emptyPollsBeforeData--
                idlePoll()
            }
            inbound.isEmpty() -> if (eof) -1 else idlePoll()
            else -> drainInbound(buffer, offset, length)
        }
    }

    private fun idlePoll(): Int {
        Thread.sleep(POLL_SLEEP_MS)
        return 0
    }

    private fun drainInbound(buffer: ByteArray, offset: Int, length: Int): Int {
        var count = 0
        while (count < length) {
            buffer[offset + count] = inbound.poll() ?: break
            count++
        }
        return count
    }

    override fun write(handle: Long, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (closed) return -1
        val room = minOf(acceptLimit - accepted, acceptPerWrite, length)
        if (room <= 0) {
            Thread.sleep(POLL_SLEEP_MS)
            return 0
        }
        synchronized(sink) { sink.write(data, offset, room) }
        accepted += room
        return room
    }

    override fun flush(handle: Long, timeoutMs: Int): Int {
        if (closed) return -1
        if (flushStalls) {
            Thread.sleep(POLL_SLEEP_MS)
            return 0
        }
        return 1
    }

    override fun close(handle: Long) {
        closes++
        closed = true
    }

    override fun release(handle: Long) {
        releases++
    }

    override fun failure(handle: Long): Int = failureCode

    override fun detail(handle: Long): String = detailText

    private companion object {
        const val POLL_SLEEP_MS = 1L
    }
}
