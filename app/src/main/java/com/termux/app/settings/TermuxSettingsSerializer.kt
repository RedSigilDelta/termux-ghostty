package com.termux.app.settings

import androidx.datastore.core.Serializer
import com.termux.shared.logger.Logger
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object TermuxSettingsSerializer : Serializer<TermuxSettings> {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue = TermuxSettings()

    override suspend fun readFrom(input: InputStream): TermuxSettings =
        try {
            json.decodeFromString<TermuxSettings>(input.readBytes().decodeToString())
        } catch (e: IllegalArgumentException) {
            Logger.logStackTraceWithMessage("TermuxSettingsSerializer", "Corrupt settings, using defaults", e)
            defaultValue
        }

    override suspend fun writeTo(t: TermuxSettings, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}
