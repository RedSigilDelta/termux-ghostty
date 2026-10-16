package com.mrndtvndv.term.ui.settings

import android.view.KeyEvent
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarKeyCatalog
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.isSwipeable
import com.mrndtvndv.term.data.prefs.isValidMacro

enum class KeyBarDirection(val arrow: String) {
    Up("↑"),
    Down("↓"),
    Left("←"),
    Right("→"),
}

fun keyLabel(keyCode: Int): String =
    KeyBarKeyCatalog.firstOrNull { it.keyCode == keyCode }?.label ?: "Key $keyCode"

/** Label a new item gets from this action, or null when the action has no natural label (text and sequences). */
fun KeyBarAction.defaultLabel(): String? = when (this) {
    is KeyBarAction.Key -> keyLabel(keyCode)
    is KeyBarAction.Text -> null
    is KeyBarAction.Macro -> null
    is KeyBarAction.Host -> kind.displayName()
    KeyBarAction.Ctrl -> "Ctrl"
    KeyBarAction.Alt -> "Alt"
    KeyBarAction.Joystick -> "Arrows"
}

/** Compact text for bar hints: the macro text is clipped, with line breaks shown as ↵. */
fun KeyBarAction.shortLabel(): String = when (this) {
    is KeyBarAction.Text -> text.replace("\n", "↵").let { if (it.length > 8) it.take(7) + "…" else it }
    is KeyBarAction.Macro -> "${steps.size} steps"
    else -> defaultLabel().orEmpty()
}

/** Swipes only fire on key and macro taps; the runtime ignores them on Ctrl, Alt and Joystick. */
fun KeyBarItem.swipeHints(): List<String> = if (!tap.isSwipeable()) emptyList() else listOfNotNull(
    swipeUp?.let { "${KeyBarDirection.Up.arrow} ${it.shortLabel()}" },
    swipeDown?.let { "${KeyBarDirection.Down.arrow} ${it.shortLabel()}" },
    swipeLeft?.let { "${KeyBarDirection.Left.arrow} ${it.shortLabel()}" },
    swipeRight?.let { "${KeyBarDirection.Right.arrow} ${it.shortLabel()}" },
)

/** Editable copy of one item. Directions missing from [swipes] are unassigned. */
data class KeyBarItemDraft(
    val label: String = "Esc",
    val tap: KeyBarAction = KeyBarAction.Key(KeyEvent.KEYCODE_ESCAPE),
    val swipes: Map<KeyBarDirection, KeyBarAction> = emptyMap(),
)

fun KeyBarItem.toDraft(): KeyBarItemDraft = KeyBarItemDraft(
    label = label,
    tap = tap,
    swipes = listOfNotNull(
        swipeUp?.let { KeyBarDirection.Up to it },
        swipeDown?.let { KeyBarDirection.Down to it },
        swipeLeft?.let { KeyBarDirection.Left to it },
        swipeRight?.let { KeyBarDirection.Right to it },
    ).toMap(),
)

fun KeyBarItemDraft.toItem(id: String): KeyBarItem = KeyBarItem(
    id = id,
    label = label.trim(),
    tap = tap,
    swipeUp = activeSwipe(KeyBarDirection.Up),
    swipeDown = activeSwipe(KeyBarDirection.Down),
    swipeLeft = activeSwipe(KeyBarDirection.Left),
    swipeRight = activeSwipe(KeyBarDirection.Right),
)

private fun KeyBarItemDraft.activeSwipe(direction: KeyBarDirection): KeyBarAction? =
    swipes[direction].takeIf { tap.isSwipeable() }

fun KeyBarItemDraft.labelError(): String? = when {
    label.isBlank() -> "Enter a label"
    label.trim().length > KeyBarLimits.MaxLabelLength -> "Use ${KeyBarLimits.MaxLabelLength} characters or fewer"
    else -> null
}

fun KeyBarAction.validationError(): String? = when (this) {
    is KeyBarAction.Text -> when {
        text.isEmpty() -> "Macro can't be empty"
        text.length > KeyBarLimits.MaxTextLength -> "Use ${KeyBarLimits.MaxTextLength} characters or fewer"
        else -> null
    }
    is KeyBarAction.Macro -> when {
        steps.isEmpty() -> "Add at least one step"
        steps.size > KeyBarLimits.MaxMacroSteps -> "Use ${KeyBarLimits.MaxMacroSteps} steps or fewer"
        !isValidMacro(steps) -> "Fix the steps marked below"
        else -> null
    }
    else -> null
}

fun KeyBarItemDraft.isValid(): Boolean {
    val swipeActions = if (tap.isSwipeable()) swipes.values else emptyList()
    return labelError() == null && (listOf(tap) + swipeActions).all { it.validationError() == null }
}

fun KeyBarItemDraft.withTap(action: KeyBarAction): KeyBarItemDraft =
    copy(label = autoLabel(label, tap, action), tap = action)

fun KeyBarItemDraft.withSwipe(direction: KeyBarDirection, action: KeyBarAction?): KeyBarItemDraft =
    copy(swipes = if (action == null) swipes - direction else swipes + (direction to action))

/** Follows the new action's name only while the label is still blank or still the old action's name. */
private fun autoLabel(current: String, previous: KeyBarAction, next: KeyBarAction): String {
    val suggested = next.defaultLabel() ?: return current
    return if (current.isBlank() || current == previous.defaultLabel()) suggested else current
}
