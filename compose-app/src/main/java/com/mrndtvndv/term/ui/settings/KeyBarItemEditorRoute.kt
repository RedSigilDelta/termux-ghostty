package com.mrndtvndv.term.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun KeyBarItemEditorRoute(
    settingsViewModel: SettingsViewModel,
    itemId: String?,
    onBack: () -> Unit,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val draftViewModel: KeyBarItemDraftViewModel = viewModel {
        KeyBarItemDraftViewModel(
            itemId = itemId,
            initial = settings.keyBarLayout.items.firstOrNull { it.id == itemId }?.toDraft() ?: KeyBarItemDraft(),
        )
    }
    val draft by draftViewModel.draft.collectAsStateWithLifecycle()

    KeyBarItemEditorScreen(
        draft = draft,
        isNew = itemId == null,
        onEdit = draftViewModel::edit,
        onSave = {
            val current = draftViewModel.draft.value
            if (current.isValid()) {
                val item = current.toItem(draftViewModel.id)
                settingsViewModel.update { it.copy(keyBarLayout = it.keyBarLayout.withItemSaved(item)) }
                onBack()
            }
        },
        onBack = onBack,
    )
}
