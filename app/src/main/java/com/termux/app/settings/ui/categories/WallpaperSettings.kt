package com.termux.app.settings.ui.categories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.settings.WallpaperScalingSetting
import com.termux.app.settings.ui.ActionRow
import com.termux.app.settings.ui.ChoiceRow
import com.termux.app.settings.ui.NumberRow
import com.termux.app.settings.ui.SettingRowContent
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.app.settings.ui.SwitchRow

fun LazyListScope.wallpaperSettings(state: SettingsState) {
    val removeWallpaperRow: SettingRowContent? = if (state.wallpaperSet) {
        { shapes ->
            ActionRow(
                title = stringResource(R.string.settings_remove_wallpaper),
                shapes = shapes,
                onClick = state.removeWallpaper,
            )
        }
    } else {
        null
    }
    item {
        SettingsGroup(
            rows = listOfNotNull(
                { shapes ->
                    ActionRow(
                        title = stringResource(R.string.settings_choose_wallpaper),
                        shapes = shapes,
                        summary = stringResource(if (state.wallpaperSet) R.string.settings_file_set else R.string.settings_file_not_set),
                        onClick = state.pickWallpaper,
                    )
                },
                removeWallpaperRow,
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_wallpaper),
                        checked = state.settings.wallpaper,
                        onChange = { checked -> state.save { it.copy(wallpaper = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("wallpaper"),
                    )
                },
                { shapes ->
                    NumberRow(
                        title = stringResource(R.string.settings_wallpaper_background_opacity),
                        value = state.settings.wallpaperBackgroundOpacity,
                        range = 0f..1f,
                        isFloat = true,
                        onChange = { value -> state.save { it.copy(wallpaperBackgroundOpacity = value.toFloat()) } },
                        shapes = shapes,
                        locked = state.isLocked("wallpaper-background-opacity"),
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.settings_wallpaper_scaling),
                        options = listOf(
                            WallpaperScalingSetting.CENTER_CROP.name to stringResource(R.string.settings_wallpaper_scaling_crop),
                            WallpaperScalingSetting.FIT_CENTER.name to stringResource(R.string.settings_wallpaper_scaling_fit),
                            WallpaperScalingSetting.FIT_XY.name to stringResource(R.string.settings_wallpaper_scaling_stretch),
                            WallpaperScalingSetting.CENTER.name to stringResource(R.string.settings_wallpaper_scaling_center),
                        ),
                        selected = state.settings.wallpaperScaling.name,
                        onSelect = { value -> state.save { it.copy(wallpaperScaling = WallpaperScalingSetting.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("wallpaper-scaling"),
                    )
                },
            ),
        )
    }
}
