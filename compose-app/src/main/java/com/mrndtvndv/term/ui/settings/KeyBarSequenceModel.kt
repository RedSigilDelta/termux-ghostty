package com.mrndtvndv.term.ui.settings

import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.MacroKeyCatalog
import com.mrndtvndv.term.data.prefs.MacroStep
import com.mrndtvndv.term.data.prefs.isSupported

fun macroKeyLabel(keyCode: Int): String =
    MacroKeyCatalog.firstOrNull { it.keyCode == keyCode }?.label ?: keyLabel(keyCode)

/** One line for a sequence step row. Modifiers are listed before the key, e.g. Ctrl+Shift+Up. */
fun MacroStep.summary(): String = when (this) {
    is MacroStep.Text -> text.replace("\n", "↵").let { if (it.length > 16) it.take(15) + "…" else it }
    is MacroStep.Key -> (
        listOfNotNull(
            "Ctrl".takeIf { ctrl },
            "Alt".takeIf { alt },
            "Shift".takeIf { shift },
        ) + macroKeyLabel(keyCode)
    ).joinToString("+")
}

/** Messages only. Whether a step is valid comes from the shared data.prefs rules. */
fun MacroStep.stepError(): String? = when (this) {
    is MacroStep.Text -> when {
        text.isEmpty() -> "Step text can't be empty"
        !KeyBarLimits.isValidText(text) -> "Use ${KeyBarLimits.MaxTextLength} characters or fewer"
        else -> null
    }
    is MacroStep.Key -> if (isSupported()) null else "Unsupported key or modifier combination"
}

fun List<MacroStep>.moved(from: Int, to: Int): List<MacroStep> = toMutableList().apply { add(to, removeAt(from)) }

fun List<MacroStep>.replacedAt(index: Int, step: MacroStep): List<MacroStep> =
    mapIndexed { i, current -> if (i == index) step else current }
