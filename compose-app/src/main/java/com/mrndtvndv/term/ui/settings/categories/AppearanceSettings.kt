package com.mrndtvndv.term.ui.settings.categories

import androidx.compose.foundation.lazy.LazyListScope
import com.mrndtvndv.term.ui.settings.ChoiceRow
import com.mrndtvndv.term.ui.settings.SettingsGroup
import com.mrndtvndv.term.ui.settings.SettingsState
import com.mrndtvndv.term.ui.settings.SwitchRow
import com.mrndtvndv.term.ui.workspace.VisualEffectFrameRate

fun LazyListScope.appearanceSettings(state: SettingsState) {
    item {
        SettingsGroup(
            rows = listOf(
                { shapes ->
                    ChoiceRow(
                        title = "App theme",
                        options = listOf("System" to "System", "Light" to "Light", "Dark" to "Dark"),
                        selected = state.settings.theme,
                        onSelect = { value -> state.onUpdate { it.copy(theme = value) } },
                        shapes = shapes,
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = "AMOLED black",
                        checked = state.settings.amoledBlack,
                        onChange = { checked -> state.onUpdate { it.copy(amoledBlack = checked) } },
                        shapes = shapes,
                        summary = "Pure black backgrounds in dark themes",
                    )
                },
                { shapes ->
                    SwitchRow(
                        title = "Animations",
                        checked = state.settings.animationsEnabled,
                        onChange = { checked -> state.onUpdate { it.copy(animationsEnabled = checked) } },
                        shapes = shapes,
                        summary = "Animate screen changes, Git and SFTP navigation, and tab switches",
                    )
                },
                { shapes ->
                    ChoiceRow(
                        title = "Visual effect frame rate",
                        options = VisualEffectFrameRate.entries.map { it to it.label },
                        selected = state.settings.visualEffectFrameRate,
                        onSelect = { rate -> state.onUpdate { it.copy(visualEffectFrameRate = rate) } },
                        shapes = shapes,
                    )
                },
            ),
        )
    }
}
