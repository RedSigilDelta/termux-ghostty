package com.mrndtvndv.term.ui.settings.categories

import androidx.compose.foundation.lazy.LazyListScope
import com.mrndtvndv.term.ui.settings.SettingsGroup
import com.mrndtvndv.term.ui.settings.SettingsState
import com.mrndtvndv.term.ui.settings.SwitchRow

fun LazyListScope.workspacesSettings(state: SettingsState) {
    item {
        SettingsGroup(
            rows = listOf(
                { shapes ->
                    SwitchRow(
                        title = "Overlay Agents button",
                        checked = state.settings.overlayAgentsFab,
                        onChange = { checked -> state.onUpdate { it.copy(overlayAgentsFab = checked) } },
                        shapes = shapes,
                        summary = "Float the action button over the agents list instead of reserving space below it",
                    )
                },
            ),
        )
    }
}
