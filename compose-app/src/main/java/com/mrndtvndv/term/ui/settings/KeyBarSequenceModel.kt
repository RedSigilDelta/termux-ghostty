package com.mrndtvndv.term.ui.settings

import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarKeyCatalog
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

fun actionFromSteps(steps: List<MacroStep>): KeyBarAction? {
    val meaningfulSteps = steps.filterNot { it == MacroStep.Text("") }
    if (meaningfulSteps.isEmpty()) return null

    return when (val step = meaningfulSteps.singleOrNull()) {
        is MacroStep.Text -> KeyBarAction.Text(step.text)
        is MacroStep.Key -> if (step.isPlainCatalogKey()) {
            KeyBarAction.Key(step.keyCode)
        } else {
            KeyBarAction.Macro(meaningfulSteps)
        }
        null -> KeyBarAction.Macro(meaningfulSteps)
    }
}

fun KeyBarAction.toSteps(): List<MacroStep> = when (this) {
    is KeyBarAction.Text -> listOf(MacroStep.Text(text))
    is KeyBarAction.Key -> listOf(MacroStep.Key(keyCode))
    is KeyBarAction.Macro -> steps
    KeyBarAction.Ctrl, KeyBarAction.Alt, KeyBarAction.Joystick, is KeyBarAction.Host -> emptyList()
}

/** Human-readable text for editor rows and slots. Callers apply their own line clipping. */
fun KeyBarAction.summary(): String = when (this) {
    is KeyBarAction.Key -> keyLabel(keyCode)
    is KeyBarAction.Text -> text.replace("\n", "↵")
    is KeyBarAction.Macro -> steps.joinToString(" ") { it.summary() }
    is KeyBarAction.Host -> kind.displayName()
    KeyBarAction.Ctrl, KeyBarAction.Alt, KeyBarAction.Joystick -> defaultLabel().orEmpty()
}

private fun MacroStep.Key.isPlainCatalogKey(): Boolean =
    !ctrl && !alt && !shift && KeyBarKeyCatalog.any { it.keyCode == keyCode }

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
