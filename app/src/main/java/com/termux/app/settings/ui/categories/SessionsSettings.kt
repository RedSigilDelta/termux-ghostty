package com.termux.app.settings.ui.categories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.settings.TabBarAlign
import com.termux.app.settings.TabBarPosition
import com.termux.app.settings.ui.ChoiceRow
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.app.settings.ui.SwitchRow

fun LazyListScope.sessionsSettings(state: SettingsState) {
    item {
        SettingsGroup(
            rows = listOf(
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.termux_ghostty_use_session_tabs_title),
                        checked = state.settings.useSessionTabs,
                        onChange = { checked -> state.save { it.copy(useSessionTabs = checked) } },
                        summary = stringResource(R.string.termux_ghostty_use_session_tabs_summary),
                        shapes = shapes,
                        locked = state.isLocked("use-session-tabs"),
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.termux_ghostty_session_tab_bar_position_title),
                        options = listOf(
                            TabBarPosition.BOTTOM.name to stringResource(R.string.settings_tab_bar_bottom),
                            TabBarPosition.TOP.name to stringResource(R.string.settings_tab_bar_top),
                        ),
                        selected = state.settings.sessionTabBarPosition.name,
                        onSelect = { value -> state.save { it.copy(sessionTabBarPosition = TabBarPosition.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("session-tab-bar-position"),
                        enabled = state.settings.useSessionTabs,
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.termux_ghostty_session_tab_bar_align_title),
                        options = listOf(
                            TabBarAlign.LEFT.name to stringResource(R.string.settings_tab_align_left),
                            TabBarAlign.CENTER.name to stringResource(R.string.settings_tab_align_center),
                            TabBarAlign.RIGHT.name to stringResource(R.string.settings_tab_align_right),
                        ),
                        selected = state.settings.sessionTabBarAlign.name,
                        onSelect = { value -> state.save { it.copy(sessionTabBarAlign = TabBarAlign.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("session-tab-bar-align"),
                        enabled = state.settings.useSessionTabs,
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_disable_session_change_toast),
                        checked = state.settings.disableTerminalSessionChangeToast,
                        onChange = { checked -> state.save { it.copy(disableTerminalSessionChangeToast = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("disable-terminal-session-change-toast"),
                    )
                },
            ),
        )
    }
}
