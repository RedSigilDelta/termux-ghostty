package com.termux.app.settings

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
import androidx.compose.ui.res.stringResource
import com.termux.R
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

@Composable
fun ConfigErrorsDialogHost() {
    val effective by TermuxSettingsRepository.get().effective.collectAsState()
    val errors = effective.errors
    if (errors.isNotEmpty() && errors != dismissedErrors) {
        ConfigErrorsDialog(errors) { dismissedErrors = errors }
    }
}
