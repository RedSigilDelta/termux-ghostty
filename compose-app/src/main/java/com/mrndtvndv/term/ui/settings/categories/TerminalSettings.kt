package com.mrndtvndv.term.ui.settings.categories

import androidx.compose.foundation.lazy.LazyListScope
import com.mrndtvndv.term.ui.settings.ActionRow
import com.mrndtvndv.term.ui.settings.ChoiceRow
import com.mrndtvndv.term.ui.settings.NumberRow
import com.mrndtvndv.term.ui.settings.SettingRowContent
import com.mrndtvndv.term.ui.settings.SettingsGroup
import com.mrndtvndv.term.ui.settings.SettingsState
import com.mrndtvndv.term.ui.settings.SwitchRow
import com.mrndtvndv.term.ui.workspace.CursorTrailEffect
import com.mrndtvndv.term.ui.workspace.MaxKeyboardResizeDebounceMillis

@Suppress("LongMethod")
fun LazyListScope.terminalSettings(state: SettingsState) {
    val resetFontRow: SettingRowContent? = if (state.settings.customFontName != null) {
        { shapes ->
            ActionRow(
                title = "Reset font",
                shapes = shapes,
                onClick = state.onClearFont,
            )
        }
    } else {
        null
    }

    item {
        SettingsGroup(
            rows = listOfNotNull(
                { shapes ->
                    NumberRow(
                        title = "Font size",
                        value = state.settings.fontSize,
                        range = state.fontSizeRange.first.toFloat()..state.fontSizeRange.last.toFloat(),
                        isFloat = false,
                        onChange = { value -> state.onUpdate { it.copy(fontSize = value.toInt()) } },
                        shapes = shapes,
                        summary = "${state.settings.fontSize} px",
                    )
                },
                { shapes ->
                    ActionRow(
                        title = "Choose font",
                        summary = state.settings.customFontName ?: "Default (Monospace)",
                        shapes = shapes,
                        onClick = state.onSelectFont,
                    )
                },
                resetFontRow,
                { shapes ->
                    SwitchRow(
                        title = "Use custom font for the whole app",
                        checked = state.settings.useCustomFontForWholeUi,
                        onChange = { checked -> state.onUpdate { it.copy(useCustomFontForWholeUi = checked) } },
                        shapes = shapes,
                        summary = "Apply the custom terminal font to the entire application UI",
                        enabled = state.settings.customFontName != null,
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = "Cursor trail",
                        options = CursorTrailEffect.entries.map { it to it.label },
                        selected = state.settings.cursorTrail,
                        onSelect = { effect -> state.onUpdate { it.copy(cursorTrail = effect) } },
                        shapes = shapes,
                    )
                },
                { shapes ->
                    NumberRow(
                        title = "Keyboard resize debounce",
                        value = state.settings.keyboardResizeDebounceMs,
                        range = 0f..MaxKeyboardResizeDebounceMillis.toFloat(),
                        isFloat = false,
                        onChange = { value -> state.onUpdate { it.copy(keyboardResizeDebounceMs = value.toInt()) } },
                        shapes = shapes,
                        summary = if (state.settings.keyboardResizeDebounceMs == 0) {
                            "0 ms (resize immediately)"
                        } else {
                            "${state.settings.keyboardResizeDebounceMs} ms"
                        },
                    )
                },
            ),
        )
    }
}
