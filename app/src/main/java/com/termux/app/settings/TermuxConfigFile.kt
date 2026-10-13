package com.termux.app.settings

import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

/** Reads the user-edited `~/.termux/termux.toml` as a JSON object of its top-level keys. */
object TermuxConfigFile {

    private const val TAG = "TermuxConfigFile"

    /** Returns an empty object if the file is missing or cannot be parsed. */
    fun read(file: File = TermuxConstants.TERMUX_SETTINGS_FILE): JsonObject {
        if (!file.isFile) return JsonObject(emptyMap())
        return try {
            val result = Toml.parse(file.readText())
            if (result.hasErrors()) {
                result.errors().forEach { Logger.logError(TAG, it.toString()) }
                return JsonObject(emptyMap())
            }
            val entries = mutableMapOf<String, JsonElement>()
            for (key in result.keySet()) {
                val value = toJson(result.get(listOf(key)))
                if (value == null) {
                    Logger.logError(TAG, "Ignoring unsupported value for \"$key\"")
                } else {
                    entries[key] = value
                }
            }
            JsonObject(entries)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(TAG, "Failed to read ${file.path}", e)
            JsonObject(emptyMap())
        }
    }

    private fun toJson(value: Any?): JsonElement? =
        when (value) {
            is String -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Double -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is TomlArray -> JsonArray(value.toList().map { toJson(it) ?: return null })
            is TomlTable -> JsonObject(value.keySet().associateWith { toJson(value.get(listOf(it))) ?: return null })
            else -> null
        }
}
