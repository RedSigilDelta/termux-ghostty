@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

typealias SettingRowContent = @Composable (ListItemShapes) -> Unit

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 20.dp, end = 12.dp, bottom = 8.dp),
    )
}

@Composable
fun SettingsGroup(title: String? = null, rows: List<SettingRowContent>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (title != null) SectionHeader(title)
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            rows.forEachIndexed { index, row ->
                row(ListItemDefaults.segmentedShapes(index, rows.size))
            }
        }
    }
}

@Composable
fun ActionRow(
    title: String,
    shapes: ListItemShapes,
    summary: String? = null,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        shapes = shapes,
        onClick = onClick,
        trailing = trailing,
    )
}

@Composable
fun NavigationRow(
    title: String,
    shapes: ListItemShapes,
    summary: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        shapes = shapes,
        onClick = onClick,
        leading = leading,
        trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
    )
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    shapes: ListItemShapes,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        shapes = shapes,
        onClick = { onChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    shapes: ListItemShapes,
    summary: String? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }

    SettingRow(
        title = title,
        summary = summary ?: options.firstOrNull { it.first == selected }?.second,
        enabled = enabled,
        shapes = shapes,
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
                TextButton(onClick = { open = false }) { Text("Cancel") }
            },
        )
    }
}

@Suppress("LongParameterList")
@Composable
fun NumberRow(
    title: String,
    value: Number,
    range: ClosedRange<Float>,
    isFloat: Boolean,
    onChange: (Number) -> Unit,
    shapes: ListItemShapes,
    summary: String = value.toString(),
    enabled: Boolean = true,
    isValid: (Number) -> Boolean = { it.toFloat() in range },
) {
    var open by remember { mutableStateOf(false) }

    SettingRow(
        title = title,
        summary = summary,
        enabled = enabled,
        shapes = shapes,
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
    var text by remember(value) { mutableStateOf(value.toString()) }
    val number: Number? = if (isFloat) text.toFloatOrNull() else text.toIntOrNull()
    val valid = number?.let(isValid) == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isFloat) KeyboardType.Decimal else KeyboardType.Number,
                ),
                supportingText = {
                    Text("Enter ${range.start.label(isFloat)} to ${range.endInclusive.label(isFloat)}")
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { number?.let(onConfirm) },
                enabled = valid,
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun Float.label(isFloat: Boolean): String = if (isFloat) toString() else toInt().toString()

@Suppress("LongParameterList")
@Composable
fun TextRow(
    title: String,
    value: String?,
    multiline: Boolean = false,
    validate: (String) -> String? = { null },
    onChange: (String) -> Unit,
    shapes: ListItemShapes,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }

    SettingRow(
        title = title,
        summary = value,
        enabled = enabled,
        shapes = shapes,
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
    var text by remember(value) { mutableStateOf(value) }
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
            TextButton(onClick = { onConfirm(text) }, enabled = error == null) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun SettingRow(
    title: String,
    summary: String?,
    enabled: Boolean,
    shapes: ListItemShapes,
    onClick: (() -> Unit)?,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val supporting: (@Composable () -> Unit)? = summary?.let {
        { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }

    if (onClick != null) {
        SegmentedListItem(
            onClick = onClick,
            shapes = shapes,
            enabled = enabled,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
            colors = ListItemDefaults.segmentedColors(),
        ) {
            Text(title)
        }
    } else {
        SegmentedListItem(
            shapes = shapes,
            enabled = enabled,
            leadingContent = leading,
            supportingContent = supporting,
            trailingContent = trailing,
            colors = ListItemDefaults.segmentedColors(),
        ) {
            Text(title)
        }
    }
}

@Composable
fun SwitchOption(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
