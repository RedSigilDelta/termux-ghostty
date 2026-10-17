package com.mrndtvndv.term.data.prefs

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalInputItem

/** A printable key, and the character it produces plain and with Shift on a US layout. */
private class PrintableKey(
    val keyCode: Int,
    val label: String,
    val spokenName: String,
    val plain: Char,
    val shifted: Char,
) {
    fun produced(shift: Boolean): Char = if (shift) shifted else plain
}

/** The key encoder has no keycodes for these, so macros encode them as characters. */
private val PrintableKeys: List<PrintableKey> = buildList {
    for (letter in 'a'..'z') {
        val upper = letter.uppercase()
        add(PrintableKey(KeyEvent.KEYCODE_A + (letter - 'a'), upper, upper, letter, letter.uppercaseChar()))
    }
    val digitShifts = ")!@#\$%^&*("
    for (digit in 0..9) {
        add(PrintableKey(KeyEvent.KEYCODE_0 + digit, "$digit", "$digit", '0' + digit, digitShifts[digit]))
    }
    add(PrintableKey(KeyEvent.KEYCODE_MINUS, "-", "minus", '-', '_'))
    add(PrintableKey(KeyEvent.KEYCODE_EQUALS, "=", "equals", '=', '+'))
    add(PrintableKey(KeyEvent.KEYCODE_LEFT_BRACKET, "[", "left bracket", '[', '{'))
    add(PrintableKey(KeyEvent.KEYCODE_RIGHT_BRACKET, "]", "right bracket", ']', '}'))
    add(PrintableKey(KeyEvent.KEYCODE_BACKSLASH, "\\", "backslash", '\\', '|'))
    add(PrintableKey(KeyEvent.KEYCODE_SEMICOLON, ";", "semicolon", ';', ':'))
    add(PrintableKey(KeyEvent.KEYCODE_APOSTROPHE, "'", "apostrophe", '\'', '"'))
    add(PrintableKey(KeyEvent.KEYCODE_COMMA, ",", "comma", ',', '<'))
    add(PrintableKey(KeyEvent.KEYCODE_PERIOD, ".", "period", '.', '>'))
    add(PrintableKey(KeyEvent.KEYCODE_SLASH, "/", "slash", '/', '?'))
    add(PrintableKey(KeyEvent.KEYCODE_GRAVE, "`", "backtick", '`', '~'))
    add(PrintableKey(KeyEvent.KEYCODE_SPACE, "Space", "space", ' ', ' '))
}

private val PrintableByKeyCode: Map<Int, PrintableKey> = PrintableKeys.associateBy { it.keyCode }

/** Keys a macro step may send: [KeyBarKeyCatalog] plus letters, digits, space and punctuation. */
val MacroKeyCatalog: List<KeyBarCatalogKey> = KeyBarKeyCatalog +
    PrintableKeys.map { KeyBarCatalogKey(it.keyCode, it.label, it.spokenName) }

/** Whether [step] has an encoding. Unsupported combinations fail validation here, not at send time. */
fun MacroStep.isSupported(): Boolean = when (this) {
    is MacroStep.Text -> text.isNotEmpty()
    is MacroStep.Key -> isSupportedKey()
}

/**
 * A macro has 1 to [KeyBarLimits.MaxMacroSteps] steps, each with an encoding, and at most
 * [KeyBarLimits.MaxTextLength] characters of text across its Text steps.
 */
fun isValidMacro(steps: List<MacroStep>): Boolean =
    steps.size in 1..KeyBarLimits.MaxMacroSteps &&
        steps.all { it.isSupported() } &&
        steps.sumOf { (it as? MacroStep.Text)?.text?.length ?: 0 } <= KeyBarLimits.MaxTextLength

/**
 * The input for [steps] in order, or null when any step has no encoding. Text and printable keys are fixed
 * characters, so they are encoded here. Special keys stay as key items, which the terminal worker encodes from its
 * live modes. The whole macro is then one ordered write.
 */
fun macroInputItems(steps: List<MacroStep>): List<TerminalInputItem>? {
    val items = ArrayList<TerminalInputItem>(steps.size)
    for (step in steps) {
        items += step.toInputItem() ?: return null
    }
    return items
}

/**
 * The ASCII control character for [produced] under Ctrl, or null when Ctrl has no mapping for it. The mapping is
 * the emulator's [KeyHandler.getControlCode], which the Compose input translator also uses.
 */
internal fun asciiControlCode(produced: Char): Char? = KeyHandler.getControlCode(produced.code)?.toChar()

internal fun MacroStep.Key.producedChar(): Char? = PrintableByKeyCode[keyCode]?.produced(shift)

private fun MacroStep.toInputItem(): TerminalInputItem? = when (this) {
    is MacroStep.Text -> if (text.isEmpty()) null else TerminalInputItem.text(text)
    is MacroStep.Key -> toKeyInputItem()
}

private fun MacroStep.Key.toKeyInputItem(): TerminalInputItem? {
    val printable = PrintableByKeyCode[keyCode] ?: return specialKeyItemOrNull()
    val produced = printable.produced(shift)
    val payload = (if (ctrl) asciiControlCode(produced) else produced) ?: return null
    return TerminalInputItem.text(if (alt) "\u001B$payload" else payload.toString())
}

private fun MacroStep.Key.specialKeyItemOrNull(): TerminalInputItem? =
    if (isSupportedKey()) TerminalInputItem.key(keyCode, modifierMask(this)) else null

private fun MacroStep.Key.isSupportedKey(): Boolean {
    val printable = PrintableByKeyCode[keyCode]
    return when {
        printable != null -> !ctrl || asciiControlCode(printable.produced(shift)) != null
        KeyBarKeyCatalog.none { it.keyCode == keyCode } -> false
        !ctrl && !alt && !shift -> true
        else -> modifiedOutputChanges()
    }
}

/**
 * KeyHandler ignores some modifiers (Shift on Esc, for one). A modified special key is therefore accepted only
 * when its output differs from the unmodified output.
 */
private fun MacroStep.Key.modifiedOutputChanges(): Boolean {
    val modified = KeyHandler.getCode(keyCode, modifierMask(this), false, false)
    return modified != null && modified != KeyHandler.getCode(keyCode, 0, false, false)
}

private fun modifierMask(step: MacroStep.Key): Int =
    (if (step.ctrl) KeyHandler.KEYMOD_CTRL else 0) or
        (if (step.alt) KeyHandler.KEYMOD_ALT else 0) or
        (if (step.shift) KeyHandler.KEYMOD_SHIFT else 0)
