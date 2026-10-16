package com.mrndtvndv.term.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.data.prefs.MacroKeyCatalog
import com.mrndtvndv.term.data.prefs.MacroStep

@Composable
internal fun SequenceEditor(steps: List<MacroStep>, onChange: (List<MacroStep>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Description("Steps run in order. Nothing is added between them, so add an Enter key step to submit.")
        val textLength = steps.sumOf { (it as? MacroStep.Text)?.text?.length ?: 0 }
        if (textLength > KeyBarLimits.MaxTextLength) {
            Text(
                text = "Use ${KeyBarLimits.MaxTextLength} characters or fewer across all text steps " +
                    "($textLength used).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (steps.isEmpty()) {
            Text(
                text = "Add at least one step.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        steps.forEachIndexed { index, step ->
            HorizontalDivider()
            StepEditor(
                index = index,
                lastIndex = steps.lastIndex,
                step = step,
                onChange = { onChange(steps.replacedAt(index, it)) },
                onMove = { delta -> onChange(steps.moved(index, index + delta)) },
                onDelete = { onChange(steps.filterIndexed { i, _ -> i != index }) },
            )
        }
        val canAdd = steps.size < KeyBarLimits.MaxMacroSteps
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = canAdd, onClick = { onChange(steps + MacroStep.Text("")) }) { Text("Add text") }
            OutlinedButton(
                enabled = canAdd,
                onClick = { onChange(steps + MacroStep.Key(KeyEvent.KEYCODE_ESCAPE)) },
            ) { Text("Add key") }
        }
        if (!canAdd) Description("A sequence holds up to ${KeyBarLimits.MaxMacroSteps} steps.")
    }
}

@Composable
private fun StepEditor(
    index: Int,
    lastIndex: Int,
    step: MacroStep,
    onChange: (MacroStep) -> Unit,
    onMove: (delta: Int) -> Unit,
    onDelete: () -> Unit,
) {
    val name = "step ${index + 1}"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${index + 1}. ${step.summary()}",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move $name up")
            }
            IconButton(onClick = { onMove(1) }, enabled = index < lastIndex) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move $name down")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete $name")
            }
        }
        when (step) {
            is MacroStep.Text -> StepTextField(text = step.text, onChange = onChange)
            is MacroStep.Key -> StepKeyEditor(step = step, onChange = onChange)
        }
    }
}

@Composable
private fun StepTextField(text: String, onChange: (MacroStep) -> Unit) {
    val error = MacroStep.Text(text).stepError()
    OutlinedTextField(
        value = text,
        onValueChange = { onChange(MacroStep.Text(it)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Text") },
        minLines = 1,
        maxLines = 4,
        isError = error != null,
        supportingText = { Text(error ?: "Sent as typed. ${text.length}/${KeyBarLimits.MaxTextLength}") },
    )
}

@Composable
private fun StepKeyEditor(step: MacroStep.Key, onChange: (MacroStep) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OptionMenu(
            selected = step.keyCode,
            options = MacroKeyCatalog.map { it.keyCode },
            optionLabel = { macroKeyLabel(it) },
            onSelect = { onChange(step.copy(keyCode = it)) },
        )
        ModifierSwitch(label = "Ctrl", checked = step.ctrl) { onChange(step.copy(ctrl = it)) }
        ModifierSwitch(label = "Alt", checked = step.alt) { onChange(step.copy(alt = it)) }
        ModifierSwitch(label = "Shift", checked = step.shift) { onChange(step.copy(shift = it)) }
        step.stepError()?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ModifierSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}
