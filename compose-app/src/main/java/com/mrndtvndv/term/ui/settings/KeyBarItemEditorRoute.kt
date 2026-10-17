package com.mrndtvndv.term.ui.settings

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarLayout
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.MacroStep
import com.mrndtvndv.term.data.prefs.supportsSwipes
import com.mrndtvndv.term.navigation.KeyBarItemStart
import kotlinx.coroutines.delay

@Composable
fun KeyBarItemEditorRoute(
    settingsViewModel: SettingsViewModel,
    itemId: String?,
    start: KeyBarItemStart? = null,
    onBack: () -> Unit,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val draftViewModel: KeyBarItemDraftViewModel = viewModel {
        KeyBarItemDraftViewModel(itemId, settings.keyBarLayout.initialDraft(itemId, start))
    }
    val draft by draftViewModel.draft.collectAsStateWithLifecycle()
    val touched by draftViewModel.touched.collectAsStateWithLifecycle()
    val initialEditorLayer = remember(draftViewModel) { draftViewModel.takeInitialEditorLayer(start) }

    KeyBarDraftLiveSaver(settingsViewModel, draftViewModel, draft, touched, settings.keyBarLayout)
    KeyBarItemEditorScreen(
        state = buildEditorState(settings.keyBarLayout, draft, draftViewModel.id, touched, initialEditorLayer),
        callbacks = KeyBarItemEditorCallbacks(
            onEdit = draftViewModel::edit,
            onBack = onBack,
            onDiscard = draftViewModel::discard,
            onMove = { direction -> moveKeyBarItem(settingsViewModel, draftViewModel.id, direction) },
            onDuplicate = {
                settingsViewModel.update {
                    it.copy(keyBarLayout = it.keyBarLayout.withItemDuplicated(draftViewModel.id))
                }
            },
            onRemove = {
                draftViewModel.discard()
                settingsViewModel.update {
                    it.copy(keyBarLayout = it.keyBarLayout.withoutItem(draftViewModel.id))
                }
                onBack()
            },
        ),
    )
}

@Composable
private fun KeyBarDraftLiveSaver(
    settingsViewModel: SettingsViewModel,
    draftViewModel: KeyBarItemDraftViewModel,
    draft: KeyBarItemDraft,
    touched: Boolean,
    layout: KeyBarLayout,
) {
    val latestDraft by rememberUpdatedState(draft)
    val latestTouched by rememberUpdatedState(touched)

    LaunchedEffect(draft, touched, layout) {
        if (!shouldSaveDraft(layout, draftViewModel.id, draft, touched, draftViewModel.skipSaving)) {
            return@LaunchedEffect
        }
        delay(300)
        saveDraftIfNeeded(settingsViewModel, draftViewModel, draft, touched)
    }

    DisposableEffect(settingsViewModel, draftViewModel) {
        onDispose {
            saveDraftIfNeeded(settingsViewModel, draftViewModel, latestDraft, latestTouched)
        }
    }
}

private fun shouldSaveDraft(
    layout: KeyBarLayout,
    id: String,
    draft: KeyBarItemDraft,
    touched: Boolean,
    skipFinalSave: Boolean,
): Boolean {
    if (skipFinalSave || !touched || !draft.isValid()) return false

    val item = draft.toItem(id)
    val savedItem = layout.items.firstOrNull { it.id == id }
    if (savedItem == item) return false
    return savedItem != null || layout.items.size < KeyBarLimits.MaxItems
}

private fun saveDraftIfNeeded(
    settingsViewModel: SettingsViewModel,
    draftViewModel: KeyBarItemDraftViewModel,
    draft: KeyBarItemDraft,
    touched: Boolean,
) {
    val id = draftViewModel.id
    if (
        !shouldSaveDraft(
            settingsViewModel.settings.value.keyBarLayout,
            id,
            draft,
            touched,
            draftViewModel.skipSaving,
        )
    ) {
        return
    }

    val item = draft.toItem(id)
    settingsViewModel.update { settings ->
        val layout = settings.keyBarLayout
        if (shouldSaveDraft(layout, id, draft, touched, draftViewModel.skipSaving)) {
            settings.copy(keyBarLayout = layout.withItemSaved(item))
        } else {
            settings
        }
    }
}

