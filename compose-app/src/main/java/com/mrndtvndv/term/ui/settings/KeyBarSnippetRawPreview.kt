package com.mrndtvndv.term.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.MacroStep
import com.mrndtvndv.term.data.prefs.asciiControlCode
import com.mrndtvndv.term.data.prefs.producedChar

@Composable
internal fun SnippetRawReadout(rawInput: String, endsWithEnter: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = "raw: $rawInput",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (!endsWithEnter) {
                Text(
                    "No Enter at the end, so it waits for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

internal fun MacroStep.rawDisplay(): String = when (this) {
    is MacroStep.Text -> text.displayText()
    is MacroStep.Key -> rawDisplay()
}

private fun MacroStep.Key.rawDisplay(): String =
    producedChar()?.rawDisplay(ctrl, alt, summary()) ?: specialRawDisplay()

private fun Char.rawDisplay(ctrl: Boolean, alt: Boolean, fallback: String): String {
    val payload = if (ctrl) asciiControlCode(this)?.visibleControl() ?: "<$fallback>" else toString()
    val isFallback = ctrl && payload.startsWith('<')
    return (if (alt && !isFallback) "⎋" else "") + payload
}

private fun Char.visibleControl(): String =
    if (code in 0..31) "^${(code + 64).toChar()}" else toString()

private fun MacroStep.Key.specialRawDisplay(): String {
    if (ctrl || alt || shift) return "<${summary()}>"
    return when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> "⏎"
        KeyEvent.KEYCODE_TAB -> "⇥"
        KeyEvent.KEYCODE_ESCAPE -> "⎋"
        else -> "<${macroKeyLabel(keyCode)}>"
    }
}

private fun String.displayText(): String = replace('\n', '↵').replace('\r', '↵')
