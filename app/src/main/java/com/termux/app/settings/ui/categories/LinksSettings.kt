package com.termux.app.settings.ui.categories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.app.settings.ui.SwitchRow

fun LazyListScope.linksSettings(state: SettingsState) {
    item {
        SettingsGroup(
            rows = listOf(
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.termux_ghostty_terminal_onclick_url_open_title),
                        checked = state.settings.terminalOnclickUrlOpen,
                        onChange = { checked -> state.save { it.copy(terminalOnclickUrlOpen = checked) } },
                        summary = stringResource(R.string.termux_ghostty_terminal_onclick_url_open_summary),
                        shapes = shapes,
                        locked = state.isLocked("terminal-onclick-url-open"),
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.termux_ghostty_terminal_onclick_url_open_when_mouse_tracking_active_title),
                        checked = state.settings.terminalOnclickUrlOpenWhenMouseTrackingActive,
                        onChange = { checked -> state.save { it.copy(terminalOnclickUrlOpenWhenMouseTrackingActive = checked) } },
                        summary = stringResource(R.string.termux_ghostty_terminal_onclick_url_open_when_mouse_tracking_active_summary),
                        shapes = shapes,
                        locked = state.isLocked("terminal-onclick-url-open-when-mouse-tracking-active"),
                        enabled = state.settings.terminalOnclickUrlOpen,
                    )
                },
            ),
        )
    }
}
