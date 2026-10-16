package com.mrndtvndv.term.ui.keyboard

import android.util.Log
import com.mrndtvndv.term.data.prefs.MacroStep
import com.mrndtvndv.term.data.prefs.macroInputItems
import com.termux.terminal.TerminalSession

private const val MacroLogTag = "KeyBarMacro"

/**
 * Sends [steps] as one ordered write. Text and printable keys are fixed bytes. Special keys are encoded on the
 * terminal worker, after output already queued for parsing, from its live cursor, keypad and Kitty modes. Those
 * modes are not read here, because the session's getters are a cached mirror that can lag the worker. Macro
 * modifiers are step-local, so an armed Ctrl or Alt is neither applied nor consumed.
 */
fun sendKeyBarMacro(session: TerminalSession, steps: List<MacroStep>) {
    val items = macroInputItems(steps)
    if (items == null) {
        Log.w(MacroLogTag, "Macro has a step with no encoding; nothing was sent")
        return
    }
    session.setGhosttyTopRow(0)
    session.setCursorBlinkState(true)
    if (!session.sendInputSequence(items)) {
        Log.w(MacroLogTag, "No terminal worker is running; macro was not sent")
    }
}
