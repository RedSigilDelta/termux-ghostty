package com.mrndtvndv.term.data.prefs

import com.mrndtvndv.term.ui.keyboard.SoftKeyboardState
import com.mrndtvndv.term.ui.workspace.CursorTrailEffect
import com.mrndtvndv.term.ui.workspace.VisualEffectFrameRate
import com.termux.terminal.compose.WallpaperScaling

/** How the arrow joystick in the key bar turns a press into arrow keys. */
enum class ArrowJoystickMode { Swipe, Hold }

@Suppress("LongParameterList")
data class AppSettings(
    val theme: String,
    val amoledBlack: Boolean,
    val fontSize: Int,
    val keyboardResizeDebounceMs: Int,
    val unconditionalSoftKeyboardOnTap: Boolean,
    val autoShowKeyboardOnTap: Boolean,
    val cursorTrail: CursorTrailEffect,
    val visualEffectFrameRate: VisualEffectFrameRate,
    val customFontName: String?,
    val useCustomFontForWholeUi: Boolean,
    val nativeLogcatLoggingEnabled: Boolean,
    val debugHudEnabled: Boolean,
    val animationsEnabled: Boolean,
    val rememberSoftKeyboardState: Boolean,
    val lastSoftKeyboardState: SoftKeyboardState,
    val arrowJoystickMode: ArrowJoystickMode,
    val wallpaperUri: String?,
    val wallpaperName: String?,
    val wallpaperEnabled: Boolean,
    val wallpaperOpacity: Float,
    val wallpaperScaling: WallpaperScaling,
    val wallpaperId: Long,
)
