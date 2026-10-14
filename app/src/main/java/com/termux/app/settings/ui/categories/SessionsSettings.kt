package com.termux.app.settings.ui.categories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.app.settings.ui.SwitchRow

fun LazyListScope.sessionsSettings(state: SettingsState) {
    item {
        SettingsGroup(
            rows = listOf(
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
