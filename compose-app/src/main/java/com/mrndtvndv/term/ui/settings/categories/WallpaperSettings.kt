package com.mrndtvndv.term.ui.settings.categories

import androidx.compose.foundation.lazy.LazyListScope
import com.mrndtvndv.term.ui.settings.ActionRow
import com.mrndtvndv.term.ui.settings.ChoiceRow
import com.mrndtvndv.term.ui.settings.NumberRow
import com.mrndtvndv.term.ui.settings.SettingRowContent
import com.mrndtvndv.term.ui.settings.SettingsGroup
import com.mrndtvndv.term.ui.settings.SettingsState
import com.mrndtvndv.term.ui.settings.SwitchRow
import com.mrndtvndv.term.ui.workspace.label
import com.termux.terminal.compose.WallpaperScaling

@Suppress("LongMethod")
fun LazyListScope.wallpaperSettings(state: SettingsState) {
    val removeWallpaperRow: SettingRowContent? = if (state.settings.wallpaperName != null) {
        { shapes ->
            ActionRow(
                title = "Remove wallpaper",
                shapes = shapes,
                onClick = state.onClearWallpaper,
            )
        }
    } else {
        null
    }
    val wallpaperAvailable = state.settings.wallpaperName != null
    val wallpaperEnabled = wallpaperAvailable && state.settings.wallpaperEnabled

    item {
        SettingsGroup(
            rows = listOfNotNull(
                { shapes ->
                    ActionRow(
                        title = "Choose wallpaper",
                        summary = state.settings.wallpaperName ?: "Not set",
                        shapes = shapes,
                        onClick = state.onSelectWallpaper,
                    )
                },
                removeWallpaperRow,
                { shapes ->
                    SwitchRow(
                        title = "Show wallpaper",
                        checked = state.settings.wallpaperEnabled,
                        onChange = { checked -> state.onUpdate { it.copy(wallpaperEnabled = checked) } },
                        shapes = shapes,
                        summary = "Draw an image behind default terminal backgrounds",
                        enabled = wallpaperAvailable,
                    )
                },
                { shapes ->
                    NumberRow(
                        title = "Wallpaper background opacity",
                        value = state.settings.wallpaperOpacity,
                        range = 0f..1f,
                        isFloat = true,
                        onChange = { value -> state.onUpdate { it.copy(wallpaperOpacity = value.toFloat()) } },
                        shapes = shapes,
                        enabled = wallpaperEnabled,
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = "Wallpaper scaling",
                        options = WallpaperScaling.entries.map { it to it.label() },
                        selected = state.settings.wallpaperScaling,
                        onSelect = { scaling -> state.onUpdate { it.copy(wallpaperScaling = scaling) } },
                        shapes = shapes,
                        enabled = wallpaperEnabled,
                    )
                },
            ),
        )
    }
}
