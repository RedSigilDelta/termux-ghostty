package com.termux.app.settings.ui

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.models.UserAction
import com.termux.app.settings.BackKeyBehaviour
import com.termux.app.settings.BellBehaviour
import com.termux.app.settings.ConfigErrorsDialog
import com.termux.app.settings.CursorStyle
import com.termux.app.settings.MaterialYouTheme
import com.termux.app.settings.NightModeSetting
import com.termux.app.settings.SoftKeyboardToggleBehaviour
import com.termux.app.settings.TabBarAlign
import com.termux.app.settings.TabBarPosition
import com.termux.app.settings.TermuxSettings
import com.termux.app.settings.TermuxSettingsRepository
import com.termux.app.settings.VolumeKeysBehaviour
import com.termux.shared.activities.ReportActivity
import com.termux.shared.android.AndroidUtils
import com.termux.shared.android.PackageUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.logger.Logger
import com.termux.shared.models.ReportInfo
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils
import com.termux.shared.termux.extrakeys.ExtraKeysConstants
import com.termux.shared.termux.extrakeys.ExtraKeysInfo
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences
import com.termux.terminal.TerminalConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

private val TRANSCRIPT_ROWS_RANGE = TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MIN.toFloat()..TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MAX.toFloat()
private val BLINK_RATE_RANGE = TerminalConstants.TERMINAL_CURSOR_BLINK_RATE_MIN.toFloat()..TerminalConstants.TERMINAL_CURSOR_BLINK_RATE_MAX.toFloat()

