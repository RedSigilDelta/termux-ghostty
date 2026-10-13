package com.termux.app.settings.ui.categories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.settings.BackKeyBehaviour
import com.termux.app.settings.TermuxSettings
import com.termux.app.settings.VolumeKeysBehaviour
import com.termux.app.settings.ui.ChoiceRow
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.app.settings.ui.SwitchRow
import com.termux.app.settings.ui.TextRow

fun LazyListScope.shortcutsSettings(state: SettingsState) {
    item {
        SettingsGroup(
            rows = listOf(
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.settings_back_key),
                        options = listOf(
                            BackKeyBehaviour.BACK.name to stringResource(R.string.settings_back_key_back),
                            BackKeyBehaviour.ESCAPE.name to stringResource(R.string.settings_back_key_escape),
                        ),
                        selected = state.settings.backKey.name,
                        onSelect = { value -> state.save { it.copy(backKey = BackKeyBehaviour.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("back-key"),
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.settings_volume_keys),
                        options = listOf(
                            VolumeKeysBehaviour.VIRTUAL.name to stringResource(R.string.settings_volume_keys_virtual),
                            VolumeKeysBehaviour.VOLUME.name to stringResource(R.string.settings_volume_keys_volume),
                        ),
                        selected = state.settings.volumeKeys.name,
                        onSelect = { value -> state.save { it.copy(volumeKeys = VolumeKeysBehaviour.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("volume-keys"),
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_disable_hardware_shortcuts),
                        checked = state.settings.disableHardwareKeyboardShortcuts,
                        onChange = { checked -> state.save { it.copy(disableHardwareKeyboardShortcuts = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("disable-hardware-keyboard-shortcuts"),
                    )
                },
                { shapes ->
                    TextRow(
                        title = stringResource(R.string.settings_shortcut_create_session),
                        value = state.settings.shortcutCreateSession,
                        multiline = false,
                        validate = { validateShortcut(it, state.context.getString(R.string.settings_invalid_shortcut)) },
                        onChange = { value -> state.save { it.copy(shortcutCreateSession = value.ifEmpty { null }) } },
                        shapes = shapes,
                        locked = state.isLocked("shortcut-create-session"),
                    )
                },
                { shapes ->
                    TextRow(
                        title = stringResource(R.string.settings_shortcut_next_session),
                        value = state.settings.shortcutNextSession,
                        multiline = false,
                        validate = { validateShortcut(it, state.context.getString(R.string.settings_invalid_shortcut)) },
                        onChange = { value -> state.save { it.copy(shortcutNextSession = value.ifEmpty { null }) } },
                        shapes = shapes,
                        locked = state.isLocked("shortcut-next-session"),
                    )
                },
                { shapes ->
                    TextRow(
                        title = stringResource(R.string.settings_shortcut_previous_session),
                        value = state.settings.shortcutPreviousSession,
                        multiline = false,
                        validate = { validateShortcut(it, state.context.getString(R.string.settings_invalid_shortcut)) },
                        onChange = { value -> state.save { it.copy(shortcutPreviousSession = value.ifEmpty { null }) } },
                        shapes = shapes,
                        locked = state.isLocked("shortcut-previous-session"),
                    )
                },
                { shapes ->
                    TextRow(
                        title = stringResource(R.string.settings_shortcut_rename_session),
                        value = state.settings.shortcutRenameSession,
                        multiline = false,
                        validate = { validateShortcut(it, state.context.getString(R.string.settings_invalid_shortcut)) },
                        onChange = { value -> state.save { it.copy(shortcutRenameSession = value.ifEmpty { null }) } },
                        shapes = shapes,
                        locked = state.isLocked("shortcut-rename-session"),
                    )
                },
            ),
        )
    }
}

private fun validateShortcut(text: String, invalid: String): String? =
    if (text.isEmpty() || TermuxSettings.shortcutCodePoint(text) != null) null else invalid
