package com.termux.app.settings

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import com.termux.shared.logger.Logger
import com.termux.shared.termux.settings.TermuxSettingsProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The settings the app runs with, and the keys that `termux.toml` set. */
data class EffectiveSettings(val settings: TermuxSettings, val fileKeys: Set<String>, val errors: List<String>)

/**
 * App-wide settings: defaults, overridden by the DataStore (edited by the UI), overridden by `termux.toml`.
 * Java code reads [current]; Compose collects [effective].
 */
class TermuxSettingsRepository private constructor(context: Context) : TermuxSettingsProvider {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = DataStoreFactory.create(
        serializer = TermuxSettingsSerializer,
        scope = scope,
        produceFile = { File(context.filesDir, "datastore/termux_settings.json") },
    )
    private val json = Json(TermuxSettingsSerializer.json) { isLenient = true }
    private val knownKeys = TermuxSettings.serializer().descriptor.elementNames.toSet()

    @Volatile
    private var stored = readStored()
    private var configFile = TermuxConfigFile.read()
    private val _effective = MutableStateFlow(merge())

    val effective: StateFlow<EffectiveSettings> = _effective.asStateFlow()

    fun current(): TermuxSettings = _effective.value.settings

    @Synchronized
    fun update(transform: (TermuxSettings) -> TermuxSettings) {
        stored = transform(stored)
        _effective.value = merge()
        scope.launch { store.updateData { stored } }
    }

    fun set(key: String, value: Boolean) = set(key, JsonPrimitive(value))

    fun set(key: String, value: String) = set(key, JsonPrimitive(value))

    @Synchronized
    fun reload() {
        configFile = TermuxConfigFile.read()
        _effective.value = merge()
    }

    override fun shouldAllowExternalApps() = current().allowExternalApps

    override fun shouldRunTermuxAmSocketServer() = current().runTermuxAmSocketServer

    override fun getDeleteTMPDIRFilesOlderThanXDaysOnExit() = current().deleteTmpdirFilesOlderThanXDaysOnExit

    private fun set(key: String, value: JsonElement) =
        update { json.decodeFromJsonElement<TermuxSettings>(JsonObject(encode(it) + (key to value))) }

    private fun encode(settings: TermuxSettings) = json.encodeToJsonElement(TermuxSettings.serializer(), settings).jsonObject

    private fun readStored(): TermuxSettings =
        try {
            runBlocking { store.data.first() }
        } catch (e: IOException) {
            Logger.logStackTraceWithMessage(TAG, "Failed to read settings, using defaults", e)
            TermuxSettings()
        }

    /** `allow-external-apps` is only honoured from `termux.toml`, so the stored value never grants access. */
    private fun merge(): EffectiveSettings {
        var merged = encode(stored.copy(allowExternalApps = false))
        val fileKeys = mutableSetOf<String>()
        val errors = configFile.errors.toMutableList()
        for ((key, value) in configFile.values) {
            if (key !in knownKeys) {
                Logger.logError(TAG, "Ignoring unknown key \"$key\" in termux.toml")
                errors += "Unknown key \"$key\""
                continue
            }
            val candidate = JsonObject(merged + (key to value))
            try {
                json.decodeFromJsonElement<TermuxSettings>(candidate)
            } catch (e: IllegalArgumentException) {
                Logger.logError(TAG, "Ignoring invalid value for \"$key\" in termux.toml: ${e.message}")
                errors += "Invalid value for \"$key\""
                continue
            }
            merged = candidate
            fileKeys += key
        }
        return EffectiveSettings(json.decodeFromJsonElement(merged), fileKeys, errors)
    }

    companion object {
        private const val TAG = "TermuxSettingsRepository"

        private lateinit var instance: TermuxSettingsRepository

        /** Blocks on the first DataStore read so [current] is valid as soon as this returns. */
        @JvmStatic
        fun init(context: Context): TermuxSettingsRepository {
            instance = TermuxSettingsRepository(context.applicationContext)
            return instance
        }

        @JvmStatic
        fun get(): TermuxSettingsRepository = instance
    }
}
