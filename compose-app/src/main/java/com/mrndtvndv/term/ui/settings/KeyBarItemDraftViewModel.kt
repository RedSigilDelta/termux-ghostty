package com.mrndtvndv.term.ui.settings

import androidx.lifecycle.ViewModel
import com.mrndtvndv.term.navigation.KeyBarItemStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** Holds unsaved edits for one item in its nav entry, so they survive configuration changes. */
class KeyBarItemDraftViewModel(itemId: String?, initial: KeyBarItemDraft) : ViewModel() {
    val id: String = itemId ?: UUID.randomUUID().toString()

    private val _draft = MutableStateFlow(initial)
    val draft: StateFlow<KeyBarItemDraft> = _draft.asStateFlow()

    private val _touched = MutableStateFlow(false)
    val touched: StateFlow<Boolean> = _touched.asStateFlow()

    var skipSaving = false
        private set

    private var startPickerShown = false

    fun discard() {
        skipSaving = true
    }

    internal fun takeInitialEditorLayer(start: KeyBarItemStart?): KeyBarItemEditorStartLayer? {
        if (start == null || startPickerShown) return null
        startPickerShown = true
        return when (start) {
            KeyBarItemStart.Combo -> KeyBarItemEditorStartLayer(pickerTab = KeyBarActionPickerTab.Key)
            KeyBarItemStart.Snippet -> KeyBarItemEditorStartLayer(composerSlot = KeyBarEditorSlot.Tap)
        }
    }

    fun edit(transform: (KeyBarItemDraft) -> KeyBarItemDraft) {
        _draft.update(transform)
        _touched.value = true
    }
}

internal data class KeyBarItemEditorStartLayer(
    val pickerTab: KeyBarActionPickerTab? = null,
    val composerSlot: KeyBarEditorSlot? = null,
)
