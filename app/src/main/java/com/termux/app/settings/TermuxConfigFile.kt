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

data class ConfigFile(val values: JsonObject, val errors: List<String>)

/** Reads the user-edited `~/.termux/termux.toml` as a JSON object of its top-level keys. */
object TermuxConfigFile {

    private const val TAG = "TermuxConfigFile"

    /** Returns an empty object if the file is missing or cannot be parsed. */
    fun read(file: File = TermuxConstants.TERMUX_SETTINGS_FILE): ConfigFile {
        if (!file.isFile) return ConfigFile(JsonObject(emptyMap()), emptyList())
        return try {
            val result = Toml.parse(file.readText())
            if (result.hasErrors()) {
                val errors = result.errors().map {
                    Logger.logError(TAG, it.toString())
                    "Line ${it.position().line()}, column ${it.position().column()}: ${it.message}"
                }
                return ConfigFile(JsonObject(emptyMap()), errors)
            }
            val entries = mutableMapOf<String, JsonElement>()
            val errors = mutableListOf<String>()
            for (key in result.keySet()) {
                val value = toJson(result.get(listOf(key)))
                if (value == null) {
                    Logger.logError(TAG, "Ignoring unsupported value for \"$key\"")
                    errors += "Unsupported value for \"$key\""
                } else {
                    entries[key] = value
                }
            }
            ConfigFile(JsonObject(entries), errors)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(TAG, "Failed to read ${file.path}", e)
            ConfigFile(JsonObject(emptyMap()), listOf("Could not read the file: ${e.message}"))
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
