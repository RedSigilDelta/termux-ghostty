package com.mrndtvndv.term.ui.settings.categories

import androidx.compose.foundation.lazy.LazyListScope
import com.mrndtvndv.term.ui.settings.SettingRowContent
import com.mrndtvndv.term.ui.settings.SettingsGroup
import com.mrndtvndv.term.ui.settings.SettingsState
import com.mrndtvndv.term.ui.settings.SwitchRow

fun LazyListScope.workspacesSettings(state: SettingsState) {
    item {
        val rows = buildList<SettingRowContent> {
            add { shapes ->
                SwitchRow(
                    title = "Agents sidebar",
                    checked = state.settings.agentsSidebar,
                    onChange = { checked -> state.onUpdate { it.copy(agentsSidebar = checked) } },
                    shapes = shapes,
                    summary = "Open Agents in a push-reveal sidebar instead of a bottom sheet",
                )
            }
            if (!state.settings.agentsSidebar) {
                add { shapes ->
                    SwitchRow(
                        title = "Overlay Agents button",
                        checked = state.settings.overlayAgentsFab,
                        onChange = { checked -> state.onUpdate { it.copy(overlayAgentsFab = checked) } },
                        shapes = shapes,
                        summary = "Float the action button over the agents list instead of reserving space below it",
                    )
                }
            }
        }
        SettingsGroup(rows = rows)
    }
}
