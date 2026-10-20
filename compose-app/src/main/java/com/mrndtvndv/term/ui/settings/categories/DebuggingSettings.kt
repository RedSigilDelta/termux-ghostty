package com.mrndtvndv.term.ui.settings.categories

import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mrndtvndv.term.NativeLogcatLogger
import com.mrndtvndv.term.ui.settings.ActionRow
import com.mrndtvndv.term.ui.settings.SettingsGroup
import com.mrndtvndv.term.ui.settings.SettingsState
import com.mrndtvndv.term.ui.settings.SwitchRow

@Suppress("LongMethod")
fun LazyListScope.debuggingSettings(state: SettingsState) {
    item {
        SettingsGroup(
            title = "Performance",
            rows = listOf(
                { shapes ->
                    SwitchRow(
                        title = "Debug performance HUD",
                        checked = state.settings.debugHudEnabled,
                        onChange = { enabled -> state.onUpdate { it.copy(debugHudEnabled = enabled) } },
                        shapes = shapes,
                        summary = "Show live FPS, app CPU, RAM, and missed frames over the terminal workspace",
                    )
                },
            ),
        )
    }
    item {
        val context = LocalContext.current
        var logSizeText by remember(context) {
            mutableStateOf(NativeLogcatLogger.getLogFileSizeMb(context))
        }
        var crashDetected by remember(context) {
            mutableStateOf(NativeLogcatLogger.hasDetectedNativeCrash(context))
        }
        SettingsGroup(
            title = "Native crash logger",
            rows = listOf(
                { shapes ->
                    SwitchRow(
                        title = "Native logcat crash logger",
                        checked = state.settings.nativeLogcatLoggingEnabled,
                        onChange = { enabled ->
                            state.onUpdate { it.copy(nativeLogcatLoggingEnabled = enabled) }
                            logSizeText = NativeLogcatLogger.getLogFileSizeMb(context)
                            crashDetected = NativeLogcatLogger.hasDetectedNativeCrash(context)
                        },
                        shapes = shapes,
                        summary = "Continuously record logcat buffer in background to capture native library crashes",
                    )
                },
                { shapes ->
                    ActionRow(
                        title = "Export log",
                        summary = if (crashDetected) {
                            "Log size: $logSizeText · Native crash (SIGSEGV / SIGABRT) detected!"
                        } else {
                            "Log file size: $logSizeText"
                        },
                        shapes = shapes,
                        onClick = {
                            val path = NativeLogcatLogger.exportLogToDownloads(context)
                            if (path != null) {
                                Toast.makeText(context, "Log exported to $path", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Failed to export log or log is empty",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                },
                { shapes ->
                    ActionRow(
                        title = "Clear log",
                        summary = "Delete recorded native logcat buffer",
                        shapes = shapes,
                        onClick = {
                            NativeLogcatLogger.clearLog(context)
                            logSizeText = NativeLogcatLogger.getLogFileSizeMb(context)
                            crashDetected = NativeLogcatLogger.hasDetectedNativeCrash(context)
                            Toast.makeText(context, "Native log cleared", Toast.LENGTH_SHORT).show()
                        },
                    )
                },
            ),
        )
    }
}
