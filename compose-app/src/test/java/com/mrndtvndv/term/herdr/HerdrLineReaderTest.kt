package com.mrndtvndv.term.herdr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HerdrLineReaderTest {
    @Test
    fun `splits lines that arrive one byte at a time`() = runBlocking {
        val channel = FakeStreamChannel { _, _ -> }
        channel.feed("{\"a\":1}\n{\"b\":2}\n", chunkSize = 1)
        channel.finish()
        val reader = HerdrLineReader(channel, maxLineBytes = 1024)

        assertEquals("{\"a\":1}", reader.readLine())
        assertEquals("{\"b\":2}", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `splits several lines coalesced into one read`() = runBlocking {
        val channel = FakeStreamChannel { _, _ -> }
        channel.feed("one\ntwo\nthree\n")
        channel.finish()
        val reader = HerdrLineReader(channel, maxLineBytes = 1024)

        assertEquals("one", reader.readLine())
        assertEquals("two", reader.readLine())
        assertEquals("three", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `keeps multibyte UTF-8 intact when it is split across reads`() = runBlocking {
        val channel = FakeStreamChannel { _, _ -> }
        channel.feed("héllo 🚀\n", chunkSize = 1)
        channel.finish()
        val reader = HerdrLineReader(channel, maxLineBytes = 1024)

        assertEquals("héllo 🚀", reader.readLine())
    }

    @Test
    fun `strips carriage returns and skips blank lines`() = runBlocking {
        val channel = FakeStreamChannel { _, _ -> }
        channel.feed("\r\n\n{\"ok\":true}\r\n")
        channel.finish()
        val reader = HerdrLineReader(channel, maxLineBytes = 1024)

        assertEquals("{\"ok\":true}", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `accepts a line exactly at the bound and rejects one byte more`() = runBlocking {
        val channel = FakeStreamChannel { _, _ -> }
        channel.feed("01234567\n")
        channel.feed("012345678\n")
        channel.finish()
        val reader = HerdrLineReader(channel, maxLineBytes = 8)

        assertEquals("01234567", reader.readLine())
        val error = expectReaderError { reader.readLine() }
        assertEquals(HerdrErrorKind.Protocol, error.kind)
    }

    @Test
    fun `rejects a line that is still growing past the bound before its newline arrives`() = runBlocking {
        val channel = FakeStreamChannel { _, _ -> }
        channel.feed("x".repeat(64), chunkSize = 8)
        val reader = HerdrLineReader(channel, maxLineBytes = 16)

        val error = expectReaderError { reader.readLine() }
        assertEquals(HerdrErrorKind.Protocol, error.kind)
    }

    @Test
    fun `returns null on clean EOF`() = runBlocking {
        val channel = FakeStreamChannel { _, _ -> }
        channel.feed("last\n")
        channel.finish()
        val reader = HerdrLineReader(channel, maxLineBytes = 1024)

        assertEquals("last", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test
    fun `EOF in the middle of a line is a disconnect`() = runBlocking {
        val channel = FakeStreamChannel { _, _ -> }
        channel.feed("{\"partial\":")
        channel.finish()
        val reader = HerdrLineReader(channel, maxLineBytes = 1024)

        val error = expectReaderError { reader.readLine() }
        assertEquals(HerdrErrorKind.Disconnected, error.kind)
    }

    @Test
    fun `invalid UTF-8 in a complete line is a protocol error`() = runBlocking {
        val channel = FakeStreamChannel { _, _ -> }
        channel.feedBytes(byteArrayOf(0x7b, 0xff.toByte(), 0x7d, '\n'.code.toByte()))
        channel.finish()
        val reader = HerdrLineReader(channel, maxLineBytes = 1024)

        val error = expectReaderError { reader.readLine() }
        assertEquals(HerdrErrorKind.Protocol, error.kind)
    }
}

/** Runs [block] and returns the [HerdrError] it threw. Fails the test when nothing is thrown. */
private suspend fun expectReaderError(block: suspend () -> Unit): HerdrError {
    try {
        block()
    } catch (error: HerdrException) {
        return error.error
    }
    throw AssertionError("expected HerdrException")
}
