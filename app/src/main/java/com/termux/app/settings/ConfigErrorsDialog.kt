package com.termux.app.settings

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.ui.TermuxTheme
import com.termux.shared.termux.TermuxConstants

@Composable
fun ConfigErrorsDialog(errors: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.config_errors_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.config_errors_message, TermuxConstants.TERMUX_SETTINGS_FILE_PATH))
                errors.forEach { error -> Text("• $error") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

/** Survives activity recreation so a dismissed list isn't shown again. */
private var dismissedErrors by mutableStateOf<List<String>>(emptyList())

fun attachConfigErrorsDialog(activity: ComponentActivity) {
    val view = ComposeView(activity).apply {
        // The dialog has its own window; keep this host from taking focus from the terminal.
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setContent {
            val effective by TermuxSettingsRepository.get().effective.collectAsState()
            val errors = effective.errors
            TermuxTheme {
                if (errors.isNotEmpty() && errors != dismissedErrors) {
                    ConfigErrorsDialog(errors) { dismissedErrors = errors }
                }
            }
        }
    }
    activity.addContentView(
        view,
        ViewGroup.LayoutParams(0, 0),
    )
}
