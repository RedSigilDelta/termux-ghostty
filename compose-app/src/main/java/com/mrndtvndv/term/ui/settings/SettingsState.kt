package com.mrndtvndv.term.ui.settings

import com.mrndtvndv.term.data.prefs.AppSettings

@Suppress("LongParameterList")
class SettingsState(
    val settings: AppSettings,
    val fontSizeRange: IntRange,
    val onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    val onSelectFont: () -> Unit,
    val onClearFont: () -> Unit,
    val onSelectWallpaper: () -> Unit,
    val onClearWallpaper: () -> Unit,
    val onEditKeyBar: () -> Unit,
)
