package com.mrndtvndv.term.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.clipboard.ClipboardImageHandler
import com.mrndtvndv.term.clipboard.ClipboardPathResolver
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.ui.settings.SwitchOption

class ImagePasteSettingsState(
    initialEnabled: Boolean,
    initialDirectory: String,
    initialAutoCleanup: Boolean,
    initialMaxFiles: Int,
) {
    var enabled by mutableStateOf(initialEnabled)
    var directory by mutableStateOf(initialDirectory)
    var autoCleanup by mutableStateOf(initialAutoCleanup)
    var maxFilesText by mutableStateOf(initialMaxFiles.toString())

    val hasDirectory: Boolean get() = directory.isNotBlank()
    val isPasteActive: Boolean get() = enabled && hasDirectory
    val trimmedDirectory: String? get() = directory.trim().ifEmpty { null }
    val maxFiles: Int
        get() = maxFilesText.toIntOrNull()
            ?.coerceAtLeast(1)
            ?: ClipboardImageHandler.MAX_IMAGE_RETENTION_COUNT
}

@Composable
fun rememberImagePasteSettingsState(initialConfig: ServerConfig?): ImagePasteSettingsState {
    return remember(initialConfig) {
        ImagePasteSettingsState(
            initialEnabled = initialConfig?.imagePasteEnabled ?: false,
            initialDirectory = initialConfig?.imagePasteDirectory
                ?: ClipboardPathResolver.DEFAULT_IMAGE_CACHE_DIR,
            initialAutoCleanup = initialConfig?.imagePasteAutoCleanup ?: true,
            initialMaxFiles = initialConfig?.safeImagePasteMaxFiles
                ?: ClipboardImageHandler.MAX_IMAGE_RETENTION_COUNT,
        )
    }
}

@Composable
@Suppress("LongMethod")
fun ImagePasteSettingsSection(
    state: ImagePasteSettingsState,
    directorySupportingText: String,
    activeSupportingText: String,
) {
    OutlinedTextField(
        value = state.directory,
        onValueChange = { state.directory = it },
        label = { Text("Upload Directory") },
        placeholder = { Text(ClipboardPathResolver.DEFAULT_IMAGE_CACHE_DIR) },
        supportingText = { Text(directorySupportingText) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    SwitchOption(
        title = "Media Upload & Paste",
        checked = state.isPasteActive,
        onCheckedChange = { state.enabled = it },
        summary = if (state.hasDirectory) activeSupportingText else "Provide a cache directory above to enable",
        enabled = state.hasDirectory,
    )

    if (state.isPasteActive) {
        SwitchOption(
            title = "Auto Cleanup",
            checked = state.autoCleanup,
            onCheckedChange = { state.autoCleanup = it },
            modifier = Modifier.padding(top = 4.dp),
            summary = "Automatically remove older clipboard images when pasting",
        )

        if (state.autoCleanup) {
            OutlinedTextField(
                value = state.maxFilesText,
                onValueChange = { newValue ->
                    if (newValue.all { it.isDigit() } && newValue.length <= 4) {
                        state.maxFilesText = newValue
                    }
                },
                label = { Text("Max Retained Clipboard Images") },
                placeholder = { Text(ClipboardImageHandler.MAX_IMAGE_RETENTION_COUNT.toString()) },
                supportingText = {
                    Text(
                        "Maximum number of recent clipboard images to keep " +
                            "(default: ${ClipboardImageHandler.MAX_IMAGE_RETENTION_COUNT})",
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