private fun moveKeyBarItem(settingsViewModel: SettingsViewModel, id: String, direction: Int) {
    val layout = settingsViewModel.settings.value.keyBarLayout
    val index = layout.items.indexOfFirst { it.id == id }
    if (index < 0) return

    val target = index + direction
    if (target !in layout.items.indices) return
    settingsViewModel.update { it.copy(keyBarLayout = it.keyBarLayout.withItemMoved(index, target)) }
}

private fun KeyBarLayout.initialDraft(itemId: String?, start: KeyBarItemStart?): KeyBarItemDraft =
    items.firstOrNull { it.id == itemId }?.toDraft()
        ?: start?.takeIf { itemId == null }?.toDraft()
        ?: KeyBarItemDraft()

private fun buildEditorState(
    layout: KeyBarLayout,
    draft: KeyBarItemDraft,
    id: String,
    touched: Boolean,
    initialEditorLayer: KeyBarItemEditorStartLayer?,
): KeyBarItemEditorState {
    val savedItem = layout.items.firstOrNull { it.id == id }
    val draftItem = draft.toItem(id)
    val isNew = savedItem == null
    val isDifferent = savedItem != draftItem
    val saveStatus = createSaveStatus(layout, draft, savedItem, isDifferent, touched)

    return KeyBarItemEditorState(
        draft = draft,
        isNew = isNew,
        itemExists = savedItem != null,
        itemIndex = layout.items.indexOfFirst { it.id == id },
        itemCount = layout.items.size,
        saveStatus = saveStatus.label,
        saveStatusIsError = saveStatus.isError,
        inlineError = "The key bar is full".takeIf { isNew && layout.items.size >= KeyBarLimits.MaxItems },
        discardMessage = discardMessage(draft, savedItem, draftItem, touched),
        initialPickerTab = initialEditorLayer?.pickerTab,
        initialComposerSlot = initialEditorLayer?.composerSlot,
    )
}

private data class KeyBarSaveStatus(val label: String, val isError: Boolean)

private fun createSaveStatus(
    layout: KeyBarLayout,
    draft: KeyBarItemDraft,
    savedItem: KeyBarItem?,
    isDifferent: Boolean,
    touched: Boolean,
): KeyBarSaveStatus {
    if (!isDifferent) return KeyBarSaveStatus("Saved", false)

    val error = when {
        !draft.isValid() -> draft.firstError() ?: "This change is invalid"
        savedItem == null && layout.items.size >= KeyBarLimits.MaxItems -> "The key bar is full"
        else -> null
    }
    if (error != null) return KeyBarSaveStatus("Not saved: $error", true)
    return KeyBarSaveStatus(if (touched) "Saving…" else "Not saved", false)
}

private fun discardMessage(
    draft: KeyBarItemDraft,
    savedItem: KeyBarItem?,
    draftItem: KeyBarItem,
    touched: Boolean,
): String? = when {
    draft.isValid() -> null
    savedItem != null && savedItem != draftItem ->
        "${draft.firstError() ?: "This change is invalid"}. The key keeps its last saved version."
    savedItem == null && touched -> "The new key won't be added."
    else -> null
}

private fun KeyBarItemDraft.firstError(): String? =
    labelError()
        ?: tap.validationError()
        ?: if (tap.supportsSwipes()) {
            KeyBarDirection.entries.firstNotNullOfOrNull { direction -> swipes[direction]?.validationError() }
        } else {
            null
        }

private fun KeyBarItemStart.toDraft(): KeyBarItemDraft = when (this) {
    KeyBarItemStart.Combo -> KeyBarItemDraft(
        label = "Ctrl+C",
        tap = KeyBarAction.Macro(listOf(MacroStep.Key(KeyEvent.KEYCODE_C, ctrl = true))),
    )
    KeyBarItemStart.Snippet -> KeyBarItemDraft(label = "", tap = KeyBarAction.Text(""))
}
