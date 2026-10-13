package com.termux.app.settings.ui.categories

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.settings.BellBehaviour
import com.termux.app.settings.CursorStyle
import com.termux.app.settings.CursorTrail
import com.termux.app.settings.ui.ActionRow
import com.termux.app.settings.ui.ChoiceRow
import com.termux.app.settings.ui.NumberRow
import com.termux.app.settings.ui.SettingRowContent
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.app.settings.ui.SwitchRow
import com.termux.app.settings.ui.TextRow
import com.termux.terminal.TerminalConstants

private val TRANSCRIPT_ROWS_RANGE = TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MIN.toFloat()..TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MAX.toFloat()
private val BLINK_RATE_RANGE = TerminalConstants.TERMINAL_CURSOR_BLINK_RATE_MIN.toFloat()..TerminalConstants.TERMINAL_CURSOR_BLINK_RATE_MAX.toFloat()

fun LazyListScope.terminalSettings(state: SettingsState) {
    val resetFontRow: SettingRowContent? = if (state.fontSet) {
        { shapes ->
            ActionRow(
                title = stringResource(R.string.settings_reset_font),
                shapes = shapes,
                onClick = state.resetFont,
            )
        }
    } else {
        null
    }
    item {
        SettingsGroup(
            rows = listOfNotNull(
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.settings_cursor_style),
                        options = listOf(
                            CursorStyle.BLOCK.name to stringResource(R.string.settings_cursor_block),
                            CursorStyle.UNDERLINE.name to stringResource(R.string.settings_cursor_underline),
                            CursorStyle.BAR.name to stringResource(R.string.settings_cursor_bar),
                        ),
                        selected = state.settings.terminalCursorStyle.name,
                        onSelect = { value -> state.save { it.copy(terminalCursorStyle = CursorStyle.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("terminal-cursor-style"),
                    )
                },
                { shapes ->
                    NumberRow(
                        title = stringResource(R.string.settings_cursor_blink_rate),
                        value = state.settings.terminalCursorBlinkRate,
                        range = BLINK_RATE_RANGE,
                        isFloat = false,
                        onChange = { value -> state.save { it.copy(terminalCursorBlinkRate = value.toInt()) } },
                        shapes = shapes,
                        locked = state.isLocked("terminal-cursor-blink-rate"),
                        isValid = { it.toInt() == 0 || it.toFloat() in BLINK_RATE_RANGE },
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.settings_cursor_trail),
                        options = listOf(
                            CursorTrail.NONE.name to stringResource(R.string.settings_cursor_trail_none),
                            CursorTrail.WARP.name to stringResource(R.string.settings_cursor_trail_warp),
                            CursorTrail.SWEEP.name to stringResource(R.string.settings_cursor_trail_sweep),
                            CursorTrail.TAIL.name to stringResource(R.string.settings_cursor_trail_tail),
                        ),
                        selected = state.settings.cursorTrail.name,
                        onSelect = { value -> state.save { it.copy(cursorTrail = CursorTrail.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("cursor-trail"),
                    )
                },
                { shapes ->
                    ActionRow(
                        title = stringResource(R.string.settings_choose_font),
                        shapes = shapes,
                        summary = stringResource(if (state.fontSet) R.string.settings_file_set else R.string.settings_file_not_set),
                        onClick = state.pickFont,
                    )
                },
                resetFontRow,
                { shapes ->
                    SwitchRow(
                        title = stringResource(R.string.settings_custom_font_for_ui),
                        checked = state.settings.customFontForUi,
                        onChange = { checked -> state.save { it.copy(customFontForUi = checked) } },
                        shapes = shapes,
                        locked = state.isLocked("custom-font-for-ui"),
                    )
                },
                { shapes ->
                    NumberRow(
                        title = stringResource(R.string.settings_transcript_rows),
                        value = state.settings.terminalTranscriptRows,
                        range = TRANSCRIPT_ROWS_RANGE,
                        isFloat = false,
                        onChange = { value -> state.save { it.copy(terminalTranscriptRows = value.toInt()) } },
                        shapes = shapes,
                        locked = state.isLocked("terminal-transcript-rows"),
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = stringResource(R.string.settings_bell_character),
                        options = listOf(
                            BellBehaviour.VIBRATE.name to stringResource(R.string.settings_bell_vibrate),
                            BellBehaviour.BEEP.name to stringResource(R.string.settings_bell_beep),
                            BellBehaviour.IGNORE.name to stringResource(R.string.settings_bell_ignore),
                        ),
                        selected = state.settings.bellBehaviour.name,
                        onSelect = { value -> state.save { it.copy(bellBehaviour = BellBehaviour.valueOf(value)) } },
                        shapes = shapes,
                        locked = state.isLocked("bell-character"),
                    )
                },
                { shapes ->
                    TextRow(
                        title = stringResource(R.string.settings_default_working_directory),
                        value = state.settings.defaultWorkingDirectory,
                        multiline = false,
                        validate = { null },
                        onChange = { value -> state.save { it.copy(defaultWorkingDirectory = value) } },
                        shapes = shapes,
                        locked = state.isLocked("default-working-directory"),
                    )
                },
            ),
        )
    }
}