private class Availability(
    val api: TermuxAPIAppSharedPreferences?,
    val float: TermuxFloatAppSharedPreferences?,
    val tasker: TermuxTaskerAppSharedPreferences?,
    val widget: TermuxWidgetAppSharedPreferences?,
    val donate: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = TermuxSettingsRepository.get()
    val effective by repository.effective.collectAsState()
    val settings = effective.settings
    val appPrefs: TermuxAppSharedPreferences? = remember { TermuxAppSharedPreferences.build(context, true) }
    val availability by produceState<Availability?>(null) {
        value = withContext(Dispatchers.IO) { loadAvailability(context) }
    }
    var showErrors by remember { mutableStateOf(false) }
    val invalidExtraKeys = stringResource(R.string.settings_invalid_extra_keys)
    val invalidShortcut = stringResource(R.string.settings_invalid_shortcut)
    val hasPlugins = listOfNotNull(availability?.api, availability?.float, availability?.tasker, availability?.widget).isNotEmpty()

    fun isLocked(key: String) = key in effective.fileKeys

    fun save(transform: (TermuxSettings) -> TermuxSettings) {
        try {
            repository.update(transform)
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, context.getString(R.string.settings_invalid_value, e.message), Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_activity_termux_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.padding(paddingValues)) {
            item { SectionHeader(stringResource(R.string.settings_section_config_file)) }
            item { ActionRow(title = TermuxConstants.TERMUX_SETTINGS_FILE_PATH, summary = stringResource(R.string.settings_file_keys_count, effective.fileKeys.size)) }
            if (effective.errors.isNotEmpty()) {
                item { ActionRow(title = stringResource(R.string.settings_file_problems, effective.errors.size), onClick = { showErrors = true }) }
            }
            item { ActionRow(title = stringResource(R.string.settings_reload_config), onClick = { repository.reload() }) }

            item { SectionHeader(stringResource(R.string.settings_section_appearance)) }
            item {
                ChoiceRow(
                    title = stringResource(R.string.termux_ghostty_material_you_theme_title),
                    options = listOf(
                        MaterialYouTheme.DISABLED.name to stringResource(R.string.settings_material_you_disabled),
                        MaterialYouTheme.LIGHT.name to stringResource(R.string.settings_material_you_light),
                        MaterialYouTheme.DARK.name to stringResource(R.string.settings_material_you_dark),
                        MaterialYouTheme.BLACK.name to stringResource(R.string.settings_material_you_black),
                        MaterialYouTheme.SYSTEM.name to stringResource(R.string.settings_option_system),
                    ),
                    selected = settings.materialYouTheme.name,
                    onSelect = { value -> save { it.copy(materialYouTheme = MaterialYouTheme.valueOf(value)) } },
                    locked = isLocked("material-you-theme"),
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.settings_night_mode),
                    options = listOf(
                        NightModeSetting.TRUE.name to stringResource(R.string.settings_option_on),
                        NightModeSetting.FALSE.name to stringResource(R.string.settings_option_off),
                        NightModeSetting.SYSTEM.name to stringResource(R.string.settings_option_system),
                    ),
                    selected = settings.nightMode.name,
                    onSelect = { value -> save { it.copy(nightMode = NightModeSetting.valueOf(value)) } },
                    locked = isLocked("night-mode"),
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.settings_cursor_style),
                    options = listOf(
                        CursorStyle.BLOCK.name to stringResource(R.string.settings_cursor_block),
                        CursorStyle.UNDERLINE.name to stringResource(R.string.settings_cursor_underline),
                        CursorStyle.BAR.name to stringResource(R.string.settings_cursor_bar),
                    ),
                    selected = settings.terminalCursorStyle.name,
                    onSelect = { value -> save { it.copy(terminalCursorStyle = CursorStyle.valueOf(value)) } },
                    locked = isLocked("terminal-cursor-style"),
                )
            }
            item {
                NumberRow(
                    title = stringResource(R.string.settings_cursor_blink_rate),
                    value = settings.terminalCursorBlinkRate,
                    range = BLINK_RATE_RANGE,
                    isFloat = false,
                    onChange = { value -> save { it.copy(terminalCursorBlinkRate = value.toInt()) } },
                    locked = isLocked("terminal-cursor-blink-rate"),
                    isValid = { it.toInt() == 0 || it.toFloat() in BLINK_RATE_RANGE },
                )
            }
            item {
                NumberRow(
                    title = stringResource(R.string.settings_margin_horizontal),
                    value = settings.terminalMarginHorizontal,
                    range = 0f..100f,
                    isFloat = false,
                    onChange = { value -> save { it.copy(terminalMarginHorizontal = value.toInt()) } },
                    locked = isLocked("terminal-margin-horizontal"),
                )
            }
            item {
                NumberRow(
                    title = stringResource(R.string.settings_margin_vertical),
                    value = settings.terminalMarginVertical,
                    range = 0f..100f,
                    isFloat = false,
                    onChange = { value -> save { it.copy(terminalMarginVertical = value.toInt()) } },
                    locked = isLocked("terminal-margin-vertical"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_fullscreen),
                    checked = settings.fullscreen,
                    onChange = { checked -> save { it.copy(fullscreen = checked) } },
                    locked = isLocked("fullscreen"),
                )
            }

            item { SectionHeader(stringResource(R.string.settings_section_sessions)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.termux_ghostty_use_session_tabs_title),
                    checked = settings.useSessionTabs,
                    onChange = { checked -> save { it.copy(useSessionTabs = checked) } },
                    summary = stringResource(R.string.termux_ghostty_use_session_tabs_summary),
                    locked = isLocked("use-session-tabs"),
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.termux_ghostty_session_tab_bar_position_title),
                    options = listOf(
                        TabBarPosition.BOTTOM.name to stringResource(R.string.settings_tab_bar_bottom),
                        TabBarPosition.TOP.name to stringResource(R.string.settings_tab_bar_top),
                    ),
                    selected = settings.sessionTabBarPosition.name,
                    onSelect = { value -> save { it.copy(sessionTabBarPosition = TabBarPosition.valueOf(value)) } },
                    locked = isLocked("session-tab-bar-position"),
                    enabled = settings.useSessionTabs,
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.termux_ghostty_session_tab_bar_align_title),
                    options = listOf(
                        TabBarAlign.LEFT.name to stringResource(R.string.settings_tab_align_left),
                        TabBarAlign.CENTER.name to stringResource(R.string.settings_tab_align_center),
                        TabBarAlign.RIGHT.name to stringResource(R.string.settings_tab_align_right),
                    ),
                    selected = settings.sessionTabBarAlign.name,
                    onSelect = { value -> save { it.copy(sessionTabBarAlign = TabBarAlign.valueOf(value)) } },
                    locked = isLocked("session-tab-bar-align"),
                    enabled = settings.useSessionTabs,
                )
            }
            item {
                TextRow(
                    title = stringResource(R.string.settings_default_working_directory),
                    value = settings.defaultWorkingDirectory,
                    multiline = false,
                    validate = { null },
                    onChange = { value -> save { it.copy(defaultWorkingDirectory = value) } },
                    locked = isLocked("default-working-directory"),
                )
            }
            item {
                NumberRow(
                    title = stringResource(R.string.settings_transcript_rows),
                    value = settings.terminalTranscriptRows,
                    range = TRANSCRIPT_ROWS_RANGE,
                    isFloat = false,
                    onChange = { value -> save { it.copy(terminalTranscriptRows = value.toInt()) } },
                    locked = isLocked("terminal-transcript-rows"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_disable_session_change_toast),
                    checked = settings.disableTerminalSessionChangeToast,
                    onChange = { checked -> save { it.copy(disableTerminalSessionChangeToast = checked) } },
                    locked = isLocked("disable-terminal-session-change-toast"),
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.settings_bell_character),
                    options = listOf(
                        BellBehaviour.VIBRATE.name to stringResource(R.string.settings_bell_vibrate),
                        BellBehaviour.BEEP.name to stringResource(R.string.settings_bell_beep),
                        BellBehaviour.IGNORE.name to stringResource(R.string.settings_bell_ignore),
                    ),
                    selected = settings.bellBehaviour.name,
                    onSelect = { value -> save { it.copy(bellBehaviour = BellBehaviour.valueOf(value)) } },
                    locked = isLocked("bell-character"),
                )
            }

            item { SectionHeader(stringResource(R.string.termux_keyboard_header)) }
            item {
                TextRow(
                    title = stringResource(R.string.settings_extra_keys),
                    value = settings.extraKeys.toString(),
                    multiline = true,
                    validate = { validateExtraKeys(it, settings.extraKeysStyle, invalidExtraKeys) },
                    onChange = { value -> save { it.copy(extraKeys = Json.parseToJsonElement(value) as JsonArray) } },
                    locked = isLocked("extra-keys"),
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.settings_extra_keys_style),
                    options = listOf(
                        "default" to stringResource(R.string.settings_extra_keys_style_default),
                        "arrows-only" to stringResource(R.string.settings_extra_keys_style_arrows_only),
                        "arrows-all" to stringResource(R.string.settings_extra_keys_style_arrows_all),
                        "all" to stringResource(R.string.settings_extra_keys_style_all),
                        "none" to stringResource(R.string.settings_extra_keys_style_none),
                    ),
                    selected = settings.extraKeysStyle,
                    onSelect = { value -> save { it.copy(extraKeysStyle = value) } },
                    locked = isLocked("extra-keys-style"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_extra_keys_all_caps),
                    checked = settings.extraKeysTextAllCaps,
                    onChange = { checked -> save { it.copy(extraKeysTextAllCaps = checked) } },
                    locked = isLocked("extra-keys-text-all-caps"),
                )
            }
            item {
                NumberRow(
                    title = stringResource(R.string.settings_toolbar_height),
                    value = settings.terminalToolbarHeight,
                    range = 0.4f..3f,
                    isFloat = true,
                    onChange = { value -> save { it.copy(terminalToolbarHeight = value.toFloat()) } },
                    locked = isLocked("terminal-toolbar-height"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_hide_keyboard_on_startup),
                    checked = settings.hideSoftKeyboardOnStartup,
                    onChange = { checked -> save { it.copy(hideSoftKeyboardOnStartup = checked) } },
                    locked = isLocked("hide-soft-keyboard-on-startup"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.termux_ghostty_remember_soft_keyboard_state_title),
                    checked = settings.rememberSoftKeyboardState,
                    onChange = { checked -> save { it.copy(rememberSoftKeyboardState = checked) } },
                    summary = stringResource(R.string.termux_ghostty_remember_soft_keyboard_state_summary),
                    locked = isLocked("remember-soft-keyboard-state"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.termux_ghostty_unconditional_soft_keyboard_on_tap_title),
                    checked = settings.unconditionalSoftKeyboardOnTap,
                    onChange = { checked -> save { it.copy(unconditionalSoftKeyboardOnTap = checked) } },
                    summary = stringResource(R.string.termux_ghostty_unconditional_soft_keyboard_on_tap_summary),
                    locked = isLocked("unconditional-soft-keyboard-on-tap"),
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.settings_soft_keyboard_toggle),
                    options = listOf(
                        SoftKeyboardToggleBehaviour.SHOW_HIDE.name to stringResource(R.string.settings_toggle_show_hide),
                        SoftKeyboardToggleBehaviour.ENABLE_DISABLE.name to stringResource(R.string.settings_toggle_enable_disable),
                    ),
                    selected = settings.softKeyboardToggleBehaviour.name,
                    onSelect = { value -> save { it.copy(softKeyboardToggleBehaviour = SoftKeyboardToggleBehaviour.valueOf(value)) } },
                    locked = isLocked("soft-keyboard-toggle-behaviour"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_enforce_char_input),
                    checked = settings.enforceCharBasedInput,
                    onChange = { checked -> save { it.copy(enforceCharBasedInput = checked) } },
                    locked = isLocked("enforce-char-based-input"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_ctrl_space_workaround),
                    checked = settings.ctrlSpaceWorkaround,
                    onChange = { checked -> save { it.copy(ctrlSpaceWorkaround = checked) } },
                    locked = isLocked("ctrl-space-workaround"),
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.settings_back_key),
                    options = listOf(
                        BackKeyBehaviour.BACK.name to stringResource(R.string.settings_back_key_back),
                        BackKeyBehaviour.ESCAPE.name to stringResource(R.string.settings_back_key_escape),
                    ),
                    selected = settings.backKey.name,
                    onSelect = { value -> save { it.copy(backKey = BackKeyBehaviour.valueOf(value)) } },
                    locked = isLocked("back-key"),
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.settings_volume_keys),
                    options = listOf(
                        VolumeKeysBehaviour.VIRTUAL.name to stringResource(R.string.settings_volume_keys_virtual),
                        VolumeKeysBehaviour.VOLUME.name to stringResource(R.string.settings_volume_keys_volume),
                    ),
                    selected = settings.volumeKeys.name,
                    onSelect = { value -> save { it.copy(volumeKeys = VolumeKeysBehaviour.valueOf(value)) } },
                    locked = isLocked("volume-keys"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_disable_hardware_shortcuts),
                    checked = settings.disableHardwareKeyboardShortcuts,
                    onChange = { checked -> save { it.copy(disableHardwareKeyboardShortcuts = checked) } },
                    locked = isLocked("disable-hardware-keyboard-shortcuts"),
                )
            }
            item {
                TextRow(
                    title = stringResource(R.string.settings_shortcut_create_session),
                    value = settings.shortcutCreateSession,
                    multiline = false,
                    validate = { validateShortcut(it, invalidShortcut) },
                    onChange = { value -> save { it.copy(shortcutCreateSession = value.ifEmpty { null }) } },
                    locked = isLocked("shortcut-create-session"),
                )
            }
            item {
                TextRow(
                    title = stringResource(R.string.settings_shortcut_next_session),
                    value = settings.shortcutNextSession,
                    multiline = false,
                    validate = { validateShortcut(it, invalidShortcut) },
                    onChange = { value -> save { it.copy(shortcutNextSession = value.ifEmpty { null }) } },
                    locked = isLocked("shortcut-next-session"),
                )
            }
            item {
                TextRow(
                    title = stringResource(R.string.settings_shortcut_previous_session),
                    value = settings.shortcutPreviousSession,
                    multiline = false,
                    validate = { validateShortcut(it, invalidShortcut) },
                    onChange = { value -> save { it.copy(shortcutPreviousSession = value.ifEmpty { null }) } },
                    locked = isLocked("shortcut-previous-session"),
                )
            }
            item {
                TextRow(
                    title = stringResource(R.string.settings_shortcut_rename_session),
                    value = settings.shortcutRenameSession,
                    multiline = false,
                    validate = { validateShortcut(it, invalidShortcut) },
                    onChange = { value -> save { it.copy(shortcutRenameSession = value.ifEmpty { null }) } },
                    locked = isLocked("shortcut-rename-session"),
                )
            }
            item {
                PrefSwitchRow(
                    title = stringResource(R.string.termux_soft_keyboard_enabled_title),
                    checked = appPrefs?.isSoftKeyboardEnabled() ?: false,
                    summaryOn = stringResource(R.string.termux_soft_keyboard_enabled_on),
                    summaryOff = stringResource(R.string.termux_soft_keyboard_enabled_off),
                    onChange = { appPrefs?.setSoftKeyboardEnabled(it) },
                )
            }
            item {
                PrefSwitchRow(
                    title = stringResource(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_title),
                    checked = appPrefs?.isSoftKeyboardEnabledOnlyIfNoHardware() ?: false,
                    summaryOn = stringResource(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_on),
                    summaryOff = stringResource(R.string.termux_soft_keyboard_enabled_only_if_no_hardware_off),
                    onChange = { appPrefs?.setSoftKeyboardEnabledOnlyIfNoHardware(it) },
                )
            }

            item { SectionHeader(stringResource(R.string.settings_section_links)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.termux_ghostty_terminal_onclick_url_open_title),
                    checked = settings.terminalOnclickUrlOpen,
                    onChange = { checked -> save { it.copy(terminalOnclickUrlOpen = checked) } },
                    summary = stringResource(R.string.termux_ghostty_terminal_onclick_url_open_summary),
                    locked = isLocked("terminal-onclick-url-open"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.termux_ghostty_terminal_onclick_url_open_when_mouse_tracking_active_title),
                    checked = settings.terminalOnclickUrlOpenWhenMouseTrackingActive,
                    onChange = { checked -> save { it.copy(terminalOnclickUrlOpenWhenMouseTrackingActive = checked) } },
                    summary = stringResource(R.string.termux_ghostty_terminal_onclick_url_open_when_mouse_tracking_active_summary),
                    locked = isLocked("terminal-onclick-url-open-when-mouse-tracking-active"),
                    enabled = settings.terminalOnclickUrlOpen,
                )
            }

            item { SectionHeader(stringResource(R.string.settings_section_integration)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_allow_external_apps),
                    checked = settings.allowExternalApps,
                    onChange = {},
                    summary = stringResource(R.string.settings_only_in_config_file),
                    enabled = false,
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_run_am_socket_server),
                    checked = settings.runTermuxAmSocketServer,
                    onChange = { checked -> save { it.copy(runTermuxAmSocketServer = checked) } },
                    locked = isLocked("run-termux-am-socket-server"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_disable_file_share_receiver),
                    checked = settings.disableFileShareReceiver,
                    onChange = { checked -> save { it.copy(disableFileShareReceiver = checked) } },
                    locked = isLocked("disable-file-share-receiver"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.settings_disable_file_view_receiver),
                    checked = settings.disableFileViewReceiver,
                    onChange = { checked -> save { it.copy(disableFileViewReceiver = checked) } },
                    locked = isLocked("disable-file-view-receiver"),
                )
            }
            item {
                NumberRow(
                    title = stringResource(R.string.settings_delete_tmpdir_days),
                    value = settings.deleteTmpdirFilesOlderThanXDaysOnExit,
                    range = -1f..100000f,
                    isFloat = false,
                    onChange = { value -> save { it.copy(deleteTmpdirFilesOlderThanXDaysOnExit = value.toInt()) } },
                    locked = isLocked("delete-tmpdir-files-older-than-x-days-on-exit"),
                )
            }

            item { SectionHeader(stringResource(R.string.termux_debugging_preferences_title)) }
            item {
                LogLevelRow(
                    title = stringResource(R.string.termux_log_level_title),
                    level = appPrefs?.getLogLevel() ?: Logger.DEFAULT_LOG_LEVEL,
                    onSelect = { appPrefs?.setLogLevel(context, it) },
                )
            }
            item {
                PrefSwitchRow(
                    title = stringResource(R.string.termux_terminal_view_key_logging_enabled_title),
                    checked = appPrefs?.isTerminalViewKeyLoggingEnabled() ?: false,
                    summaryOn = stringResource(R.string.termux_terminal_view_key_logging_enabled_on),
                    summaryOff = stringResource(R.string.termux_terminal_view_key_logging_enabled_off),
                    onChange = { appPrefs?.setTerminalViewKeyLoggingEnabled(it) },
                )
            }
            item {
                PrefSwitchRow(
                    title = stringResource(R.string.termux_plugin_error_notifications_enabled_title),
                    checked = appPrefs?.arePluginErrorNotificationsEnabled(false) ?: false,
                    summaryOn = stringResource(R.string.termux_plugin_error_notifications_enabled_on),
                    summaryOff = stringResource(R.string.termux_plugin_error_notifications_enabled_off),
                    onChange = { appPrefs?.setPluginErrorNotificationsEnabled(it) },
                )
            }
            item {
                PrefSwitchRow(
                    title = stringResource(R.string.termux_crash_report_notifications_enabled_title),
                    checked = appPrefs?.areCrashReportNotificationsEnabled(false) ?: false,
                    summaryOn = stringResource(R.string.termux_crash_report_notifications_enabled_on),
                    summaryOff = stringResource(R.string.termux_crash_report_notifications_enabled_off),
                    onChange = { appPrefs?.setCrashReportNotificationsEnabled(it) },
                )
            }

            if (hasPlugins) item { SectionHeader(stringResource(R.string.settings_section_plugins)) }
            availability?.api?.let { api ->
                item {
                    LogLevelRow(
                        title = stringResource(R.string.settings_plugin_log_level, stringResource(R.string.termux_api_preferences_title)),
                        level = api.getLogLevel(true),
                        onSelect = { api.setLogLevel(context, it, true) },
                    )
                }
            }
            availability?.float?.let { float ->
                item {
                    LogLevelRow(
                        title = stringResource(R.string.settings_plugin_log_level, stringResource(R.string.termux_float_preferences_title)),
                        level = float.getLogLevel(true),
                        onSelect = { float.setLogLevel(context, it, true) },
                    )
                }
                item {
                    PrefSwitchRow(
                        title = stringResource(R.string.settings_plugin_key_logging, stringResource(R.string.termux_float_preferences_title)),
                        checked = float.isTerminalViewKeyLoggingEnabled(true),
                        summaryOn = stringResource(R.string.termux_terminal_view_key_logging_enabled_on),
                        summaryOff = stringResource(R.string.termux_terminal_view_key_logging_enabled_off),
                        onChange = { float.setTerminalViewKeyLoggingEnabled(it, true) },
                    )
                }
            }
            availability?.tasker?.let { tasker ->
                item {
                    LogLevelRow(
                        title = stringResource(R.string.settings_plugin_log_level, stringResource(R.string.termux_tasker_preferences_title)),
                        level = tasker.getLogLevel(true),
                        onSelect = { tasker.setLogLevel(context, it, true) },
                    )
                }
            }
            availability?.widget?.let { widget ->
                item {
                    LogLevelRow(
                        title = stringResource(R.string.settings_plugin_log_level, stringResource(R.string.termux_widget_preferences_title)),
                        level = widget.getLogLevel(true),
                        onSelect = { widget.setLogLevel(context, it, true) },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.termux_preferences_title)) }
            item { ActionRow(title = stringResource(R.string.about_preference_title), onClick = { showAbout(context) }) }
            if (availability?.donate == true) {
                item { ActionRow(title = stringResource(R.string.donate_preference_title), onClick = { ShareUtils.openUrl(context, TermuxConstants.TERMUX_DONATE_URL) }) }
            }
        }

        if (showErrors) {
            ConfigErrorsDialog(effective.errors) { showErrors = false }
        }
    }
}

