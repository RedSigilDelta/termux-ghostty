package com.mrndtvndv.term.ui.settings

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.MacroStep
import com.mrndtvndv.term.data.prefs.isSupported

internal class KeyBarSnippetComposerState(initialSteps: List<MacroStep>) {
    var steps by mutableStateOf(initialSteps)
        private set

    var activeTextIndex by mutableIntStateOf(initialSteps.indexOfLast { it is MacroStep.Text })
        private set

    var menuStepIndex by mutableStateOf<Int?>(null)

    val meaningfulSteps: List<MacroStep>
        get() = steps.filterNot { it == MacroStep.Text("") }

    fun activateText(index: Int) {
        if (steps.getOrNull(index) is MacroStep.Text) activeTextIndex = index
    }

    fun updateText(index: Int, value: String, onChange: (KeyBarAction?) -> Unit) {
        val currentText = steps.getOrNull(index) as? MacroStep.Text ?: return
        if (cannotStartAnotherStep(currentText, value)) return
        val otherTextLength = steps.mapIndexedNotNull { stepIndex, step ->
            (step as? MacroStep.Text)?.text?.length?.takeIf { stepIndex != index }
        }.sum()
        val availableLength = (KeyBarLimits.MaxTextLength - otherTextLength).coerceAtLeast(0)
        commit(steps.replacedAt(index, MacroStep.Text(value.take(availableLength))), index, onChange)
    }

    fun insertKey(step: MacroStep.Key, onChange: (KeyBarAction?) -> Unit) {
        if (meaningfulSteps.size >= KeyBarLimits.MaxMacroSteps || !step.isSupported()) return
        val activeText = steps.getOrNull(activeTextIndex) as? MacroStep.Text ?: return
        val nextSteps = steps.toMutableList()
        val keyIndex = if (activeText.text.isEmpty()) activeTextIndex else activeTextIndex + 1
        if (activeText.text.isEmpty()) nextSteps.removeAt(activeTextIndex)
        nextSteps.add(keyIndex, step)
        nextSteps.add(keyIndex + 1, MacroStep.Text(""))
        commit(nextSteps, keyIndex + 1, onChange)
    }

    fun moveStep(index: Int, direction: Int, onChange: (KeyBarAction?) -> Unit) {
        val destination = index + direction
        if (destination !in steps.indices) return
        val nextSteps = steps.moved(index, destination)
        val nextActiveIndex = activeTextIndex.movedIndex(index, destination)
        menuStepIndex = null
        commit(nextSteps, nextActiveIndex, onChange)
    }

    fun removeStep(index: Int, onChange: (KeyBarAction?) -> Unit) {
        val nextSteps = steps.toMutableList().apply { removeAt(index) }
        menuStepIndex = null
        commit(nextSteps, index.coerceAtMost(nextSteps.lastIndex), onChange)
    }

    fun removePreviousKeyForEmptyText(index: Int, onChange: (KeyBarAction?) -> Unit): Boolean {
        val previousIndex = index - 1
        val canRemovePreviousKey = index == activeTextIndex &&
            (steps.getOrNull(index) as? MacroStep.Text)?.text?.isEmpty() == true &&
            steps.getOrNull(previousIndex) is MacroStep.Key
        if (!canRemovePreviousKey) return false
        removeStep(previousIndex, onChange)
        return true
    }

    fun validationErrors(slot: KeyBarEditorSlot): List<String> {
        val action = actionFromSteps(steps) ?: KeyBarAction.Text("").takeIf { slot == KeyBarEditorSlot.Tap }
        return listOfNotNull(
            action?.validationError(),
            meaningfulSteps.firstNotNullOfOrNull { it.stepError() },
        ).distinct()
    }

    fun rawInput(): String = meaningfulSteps.joinToString(separator = "") { it.rawDisplay() }

    fun endsWithEnter(): Boolean =
        (meaningfulSteps.lastOrNull() as? MacroStep.Key)?.keyCode == KeyEvent.KEYCODE_ENTER

    private fun cannotStartAnotherStep(currentText: MacroStep.Text, value: String): Boolean =
        currentText.text.isEmpty() && value.isNotEmpty() && meaningfulSteps.size >= KeyBarLimits.MaxMacroSteps

    private fun commit(nextSteps: List<MacroStep>, preferredTextIndex: Int, onChange: (KeyBarAction?) -> Unit) {
        val normalizedSteps = mutableListOf<MacroStep>()
        var nextActiveIndex: Int? = null
        nextSteps.forEachIndexed { index, step ->
            if (step !is MacroStep.Text) {
                normalizedSteps += step
                return@forEachIndexed
            }
            val previousText = normalizedSteps.lastOrNull() as? MacroStep.Text
            if (previousText != null) {
                val mergedIndex = normalizedSteps.lastIndex
                normalizedSteps[mergedIndex] = MacroStep.Text(previousText.text + step.text)
                if (index == preferredTextIndex) nextActiveIndex = mergedIndex
                return@forEachIndexed
            }
            if (step.text.isEmpty() && index != preferredTextIndex) return@forEachIndexed
            normalizedSteps += step
            if (index == preferredTextIndex) nextActiveIndex = normalizedSteps.lastIndex
        }
        val activeIndex = nextActiveIndex
            ?: normalizedSteps.indexOfLast { it is MacroStep.Text }.takeIf { it >= 0 }
            ?: normalizedSteps.size.also { normalizedSteps += MacroStep.Text("") }
        steps = normalizedSteps
        activeTextIndex = activeIndex
        onChange(actionFromSteps(normalizedSteps))
    }
}

internal fun KeyBarAction?.initialSnippetSteps(): List<MacroStep> {
    val initialSteps = this?.toSteps().orEmpty().toMutableList()
    if (initialSteps.isEmpty() || initialSteps.last() !is MacroStep.Text) {
        initialSteps += MacroStep.Text("")
    }
    return initialSteps
}

private fun Int.movedIndex(from: Int, to: Int): Int = when {
    this == from -> to
    from < to && this in (from + 1)..to -> this - 1
    to < from && this in to until from -> this + 1
    else -> this
}
