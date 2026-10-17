package com.mrndtvndv.term.ui.addserver

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.domain.AuthType
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.herdr.FocusBridgeUiState
import com.mrndtvndv.term.ui.components.ImagePasteSettingsSection
import com.mrndtvndv.term.ui.components.rememberImagePasteSettingsState

@Suppress("LongMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onSave: (ServerConfig) -> Unit,
    onBack: () -> Unit,
    initialConfig: ServerConfig? = null,
    focusBridge: FocusBridgeControls? = null,
    modifier: Modifier = Modifier,
) {
    val initialPassword = (initialConfig?.auth as? AuthType.Password)?.password.orEmpty()
    var label by remember(initialConfig) { mutableStateOf(initialConfig?.label ?: "") }
    var host by remember(initialConfig) { mutableStateOf(initialConfig?.host ?: "") }
    var portString by remember(initialConfig) { mutableStateOf(initialConfig?.port?.toString() ?: "22") }
    var username by remember(initialConfig) { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember(initialConfig) { mutableStateOf(initialPassword) }
    var useGhosttyTerm by remember(initialConfig) { mutableStateOf(initialConfig?.useGhosttyTerm ?: false) }
    var herdrEnabled by remember(initialConfig) { mutableStateOf(initialConfig?.herdrEnabled ?: false) }
    var herdrSocketPath by remember(initialConfig) { mutableStateOf(initialConfig?.herdrSocketPath.orEmpty()) }
    val socketPathValid = herdrSocketPath.isBlank() || herdrSocketPath.trim().startsWith("/")
    val imagePasteState = rememberImagePasteSettingsState(initialConfig)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initialConfig == null) "Add Server" else "Edit Server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = portString,
                onValueChange = { portString = it },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use xterm-ghostty")
                    Text(
                        "Needs Ghostty's terminfo on the server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useGhosttyTerm, onCheckedChange = { useGhosttyTerm = it })
            }

            HerdrSection(
                herdrEnabled = herdrEnabled,
                onHerdrEnabledChange = { herdrEnabled = it },
                herdrSocketPath = herdrSocketPath,
                onHerdrSocketPathChange = { herdrSocketPath = it },
                socketPathValid = socketPathValid,
                focusBridge = focusBridge,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            ImagePasteSettingsSection(
                state = imagePasteState,
                directorySupportingText =
                    "Remote directory for uploaded files and pasted images. Required for uploads.",
                activeSupportingText = "Upload media and files or paste clipboard images into terminal",
            )

            Button(
                onClick = {
                    onSave(
                        ServerConfig(
                            id = initialConfig?.id ?: java.util.UUID.randomUUID().toString(),
                            label = label,
                            host = host,
                            port = parsePort(portString),
                            username = username,
                            auth = AuthType.Password(password),
                            useGhosttyTerm = useGhosttyTerm,
                            herdrEnabled = herdrEnabled,
                            herdrSocketPath = herdrSocketPath.trim().ifEmpty { null },
                            // Install and Remove persist this flag themselves, so saving the form keeps the stored
                            // value.
                            herdrFocusBridgeEnabled = initialConfig?.herdrFocusBridgeEnabled ?: false,
                            imagePasteEnabled = imagePasteState.isPasteActive,
                            imagePasteDirectory = imagePasteState.trimmedDirectory,
                            imagePasteAutoCleanup = imagePasteState.autoCleanup,
                            imagePasteMaxFiles = imagePasteState.maxFiles,
                        )
                    )
                },
                enabled = host.isNotBlank() && username.isNotBlank() && socketPathValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun HerdrSection(
    herdrEnabled: Boolean,
    onHerdrEnabledChange: (Boolean) -> Unit,
    herdrSocketPath: String,
    onHerdrSocketPathChange: (String) -> Unit,
    socketPathValid: Boolean,
    focusBridge: FocusBridgeControls?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("herdr workspaces")
            Text(
                "Shows herdr workspaces from the server and attaches to their terminals directly. " +
                    "Plain tabs stay login shells. herdr must be installed there.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = herdrEnabled, onCheckedChange = onHerdrEnabledChange)
    }
    if (herdrEnabled) {
        OutlinedTextField(
            value = herdrSocketPath,
            onValueChange = onHerdrSocketPathChange,
            label = { Text("herdr socket path (optional)") },
            supportingText = {
                Text(
                    if (socketPathValid) {
                        "Absolute path on the server. Empty uses ~/.config/herdr/herdr.sock"
                    } else {
                        "Must be an absolute path, starting with /"
                    },
                )
            },
            isError = !socketPathValid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Shown only when editing a saved server, since Install and Remove act on a live connection to it.
        if (focusBridge != null) {
            FocusBridgeSection(focusBridge)
        }
    }
}

/** Install and Remove for the focus helper of the server being edited. Null when adding a server. */
data class FocusBridgeControls(
    val status: FocusBridgeUiState,
    /** True while this server has a live connection. Install and Remove act on that connection. */
    val connected: Boolean,
    val onInstall: () -> Unit,
    val onRemove: () -> Unit,
)

@Composable
private fun FocusBridgeSection(controls: FocusBridgeControls) {
    val status = controls.status
    val busy = status is FocusBridgeUiState.Working
    val actionsEnabled = controls.connected && !busy
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Fast focus helper")
        Text(
            "Runs a small helper on the SSH host so pane focus skips the herdr socket round trip. " +
                "Supported on macOS arm64 only. Focus uses the herdr socket until the helper is installed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (controls.connected) {
                "Connected. Other changes here apply the next time this server connects."
            } else {
                "Connect to this server to install or remove the helper."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (status) {
            FocusBridgeUiState.Idle -> Unit
            is FocusBridgeUiState.Working -> Text(
                status.stage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is FocusBridgeUiState.Done -> Text(status.message, style = MaterialTheme.typography.bodySmall)
            is FocusBridgeUiState.Failed -> Text(
                status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = controls.onInstall, enabled = actionsEnabled) { Text("Install") }
            TextButton(onClick = controls.onRemove, enabled = actionsEnabled) { Text("Remove") }
        }
    }
}

private fun parsePort(text: String): Int = text.toIntOrNull() ?: 22
