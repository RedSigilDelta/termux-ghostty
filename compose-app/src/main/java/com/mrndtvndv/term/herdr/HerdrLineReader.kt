package com.mrndtvndv.term.herdr

import com.mrndtvndv.term.domain.SshStreamChannel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Reads newline-delimited JSON lines from a stream channel.
 *
 * Bytes are buffered until a newline arrives, and only complete lines are decoded, so a
 * UTF-8 sequence split across reads stays intact. Lines longer than [maxLineBytes] fail fast.
 */
internal class HerdrLineReader(
    private val channel: SshStreamChannel,
    private val maxLineBytes: Int,
) {
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    private var cursor = 0
    private var end = 0
    private val line = ByteArrayOutputStream()

    /** Next non-blank line without its terminator, or null on clean EOF. */
    suspend fun readLine(): String? {
        while (true) {
            while (cursor < end) {
                val newline = indexOfNewline(cursor, end)
                val segmentEnd = if (newline < 0) end else newline
                if (line.size() + segmentEnd - cursor > maxLineBytes) {
                    throw HerdrException(
                        HerdrError(HerdrErrorKind.Protocol, "Herdr line exceeds $maxLineBytes bytes"),
                    )
                }
                line.write(readBuffer, cursor, segmentEnd - cursor)
                if (newline < 0) {
                    cursor = end
                    break
                }
                cursor = newline + 1
                val text = decodeLine()
                if (text.isNotBlank()) return text
            }

            val read = channel.read(readBuffer)
            if (read < 0) {
                if (line.size() == 0) return null
                throw HerdrException(
                    HerdrError(HerdrErrorKind.Disconnected, "Herdr connection closed in the middle of a line"),
                )
            }
            check(read > 0) { "SshStreamChannel.read returned 0 bytes" }
            cursor = 0
            end = read
        }
    }

    private fun indexOfNewline(from: Int, to: Int): Int {
        for (index in from until to) {
            if (readBuffer[index] == NEWLINE) return index
        }
        return -1
    }

    private fun decodeLine(): String {
        var bytes = line.toByteArray()
        line.reset()
        if (bytes.isNotEmpty() && bytes.last() == CARRIAGE_RETURN) {
            bytes = bytes.copyOf(bytes.size - 1)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw HerdrException(HerdrError(HerdrErrorKind.Protocol, "Herdr line is not valid UTF-8"), error)
        }
    }

    private companion object {
        const val READ_CHUNK_BYTES = 16 * 1024
        const val NEWLINE = '\n'.code.toByte()
        const val CARRIAGE_RETURN = '\r'.code.toByte()
    }
}
