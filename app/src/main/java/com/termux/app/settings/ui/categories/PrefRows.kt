package com.termux.app.settings.ui.categories

import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.termux.app.settings.ui.ChoiceRow
import com.termux.app.settings.ui.SwitchRow
import com.termux.shared.logger.Logger

@Composable
internal fun PrefSwitchRow(
    title: String,
    checked: Boolean,
    summaryOn: String,
    summaryOff: String,
    onChange: (Boolean) -> Unit,
    shapes: ListItemShapes,
) {
    var value by remember { mutableStateOf(checked) }
    SwitchRow(
        title = title,
        checked = value,
        onChange = {
            value = it
            onChange(it)
        },
        shapes = shapes,
        summary = if (value) summaryOn else summaryOff,
    )
}

@Composable
internal fun LogLevelRow(title: String, level: Int, onSelect: (Int) -> Unit, shapes: ListItemShapes) {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(level) }
    val levels = Logger.getLogLevelsArray()
    val labels = Logger.getLogLevelLabelsArray(context, levels, true)
    ChoiceRow(
        title = title,
        options = levels.map { it.toString() }.zip(labels.map { it.toString() }),
        selected = selected.toString(),
        onSelect = {
            selected = it.toInt()
            onSelect(selected)
        },
        shapes = shapes,
    )
}
