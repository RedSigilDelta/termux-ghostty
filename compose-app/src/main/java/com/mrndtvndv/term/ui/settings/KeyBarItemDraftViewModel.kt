package com.mrndtvndv.term.ui.settings

import androidx.lifecycle.ViewModel
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

    fun edit(transform: (KeyBarItemDraft) -> KeyBarItemDraft) {
        _draft.update(transform)
    }
}
