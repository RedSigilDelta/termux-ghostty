package com.termux.app.workspace

import java.io.File
import kotlinx.serialization.json.Json

private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    encodeDefaults = true
}

class WorkspaceFile(private val file: File) {

    fun read(): WorkspaceState =
        try {
            json.decodeFromString<WorkspaceState>(file.readText())
        } catch (e: Exception) {
            WorkspaceState()
        }

    @Synchronized
    fun write(state: WorkspaceState) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(state))
        check(tmp.renameTo(file)) { "Cannot replace ${file.path}" }
    }
}
