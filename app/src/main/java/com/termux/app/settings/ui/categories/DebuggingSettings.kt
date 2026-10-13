package com.termux.app.settings.ui.categories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.settings.ui.SettingRowContent
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.shared.logger.Logger

fun LazyListScope.debuggingSettings(state: SettingsState) {
    val hasPlugins = listOfNotNull(state.availability?.api, state.availability?.float, state.availability?.tasker, state.availability?.widget).isNotEmpty()
    val pluginRows = buildList<SettingRowContent> {
        state.availability?.api?.let { api ->
            add { shapes ->
                LogLevelRow(
                    title = stringResource(R.string.settings_plugin_log_level, stringResource(R.string.termux_api_preferences_title)),
                    level = api.getLogLevel(true),
                    onSelect = { api.setLogLevel(state.context, it, true) },
                    shapes = shapes,
                )
            }
        }
        state.availability?.float?.let { float ->
            add { shapes ->
                LogLevelRow(
                    title = stringResource(R.string.settings_plugin_log_level, stringResource(R.string.termux_float_preferences_title)),
                    level = float.getLogLevel(true),
                    onSelect = { float.setLogLevel(state.context, it, true) },
                    shapes = shapes,
                )
            }
            add { shapes ->
                PrefSwitchRow(
                    title = stringResource(R.string.settings_plugin_key_logging, stringResource(R.string.termux_float_preferences_title)),
                    checked = float.isTerminalViewKeyLoggingEnabled(true),
                    summaryOn = stringResource(R.string.termux_terminal_view_key_logging_enabled_on),
                    summaryOff = stringResource(R.string.termux_terminal_view_key_logging_enabled_off),
                    onChange = { float.setTerminalViewKeyLoggingEnabled(it, true) },
                    shapes = shapes,
                )
            }
        }
        state.availability?.tasker?.let { tasker ->
            add { shapes ->
                LogLevelRow(
                    title = stringResource(R.string.settings_plugin_log_level, stringResource(R.string.termux_tasker_preferences_title)),
                    level = tasker.getLogLevel(true),
                    onSelect = { tasker.setLogLevel(state.context, it, true) },
                    shapes = shapes,
                )
            }
        }
        state.availability?.widget?.let { widget ->
            add { shapes ->
                LogLevelRow(
                    title = stringResource(R.string.settings_plugin_log_level, stringResource(R.string.termux_widget_preferences_title)),
                    level = widget.getLogLevel(true),
                    onSelect = { widget.setLogLevel(state.context, it, true) },
                    shapes = shapes,
                )
            }
        }
    }

    item {
        SettingsGroup(
            title = stringResource(R.string.termux_debugging_preferences_title),
            rows = listOf(
                { shapes ->
                    LogLevelRow(
                        title = stringResource(R.string.termux_log_level_title),
                        level = state.appPrefs?.getLogLevel() ?: Logger.DEFAULT_LOG_LEVEL,
                        onSelect = { state.appPrefs?.setLogLevel(state.context, it) },
                        shapes = shapes,
                    )
                },
                { shapes ->
                    PrefSwitchRow(
                        title = stringResource(R.string.termux_terminal_view_key_logging_enabled_title),
                        checked = state.appPrefs?.isTerminalViewKeyLoggingEnabled() ?: false,
                        summaryOn = stringResource(R.string.termux_terminal_view_key_logging_enabled_on),
                        summaryOff = stringResource(R.string.termux_terminal_view_key_logging_enabled_off),
                        onChange = { state.appPrefs?.setTerminalViewKeyLoggingEnabled(it) },
                        shapes = shapes,
                    )
                },
                { shapes ->
                    PrefSwitchRow(
                        title = stringResource(R.string.termux_plugin_error_notifications_enabled_title),
                        checked = state.appPrefs?.arePluginErrorNotificationsEnabled(false) ?: false,
                        summaryOn = stringResource(R.string.termux_plugin_error_notifications_enabled_on),
                        summaryOff = stringResource(R.string.termux_plugin_error_notifications_enabled_off),
                        onChange = { state.appPrefs?.setPluginErrorNotificationsEnabled(it) },
                        shapes = shapes,
                    )
                },
                { shapes ->
                    PrefSwitchRow(
                        title = stringResource(R.string.termux_crash_report_notifications_enabled_title),
                        checked = state.appPrefs?.areCrashReportNotificationsEnabled(false) ?: false,
                        summaryOn = stringResource(R.string.termux_crash_report_notifications_enabled_on),
                        summaryOff = stringResource(R.string.termux_crash_report_notifications_enabled_off),
                        onChange = { state.appPrefs?.setCrashReportNotificationsEnabled(it) },
                        shapes = shapes,
                    )
                },
            ),
        )
    }
    if (hasPlugins) item {
        SettingsGroup(title = stringResource(R.string.settings_section_plugins), rows = pluginRows)
    }
}
