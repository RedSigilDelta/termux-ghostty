package com.termux.app.settings.ui.categories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.settings.SoftKeyboardToggleBehaviour
import com.termux.app.settings.ui.ChoiceRow
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.app.settings.ui.SwitchRow

fun LazyListScope.keyboardSettings(state: SettingsState) {
    item {
        SettingsGroup(
            title = stringResource(R.string.settings_group_soft_keyboard),
            rows = listOf(
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_hide_keyboard_on_startup),
                        checked = state.settings.hideSoftKeyboardOnStartup,
                        onChange = { checked -> state.save { it.copy(hideSoftKeyboardOnStartup = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("hide-soft-keyboard-on-startup"),
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.termux_ghostty_remember_soft_keyboard_state_title),
                        checked = state.settings.rememberSoftKeyboardState,
                        onChange = { checked -> state.save { it.copy(rememberSoftKeyboardState = checked) } },
                        summary = stringResource(R.string.termux_ghostty_remember_soft_keyboard_state_summary),
                        shapes = shapes,
                        locked = state.isLocked("remember-soft-keyboard-state"),
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.termux_ghostty_unconditional_soft_keyboard_on_tap_title),
                        checked = state.settings.unconditionalSoftKeyboardOnTap,
                        onChange = { checked -> state.save { it.copy(unconditionalSoftKeyboardOnTap = checked) } },
                        summary = stringResource(R.string.termux_ghostty_unconditional_soft_keyboard_on_tap_summary),
                        shapes = shapes,
                        locked = state.isLocked("unconditional-soft-keyboard-on-tap"),
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.settings_soft_keyboard_toggle),
                        options = listOf(
                            SoftKeyboardToggleBehaviour.SHOW_HIDE.name to stringResource(R.string.settings_toggle_show_hide),
                            SoftKeyboardToggleBehaviour.ENABLE_DISABLE.name to stringResource(R.string.settings_toggle_enable_disable),
                        ),
                        selected = state.settings.softKeyboardToggleBehaviour.name,
                        onSelect = { value -> state.save { it.copy(softKeyboardToggleBehaviour = SoftKeyboardToggleBehaviour.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("soft-keyboard-toggle-behaviour"),
                    )
                },
                { shapes ->
                    PrefSwitchRow(
                        title = stringResource(R.string.termux_soft_keyboard_enabled_title),
                        checked = state.appPrefs?.isSoftKeyboardEnabled() ?: false,
                        summaryOn = stringResource(R.string.termux_soft_keyboard_enabled_on),
                        summaryOff = stringResource(R.string.termux_soft_keyboard_enabled_off),
                        onChange = { state.appPrefs?.setSoftKeyboardEnabled(it) },
                        shapes = shapes,
                    )
                },
                { shapes ->
                    PrefSwitchRow(
                        title = stringResource(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_title),
                        checked = state.appPrefs?.isSoftKeyboardEnabledOnlyIfNoHardware() ?: false,
                        summaryOn = stringResource(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_on),
                        summaryOff = stringResource(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_off),
                        onChange = { state.appPrefs?.setSoftKeyboardEnabledOnlyIfNoHardware(it) },
                        shapes = shapes,
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_enforce_char_input),
                        checked = state.settings.enforceCharBasedInput,
                        onChange = { checked -> state.save { it.copy(enforceCharBasedInput = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("enforce-char-based-input"),
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_ctrl_space_workaround),
                        checked = state.settings.ctrlSpaceWorkaround,
                        onChange = { checked -> state.save { it.copy(ctrlSpaceWorkaround = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("ctrl-space-workaround"),
                    )
                },
            ),
        )
    }
}
