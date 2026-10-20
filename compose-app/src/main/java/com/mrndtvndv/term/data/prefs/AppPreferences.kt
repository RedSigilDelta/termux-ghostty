package com.mrndtvndv.term.data.prefs

import android.content.SharedPreferences
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardState
import com.mrndtvndv.term.ui.workspace.CursorTrailEffect
import com.mrndtvndv.term.ui.workspace.DefaultKeyboardResizeDebounceMillis
import com.mrndtvndv.term.ui.workspace.MaxKeyboardResizeDebounceMillis
import com.mrndtvndv.term.ui.workspace.VisualEffectFrameRate
import com.mrndtvndv.term.ui.workspace.wallpaperScalingFromPref
import com.termux.terminal.compose.TerminalWallpaperConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private object Key {
    const val Theme = "app_theme"
    const val AmoledBlack = "amoled_black"
    const val FontSize = "font_size"
    const val KeyboardResizeDebounceMs = "keyboard_resize_debounce_ms"
    const val UnconditionalSoftKeyboardOnTap = "unconditional_soft_keyboard_on_tap"
    const val AutoShowKeyboardOnTap = "auto_show_soft_keyboard_on_tap"
    const val CursorTrail = "cursor_trail_effect"
    const val VisualEffectFrameRate = "visual_effect_frame_rate"
    const val CustomFontName = "custom_font_name"
    const val UseCustomFontForWholeUi = "use_custom_font_for_whole_ui"
    const val NativeLogcatLoggingEnabled = "native_logcat_logging_enabled"
    const val DebugHudEnabled = "debug_hud_enabled"
    const val AnimationsEnabled = "animations_enabled"
    const val RememberSoftKeyboardState = "remember_soft_keyboard_state"
    const val LastSoftKeyboardState = "last_soft_keyboard_state"
    const val ArrowJoystickMode = "arrow_joystick_mode"
    const val OverlayKeyBar = "overlay_key_bar"
    const val KeyBarLayout = "key_bar_layout"
    const val WallpaperUri = "terminal_wallpaper_uri"
    const val WallpaperName = "terminal_wallpaper_name"
    const val WallpaperEnabled = "terminal_wallpaper_enabled"
    const val WallpaperOpacity = "terminal_wallpaper_opacity"
    const val WallpaperScaling = "terminal_wallpaper_scaling"
    const val WallpaperId = "terminal_wallpaper_id"
}

/**
 * Absent or unreadable data yields the default layout; the default is never stored, so later default changes apply.
 * A layout saved by an older version is upgraded once and stored back right away.
 */
private fun loadKeyBarLayout(prefs: SharedPreferences): KeyBarLayout {
    val raw = runCatching { prefs.getString(Key.KeyBarLayout, null) }.getOrNull()
    val decoded = raw?.let(::decodeKeyBarLayout) ?: return defaultKeyBarLayout()
    if (decoded.upgraded) prefs.edit().apply { writeKeyBarLayout(decoded.layout) }.apply()
    return decoded.layout
}

private fun SharedPreferences.Editor.writeKeyBarLayout(layout: KeyBarLayout) {
    if (layout == defaultKeyBarLayout()) {
        remove(Key.KeyBarLayout)
    } else {
        putString(Key.KeyBarLayout, encodeKeyBarLayout(layout))
    }
}

// "Black" was the old default theme. It is now Dark with AMOLED black on, which looks the same.
private fun themeFromPref(value: String?): String =
    listOf("System", "Light", "Dark").firstOrNull { it == value } ?: "Dark"

private fun SharedPreferences.Editor.write(settings: AppSettings) {
    putString(Key.Theme, settings.theme)
    putBoolean(Key.AmoledBlack, settings.amoledBlack)
    putInt(Key.FontSize, settings.fontSize)
    putInt(Key.KeyboardResizeDebounceMs, settings.keyboardResizeDebounceMs)
    putBoolean(Key.UnconditionalSoftKeyboardOnTap, settings.unconditionalSoftKeyboardOnTap)
    putBoolean(Key.AutoShowKeyboardOnTap, settings.autoShowKeyboardOnTap)
    putString(Key.CursorTrail, settings.cursorTrail.key)
    putString(Key.VisualEffectFrameRate, settings.visualEffectFrameRate.key)
    putString(Key.CustomFontName, settings.customFontName)
    putBoolean(Key.UseCustomFontForWholeUi, settings.useCustomFontForWholeUi)
    putBoolean(Key.NativeLogcatLoggingEnabled, settings.nativeLogcatLoggingEnabled)
    putBoolean(Key.DebugHudEnabled, settings.debugHudEnabled)
    putBoolean(Key.AnimationsEnabled, settings.animationsEnabled)
    putBoolean(Key.RememberSoftKeyboardState, settings.rememberSoftKeyboardState)
    if (settings.lastSoftKeyboardState == SoftKeyboardState.UNKNOWN) {
        remove(Key.LastSoftKeyboardState)
    } else {
        putString(Key.LastSoftKeyboardState, settings.lastSoftKeyboardState.preferenceValue)
    }
    putString(Key.ArrowJoystickMode, settings.arrowJoystickMode.name)
    putBoolean(Key.OverlayKeyBar, settings.overlayKeyBar)
    writeKeyBarLayout(settings.keyBarLayout)
    putString(Key.WallpaperUri, settings.wallpaperUri)
    putString(Key.WallpaperName, settings.wallpaperName)
    putBoolean(Key.WallpaperEnabled, settings.wallpaperEnabled)
    putFloat(Key.WallpaperOpacity, settings.wallpaperOpacity)
    putString(Key.WallpaperScaling, settings.wallpaperScaling.name)
    putLong(Key.WallpaperId, settings.wallpaperId)
}

