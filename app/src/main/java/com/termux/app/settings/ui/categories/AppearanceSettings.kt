package com.termux.app.settings.ui.categories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.settings.EffectFrameRate
import com.termux.app.settings.MaterialYouTheme
import com.termux.app.settings.NightModeSetting
import com.termux.app.settings.ui.ChoiceRow
import com.termux.app.settings.ui.NumberRow
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.app.settings.ui.SwitchRow

fun LazyListScope.appearanceSettings(state: SettingsState) {
    item {
        SettingsGroup(
            rows = listOf(
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.termux_ghostty_material_you_theme_title),
                        options = listOf(
                            MaterialYouTheme.DISABLED.name to stringResource(R.string.settings_material_you_disabled),
                            MaterialYouTheme.LIGHT.name to stringResource(R.string.settings_material_you_light),
                            MaterialYouTheme.DARK.name to stringResource(R.string.settings_material_you_dark),
                            MaterialYouTheme.BLACK.name to stringResource(R.string.settings_material_you_black),
                            MaterialYouTheme.SYSTEM.name to stringResource(R.string.settings_option_system),
                        ),
                        selected = state.settings.materialYouTheme.name,
                        onSelect = { value -> state.save { it.copy(materialYouTheme = MaterialYouTheme.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("material-you-theme"),
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.settings_night_mode),
                        options = listOf(
                            NightModeSetting.TRUE.name to stringResource(R.string.settings_option_on),
                            NightModeSetting.FALSE.name to stringResource(R.string.settings_option_off),
                            NightModeSetting.SYSTEM.name to stringResource(R.string.settings_option_system),
                        ),
                        selected = state.settings.nightMode.name,
                        onSelect = { value -> state.save { it.copy(nightMode = NightModeSetting.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("night-mode"),
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_fullscreen),
                        checked = state.settings.fullscreen,
                        onChange = { checked -> state.save { it.copy(fullscreen = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("fullscreen"),
                    )
                },
                { shapes ->
                    NumberRow(
                        title = stringResource(R.string.settings_margin_horizontal),
                        value = state.settings.terminalMarginHorizontal,
                        range = 0f..100f,
                        isFloat = false,
                        onChange = { value -> state.save { it.copy(terminalMarginHorizontal = value.toInt()) } },
                        shapes = shapes,
                        locked = state.isLocked("terminal-margin-horizontal"),
                    )
                },
                { shapes ->
                    NumberRow(
                        title = stringResource(R.string.settings_margin_vertical),
                        value = state.settings.terminalMarginVertical,
                        range = 0f..100f,
                        isFloat = false,
                        onChange = { value -> state.save { it.copy(terminalMarginVertical = value.toInt()) } },
                        shapes = shapes,
                        locked = state.isLocked("terminal-margin-vertical"),
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_animations),
                        checked = state.settings.animations,
                        onChange = { checked -> state.save { it.copy(animations = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("animations"),
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.settings_visual_effect_frame_rate),
                        options = listOf(
                            EffectFrameRate.VSYNC.name to stringResource(R.string.settings_frame_rate_vsync),
                            EffectFrameRate.FPS_30.name to stringResource(R.string.settings_frame_rate_30),
                            EffectFrameRate.FPS_60.name to stringResource(R.string.settings_frame_rate_60),
                            EffectFrameRate.FPS_90.name to stringResource(R.string.settings_frame_rate_90),
                            EffectFrameRate.FPS_120.name to stringResource(R.string.settings_frame_rate_120),
                        ),
                        selected = state.settings.visualEffectFrameRate.name,
                        onSelect = { value -> state.save { it.copy(visualEffectFrameRate = EffectFrameRate.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("visual-effect-frame-rate"),
                    )
                },
            ),
        )
    }
}
