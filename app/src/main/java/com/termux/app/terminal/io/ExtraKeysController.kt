package com.termux.app.terminal.io

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ModifierState {
    INACTIVE,
    ACTIVE,
    LOCKED
}

class ExtraKeysController {
    var ctrlState by mutableStateOf(ModifierState.INACTIVE)
        private set
    var altState by mutableStateOf(ModifierState.INACTIVE)
        private set
    var shiftState by mutableStateOf(ModifierState.INACTIVE)
        private set
    var fnState by mutableStateOf(ModifierState.INACTIVE)
        private set

    @Volatile private var ctrlConsumed = false
    @Volatile private var altConsumed = false
    @Volatile private var shiftConsumed = false
    @Volatile private var fnConsumed = false

    fun toggleControl() {
        ctrlConsumed = false
        ctrlState = nextToggleState(ctrlState)
    }

    fun lockControl() {
        ctrlConsumed = false
        ctrlState = ModifierState.LOCKED
    }

    fun toggleAlt() {
        altConsumed = false
        altState = nextToggleState(altState)
    }

    fun lockAlt() {
        altConsumed = false
        altState = ModifierState.LOCKED
    }

    fun toggleShift() {
        shiftConsumed = false
        shiftState = nextToggleState(shiftState)
    }

    fun lockShift() {
        shiftConsumed = false
        shiftState = ModifierState.LOCKED
    }

    fun toggleFn() {
        fnConsumed = false
        fnState = nextToggleState(fnState)
    }

    fun lockFn() {
        fnConsumed = false
        fnState = ModifierState.LOCKED
    }

    /**
     * Reads the dispatch state without mutating Compose state in the input hot path.
     * The visual state is cleared by [clearConsumedModifiers] after dispatch.
     */
    fun readControl(): Boolean = readModifier(ctrlState) { ctrlConsumed = true }

    fun readAlt(): Boolean = readModifier(altState) { altConsumed = true }

    fun readShift(): Boolean = readModifier(shiftState) { shiftConsumed = true }

    fun readFn(): Boolean = readModifier(fnState) { fnConsumed = true }

    /** Applies one-shot modifier changes after terminal input dispatch has returned. */
    fun clearConsumedModifiers() {
        if (ctrlConsumed) {
            ctrlConsumed = false
            if (ctrlState == ModifierState.ACTIVE) ctrlState = ModifierState.INACTIVE
        }
        if (altConsumed) {
            altConsumed = false
            if (altState == ModifierState.ACTIVE) altState = ModifierState.INACTIVE
        }
        if (shiftConsumed) {
            shiftConsumed = false
            if (shiftState == ModifierState.ACTIVE) shiftState = ModifierState.INACTIVE
        }
        if (fnConsumed) {
            fnConsumed = false
            if (fnState == ModifierState.ACTIVE) fnState = ModifierState.INACTIVE
        }
    }

    private fun readModifier(state: ModifierState, markConsumed: () -> Unit): Boolean {
        if (state == ModifierState.ACTIVE) markConsumed()
        return state != ModifierState.INACTIVE
    }

    private fun nextToggleState(state: ModifierState): ModifierState = when (state) {
        ModifierState.INACTIVE -> ModifierState.ACTIVE
        ModifierState.ACTIVE, ModifierState.LOCKED -> ModifierState.INACTIVE
    }
}
