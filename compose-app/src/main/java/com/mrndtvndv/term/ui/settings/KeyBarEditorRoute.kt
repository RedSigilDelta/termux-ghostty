package com.mrndtvndv.term.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mrndtvndv.term.data.prefs.defaultKeyBarLayout

@Composable
fun KeyBarEditorRoute(
    settingsViewModel: SettingsViewModel,
    onAddItem: () -> Unit,
    onEditItem: (String) -> Unit,
    onBack: () -> Unit,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    KeyBarEditorScreen(
        items = settings.keyBarLayout.items,
        onAdd = onAddItem,
        onEdit = onEditItem,
        onMove = { from, to ->
            settingsViewModel.update { it.copy(keyBarLayout = it.keyBarLayout.withItemMoved(from, to)) }
        },
        onDelete = { id ->
            settingsViewModel.update { it.copy(keyBarLayout = it.keyBarLayout.withoutItem(id)) }
        },
        onReset = {
            settingsViewModel.update { it.copy(keyBarLayout = defaultKeyBarLayout()) }
        },
        onBack = onBack,
    )
}
