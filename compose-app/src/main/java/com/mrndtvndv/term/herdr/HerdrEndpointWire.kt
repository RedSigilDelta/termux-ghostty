package com.mrndtvndv.term.herdr

import com.mrndtvndv.term.domain.SshStreamChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream

/** The endpoint generation-one envelope is length-prefixed bincode; the snapshot itself is JSON. */
internal object HerdrEndpointWire {
    private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    private const val ENDPOINT_CONTROL = 20L
    private const val SEMANTIC_NOTIFICATION = 14L
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Message {
        data class Control(val kind: String, val data: String) : Message
        data class Notification(
            val title: String,
            val body: String?,
            val workspaceId: String?,
            val tabId: String?,
            val paneId: String?,
        ) : Message
        data object Other : Message
    }

    fun hello(): ByteArray {
        val data = """{
            "generation":1,"cell_width_px":0,"cell_height_px":0,"surface_size":{"cols":1,"rows":1},
            "pixel_mouse":false,"direct_graphics":false,"endpoint_keybindings":false,
            "mouse_capture":false,"surface_active":false,
            "snapshot_codecs":["shell.snapshot.v1"],"surface_codecs":["shell.surface.v1"],
            "input_codecs":["shell.input.semantic.v1"],"blob_codecs":["shell.blob.v1"]
        }""".trimIndent()
        val payload = ByteArrayOutputStream().apply {
            writeInteger(ENDPOINT_CONTROL)
            writeString("endpoint.hello.v1")
            writeString(data)
        }.toByteArray()
        return byteArrayOf(
            payload.size.toByte(),
            (payload.size shr 8).toByte(),
            (payload.size shr 16).toByte(),
            (payload.size shr 24).toByte(),
        ) + payload
    }

    suspend fun read(channel: SshStreamChannel): Message? {
        val header = readExact(channel, 4) ?: return null
        val length = (header[0].toInt() and 255) or
            ((header[1].toInt() and 255) shl 8) or
            ((header[2].toInt() and 255) shl 16) or
            ((header[3].toInt() and 255) shl 24)
        require(length in 1..MAX_FRAME_BYTES) { "Invalid Herdr endpoint frame length: $length" }
        val bytes = readExact(channel, length) ?: error("Herdr endpoint closed mid-frame")
        return decode(bytes)
    }

    fun decode(bytes: ByteArray): Message {
        val cursor = Cursor(bytes)
        return when (cursor.integer()) {
            ENDPOINT_CONTROL -> Message.Control(cursor.string(), cursor.string()).also { cursor.requireEnd() }
            SEMANTIC_NOTIFICATION -> {
                cursor.integer() // kind
                val title = cursor.string()
                val body = cursor.optionalString()
                cursor.optionalInteger() // sound
                cursor.optionalString() // agent
                val workspaceId = cursor.optionalString()
                val tabId = cursor.optionalString()
                val paneId = cursor.optionalString()
                cursor.optionalInteger() // position
                Message.Notification(title, body, workspaceId, tabId, paneId).also { cursor.requireEnd() }
            }
            else -> Message.Other
        }
    }

    fun statuses(data: String): Map<String, String> {
        val root = json.parseToJsonElement(data).jsonObject
        return root.getValue("agents").jsonArray.associate { agent ->
            val value: JsonObject = agent.jsonObject
            value.getValue("pane_id").jsonPrimitive.content to value.getValue("agent_status").jsonPrimitive.content
        }
    }

    fun validateWelcome(message: Message?) {
        require(message is Message.Control && message.kind == "endpoint.welcome.v1") {
            "Herdr endpoint handshake failed"
        }
        val welcome = json.parseToJsonElement(message.data).jsonObject
        require(welcome["generation"]?.jsonPrimitive?.content == "1" &&
            welcome["snapshot_codec"]?.jsonPrimitive?.content == "shell.snapshot.v1" &&
            (welcome["error"] == null || welcome["error"].toString() == "null")
        ) { "Incompatible Herdr endpoint" }
    }

    private suspend fun readExact(channel: SshStreamChannel, size: Int): ByteArray? {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val chunk = ByteArray(minOf(8192, size - offset))
            val count = channel.read(chunk)
            if (count < 0 && offset == 0) return null
            check(count > 0) { "Herdr endpoint closed mid-frame" }
            chunk.copyInto(result, offset, 0, count)
            offset += count
        }
        return result
    }

    private fun ByteArrayOutputStream.writeInteger(value: Long) {
        when {
            value < 251 -> write(value.toInt())
            value <= 0xffff -> {
                write(251)
                write(value.toInt() and 255)
                write((value.toInt() shr 8) and 255)
            }
            else -> error("Unsupported bincode integer: $value")
        }
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInteger(bytes.size.toLong())
        write(bytes)
    }

    private class Cursor(private val bytes: ByteArray) {
        private var position = 0

        fun integer(): Long {
            val first = next()
            return when (first) {
                in 0..250 -> first.toLong()
                251 -> (next() or (next() shl 8)).toLong()
                252 -> (0 until 4).fold(0L) { value, shift -> value or (next().toLong() shl (shift * 8)) }
                else -> error("Unsupported bincode integer prefix: $first")
            }
        }

        fun string(): String {
            val length = integer()
            require(length <= bytes.size - position) { "Truncated Herdr endpoint string" }
            return bytes.copyOfRange(position, position + length.toInt()).toString(Charsets.UTF_8).also {
                position += length.toInt()
            }
        }

        fun optionalString(): String? = when (next()) {
            0 -> null
            1 -> string()
            else -> error("Invalid Herdr endpoint option")
        }

        fun optionalInteger(): Long? = when (next()) {
            0 -> null
            1 -> integer()
            else -> error("Invalid Herdr endpoint option")
        }

        fun requireEnd() = require(position == bytes.size) { "Trailing Herdr endpoint bytes" }

        private fun next(): Int {
            require(position < bytes.size) { "Truncated Herdr endpoint frame" }
            return bytes[position++].toInt() and 255
        }
    }
}