@Composable
private fun PrefSwitchRow(
    title: String,
    checked: Boolean,
    summaryOn: String,
    summaryOff: String,
    onChange: (Boolean) -> Unit,
) {
    var value by remember { mutableStateOf(checked) }
    SwitchRow(
        title = title,
        checked = value,
        onChange = {
            value = it
            onChange(it)
        },
        summary = if (value) summaryOn else summaryOff,
    )
}

@Composable
private fun LogLevelRow(title: String, level: Int, onSelect: (Int) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(level) }
    val levels = Logger.getLogLevelsArray()
    val labels = Logger.getLogLevelLabelsArray(context, levels, true)
    ChoiceRow(
        title = title,
        options = levels.map { it.toString() }.zip(labels.map { it.toString() }),
        selected = selected.toString(),
        onSelect = {
            selected = it.toInt()
            onSelect(selected)
        },
    )
}

private fun validateExtraKeys(text: String, style: String, invalid: String): String? {
    try {
        if (Json.parseToJsonElement(text) !is JsonArray) return invalid
        ExtraKeysInfo(text, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES)
    } catch (e: Exception) {
        return invalid
    }
    return null
}

private fun validateShortcut(text: String, invalid: String): String? =
    if (text.isEmpty() || TermuxSettings.shortcutCodePoint(text) != null) null else invalid

private fun loadAvailability(context: Context) = Availability(
    api = TermuxAPIAppSharedPreferences.build(context, false),
    float = TermuxFloatAppSharedPreferences.build(context, false),
    tasker = TermuxTaskerAppSharedPreferences.build(context, false),
    widget = TermuxWidgetAppSharedPreferences.build(context, false),
    donate = isDonateVisible(context),
)

private fun isDonateVisible(context: Context): Boolean {
    val digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context) ?: return false
    // Play Store builds hide the donation link, per the Play Store donation policy.
    val apkRelease = TermuxUtils.getAPKRelease(digest)
    return apkRelease != null && apkRelease != TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST
}

private fun showAbout(context: Context) {
    Thread {
        val title = "About"

        val aboutString = StringBuilder()
        aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES))
        aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true))
        aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context))

        val userActionName = UserAction.ABOUT.getName()

        val reportInfo = ReportInfo(userActionName, TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title)
        reportInfo.setReportString(aboutString.toString())
        reportInfo.setReportSaveFileLabelAndPath(userActionName,
            "${Environment.getExternalStorageDirectory()}/" +
                FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true))

        ReportActivity.startReportActivity(context, reportInfo)
    }.start()
}
