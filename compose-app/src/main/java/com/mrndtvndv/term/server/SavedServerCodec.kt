package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Reads and writes the whole saved server list. Decoding is all-or-nothing: text that is not a JSON list, or in
 * which any entry fails to decode, throws [UnreadableException]. Such text is never partly read or rewritten.
 * Pure JSON, so it runs without Android.
 *
 * Entries saved under the retired `persistence` setting are converted once, here, as they are read. A write stores
 * only the current shape, so the legacy key does not survive the next rewrite.
 */
object SavedServerCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The stored list cannot be read in full. [message] is safe to show to the user. */
    class UnreadableException(message: String) : Exception(message)

    fun decode(raw: String): List<ServerConfig> {
        // Blank text is damage, not an empty list. No version writes it, so it is refused as unreadable.
        if (raw.isBlank()) unreadable("Saved servers could not be read: the stored data is empty.")
        val entries = parseText(raw) as? JsonArray
            ?: unreadable("Saved servers could not be read: the stored data is not a list.")
        return entries.mapIndexed { index, element -> decodeEntry(index, element) }
    }

    fun encode(servers: List<ServerConfig>): String =
        JsonArray(servers.map { json.encodeToJsonElement(it) }).toString()

    private fun parseText(raw: String): JsonElement = try {
        json.parseToJsonElement(raw)
    } catch (_: SerializationException) {
        unreadable("Saved servers could not be read: the stored data is not valid JSON.")
    }

    private fun decodeEntry(index: Int, element: JsonElement): ServerConfig {
        val entry = element as? JsonObject ?: unreadable(entryMessage(index))
        val current = convertLegacyPersistence(index, entry)
        return try {
            json.decodeFromJsonElement<ServerConfig>(current)
        } catch (_: SerializationException) {
            unreadable(entryMessage(index))
        } catch (_: IllegalArgumentException) {
            unreadable(entryMessage(index))
        }
    }

    /**
     * One-time conversion of the retired `persistence` setting into `herdrEnabled`. `Herdr` turns the integration
     * on. `Dtach` and `None` leave it off: dtach is no longer managed, and the remote sessions it kept alive keep
     * running untouched. An explicit `herdrEnabled` already in the entry wins over the legacy value. Any other legacy
     * value is malformed, so the whole list is unreadable rather than guessed at.
     */
    private fun convertLegacyPersistence(index: Int, entry: JsonObject): JsonObject {
        val legacy = entry["persistence"] ?: return entry
        val herdrEnabled = when ((legacy as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "Herdr" -> true
            "Dtach", "None" -> false
            else -> unreadable(entryMessage(index))
        }
        val current = entry.filterKeys { it != "persistence" }
        return if ("herdrEnabled" in current) {
            JsonObject(current)
        } else {
            JsonObject(current + ("herdrEnabled" to JsonPrimitive(herdrEnabled)))
        }
    }

    private fun entryMessage(index: Int): String =
        "Saved servers could not be read: entry ${index + 1} is not understood by this version of the app."

    private fun unreadable(message: String): Nothing = throw UnreadableException(message)
}
