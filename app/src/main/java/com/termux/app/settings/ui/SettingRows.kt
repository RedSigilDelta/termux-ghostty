package com.termux.app.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.R

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun ActionRow(title: String, summary: String? = null, onClick: (() -> Unit)? = null) {
    SettingRow(title = title, summary = summary, locked = false, enabled = true, onClick = onClick)
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    summary: String? = null,
    locked: Boolean = false,
    enabled: Boolean = true,
) {
    SettingRow(
        title = title,
        summary = summary,
        locked = locked,
        enabled = enabled,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = !locked && enabled) },
    )
}

@Composable
fun ChoiceRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    locked: Boolean = false,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }

    SettingRow(
        title = title,
        summary = options.firstOrNull { it.first == selected }?.second,
        locked = locked,
        enabled = enabled,
        onClick = { open = true },
    )

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup()) {
                    options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = value == selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        onSelect(value)
                                        open = false
                                    },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = null)
                            Text(label, modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

@Composable
fun NumberRow(
    title: String,
    value: Number,
    range: ClosedRange<Float>,
    isFloat: Boolean,
    onChange: (Number) -> Unit,
    locked: Boolean = false,
    enabled: Boolean = true,
    isValid: (Number) -> Boolean = { it.toFloat() in range },
) {
    var open by remember { mutableStateOf(false) }

    SettingRow(
        title = title,
        summary = value.toString(),
        locked = locked,
        enabled = enabled,
        onClick = { open = true },
    )

    if (open) {
        NumberDialog(
            title = title,
            value = value,
            range = range,
            isFloat = isFloat,
            isValid = isValid,
            onDismiss = { open = false },
            onConfirm = {
                open = false
                onChange(it)
            },
        )
    }
}

@Composable
private fun NumberDialog(
    title: String,
    value: Number,
    range: ClosedRange<Float>,
    isFloat: Boolean,
    isValid: (Number) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Number) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    val number = if (isFloat) text.toFloatOrNull() else text.toIntOrNull()
    val valid = number != null && isValid(number)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (isFloat) KeyboardType.Decimal else KeyboardType.Number),
                supportingText = {
                    Text(stringResource(R.string.settings_number_range, range.start.label(isFloat), range.endInclusive.label(isFloat)))
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (number != null) onConfirm(number) }, enabled = valid) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun Float.label(isFloat: Boolean): String = if (isFloat) toString() else toInt().toString()

@Composable
fun TextRow(
    title: String,
    value: String?,
    multiline: Boolean,
    validate: (String) -> String?,
    onChange: (String) -> Unit,
    locked: Boolean = false,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }

    SettingRow(
        title = title,
        summary = value,
        locked = locked,
        enabled = enabled,
        onClick = { open = true },
    )

    if (open) {
        TextDialog(
            title = title,
            value = value.orEmpty(),
            multiline = multiline,
            validate = validate,
            onDismiss = { open = false },
            onConfirm = {
                open = false
                onChange(it)
            },
        )
    }
}

@Composable
private fun TextDialog(
    title: String,
    value: String,
    multiline: Boolean,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    val error = validate(text)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                isError = error != null,
                supportingText = {
                    if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = error == null) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun SettingRow(
    title: String,
    summary: String?,
    locked: Boolean,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val disabled = locked || !enabled
    ListItem(
        supportingContent = {
            val text = if (locked) stringResource(R.string.settings_set_in_config_file) else summary
            if (text != null) Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = trailing,
        modifier = Modifier
            .alpha(if (disabled) 0.38f else 1f)
            .clickable(enabled = onClick != null && !disabled) { onClick?.invoke() },
    ) {
        Text(title)
    }
}
