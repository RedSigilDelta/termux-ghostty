package com.termux.app.settings.ui.categories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.settings.ui.NumberRow
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.app.settings.ui.SwitchRow

fun LazyListScope.integrationsSettings(state: SettingsState) {
    item {
        SettingsGroup(
            title = stringResource(R.string.settings_section_integration),
            rows = listOf(
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_allow_external_apps),
                        checked = state.settings.allowExternalApps,
                        onChange = {},
                        summary = stringResource(R.string.settings_only_in_config_file),
                        shapes = shapes,
                        enabled = false,
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_run_am_socket_server),
                        checked = state.settings.runTermuxAmSocketServer,
                        onChange = { checked -> state.save { it.copy(runTermuxAmSocketServer = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("run-termux-am-socket-server"),
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_disable_file_share_receiver),
                        checked = state.settings.disableFileShareReceiver,
                        onChange = { checked -> state.save { it.copy(disableFileShareReceiver = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("disable-file-share-receiver"),
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_disable_file_view_receiver),
                        checked = state.settings.disableFileViewReceiver,
                        onChange = { checked -> state.save { it.copy(disableFileViewReceiver = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("disable-file-view-receiver"),
                    )
                },
                { shapes ->
                    NumberRow(
                        title = stringResource(R.string.settings_delete_tmpdir_days),
                        value = state.settings.deleteTmpdirFilesOlderThanXDaysOnExit,
                        range = -1f..100000f,
                        isFloat = false,
                        onChange = { value -> state.save { it.copy(deleteTmpdirFilesOlderThanXDaysOnExit = value.toInt()) } },
                        shapes = shapes,
                        locked = state.isLocked("delete-tmpdir-files-older-than-x-days-on-exit"),
                    )
                },
            ),
        )
    }
}