class AppPreferences(
    private val prefs: SharedPreferences,
    private val defaultFontSize: Int,
    val fontSizeRange: IntRange,
) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Applies [transform] and saves the result. Returns false and saves nothing when the resulting key-bar layout
     * is not valid, so an unsupported macro step is rejected before it reaches preferences.
     */
    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings): Boolean {
        val old = _settings.value
        val proposed = transform(old)
        if (!proposed.keyBarLayout.isValid()) return false
        val new = normalize(proposed)
        if (new == old) return true
        _settings.value = new
        prefs.edit().apply { write(new) }.apply()
        return true
    }

    private fun load(): AppSettings = normalize(
        AppSettings(
            theme = themeFromPref(prefs.getString(Key.Theme, null)),
            amoledBlack = prefs.getBoolean(Key.AmoledBlack, true),
            fontSize = prefs.getInt(Key.FontSize, defaultFontSize),
            keyboardResizeDebounceMs = prefs.getInt(
                Key.KeyboardResizeDebounceMs,
                DefaultKeyboardResizeDebounceMillis,
            ),
            unconditionalSoftKeyboardOnTap = prefs.getBoolean(Key.UnconditionalSoftKeyboardOnTap, true),
            autoShowKeyboardOnTap = prefs.getBoolean(Key.AutoShowKeyboardOnTap, true),
            cursorTrail = CursorTrailEffect.fromPref(prefs.getString(Key.CursorTrail, null)),
            visualEffectFrameRate = VisualEffectFrameRate.fromPref(prefs.getString(Key.VisualEffectFrameRate, null)),
            customFontName = prefs.getString(Key.CustomFontName, null),
            useCustomFontForWholeUi = prefs.getBoolean(Key.UseCustomFontForWholeUi, false),
            nativeLogcatLoggingEnabled = prefs.getBoolean(Key.NativeLogcatLoggingEnabled, false),
            debugHudEnabled = prefs.getBoolean(Key.DebugHudEnabled, false),
            animationsEnabled = prefs.getBoolean(Key.AnimationsEnabled, true),
            rememberSoftKeyboardState = prefs.getBoolean(Key.RememberSoftKeyboardState, false),
            lastSoftKeyboardState = SoftKeyboardState.fromPreference(prefs.getString(Key.LastSoftKeyboardState, null)),
            arrowJoystickMode = ArrowJoystickMode.entries.firstOrNull {
                it.name == prefs.getString(Key.ArrowJoystickMode, null)
            } ?: ArrowJoystickMode.Swipe,
            overlayKeyBar = prefs.getBoolean(Key.OverlayKeyBar, false),
            keyBarLayout = loadKeyBarLayout(prefs),
            wallpaperUri = prefs.getString(Key.WallpaperUri, null),
            wallpaperName = prefs.getString(Key.WallpaperName, null),
            wallpaperEnabled = prefs.getBoolean(Key.WallpaperEnabled, true),
            wallpaperOpacity = prefs.getFloat(Key.WallpaperOpacity, TerminalWallpaperConfig.DefaultBackgroundOpacity),
            wallpaperScaling = wallpaperScalingFromPref(prefs.getString(Key.WallpaperScaling, null)),
            wallpaperId = prefs.getLong(Key.WallpaperId, 0L),
        ),
    )

    private fun normalize(settings: AppSettings): AppSettings = settings.copy(
        fontSize = settings.fontSize.coerceIn(fontSizeRange),
        keyboardResizeDebounceMs = settings.keyboardResizeDebounceMs.coerceIn(0, MaxKeyboardResizeDebounceMillis),
        lastSoftKeyboardState = settings.lastSoftKeyboardState.takeIf { settings.rememberSoftKeyboardState }
            ?: SoftKeyboardState.UNKNOWN,
        wallpaperOpacity = settings.wallpaperOpacity.coerceIn(0f, 1f),
        keyBarLayout = settings.keyBarLayout.normalized(),
    )
}
