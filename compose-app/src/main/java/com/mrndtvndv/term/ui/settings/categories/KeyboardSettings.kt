package com.mrndtvndv.term.ui.settings.categories

import androidx.compose.foundation.lazy.LazyListScope
import com.mrndtvndv.term.data.prefs.ArrowJoystickMode
import com.mrndtvndv.term.ui.settings.ChoiceRow
import com.mrndtvndv.term.ui.settings.NavigationRow
import com.mrndtvndv.term.ui.settings.SettingsGroup
import com.mrndtvndv.term.ui.settings.SettingsState
import com.mrndtvndv.term.ui.settings.SwitchRow

@Suppress("LongMethod")
fun LazyListScope.keyboardSettings(state: SettingsState) {
    item {
        SettingsGroup(
            title = "Soft keyboard",
            rows = listOf(
                { shapes ->
                    SwitchRow(
                        title = "Auto-show keyboard on tap",
                        checked = state.settings.autoShowKeyboardOnTap,
                        onChange = { checked -> state.onUpdate { it.copy(autoShowKeyboardOnTap = checked) } },
                        shapes = shapes,
                        summary = "Automatically open the soft keyboard when tapping the terminal",
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = "Unconditional keyboard on tap",
                        checked = state.settings.unconditionalSoftKeyboardOnTap,
                        onChange = { checked ->
                            state.onUpdate { it.copy(unconditionalSoftKeyboardOnTap = checked) }
                        },
                        shapes = shapes,
                        summary = "Show soft keyboard on tap even when terminal mouse tracking (e.g. tmux) is active",
                        enabled = state.settings.autoShowKeyboardOnTap,
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = "Remember keyboard state",
                        checked = state.settings.rememberSoftKeyboardState,
                        onChange = { checked -> state.onUpdate { it.copy(rememberSoftKeyboardState = checked) } },
                        shapes = shapes,
                        summary = "Restore the terminal keyboard's last visibility when reopening it",
                    )
                },
            ),
        )
    }
    item {
        SettingsGroup(
            title = "Key bar",
            rows = listOf(
                { shapes ->
                    NavigationRow(
                        title = "Key bar",
                        summary = "${state.settings.keyBarLayout.items.size} keys · " +
                            "Customize the keys above the keyboard",
                        shapes = shapes,
                        onClick = state.onEditKeyBar,
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = "Overlay key bar",
                        checked = state.settings.overlayKeyBar,
                        onChange = { checked -> state.onUpdate { it.copy(overlayKeyBar = checked) } },
                        shapes = shapes,
                        summary = "Let terminal rows extend behind the key bar instead of stopping above it",
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = "Arrow joystick",
                        options = listOf(
                            ArrowJoystickMode.Swipe to "Swipe (step per drag)",
                            ArrowJoystickMode.Hold to "Hold (repeat while held)",
                        ),
                        selected = state.settings.arrowJoystickMode,
                        onSelect = { mode -> state.onUpdate { it.copy(arrowJoystickMode = mode) } },
                        shapes = shapes,
                    )
                },
            ),
        )
    }
}
