package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

sealed interface WorkspaceDialogs {
    data class NewWorkspace(val defaultRoot: String) : WorkspaceDialogs
    data class RenameWorkspace(val workspaceId: String, val name: String) : WorkspaceDialogs
    data class RenameTerminal(val tabId: String, val name: String?) : WorkspaceDialogs
    data class DeleteWorkspace(val workspaceId: String, val name: String) : WorkspaceDialogs
}

/** Name/root dialogs for workspaces and terminal tabs. A blank name is rejected for workspaces. */
@Suppress("LongParameterList")
@Composable
fun WorkspaceDialogHost(
    dialog: WorkspaceDialogs?,
    onDismiss: () -> Unit,
    onCreateWorkspace: (name: String, root: String) -> Unit,
    onRenameWorkspace: (workspaceId: String, name: String) -> Unit,
    onRenameTerminal: (tabId: String, name: String?) -> Unit,
    onDeleteWorkspace: (workspaceId: String) -> Unit,
) {
    when (dialog) {
        is WorkspaceDialogs.DeleteWorkspace -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete workspace?") },
            text = { Text("\"${dialog.name}\" and its terminals will be closed, including remote sessions.") },
            confirmButton = {
                TextButton(onClick = { onDeleteWorkspace(dialog.workspaceId) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        is WorkspaceDialogs.NewWorkspace -> WorkspaceFieldsDialog(
            title = "New workspace",
            initialName = "",
            initialRoot = dialog.defaultRoot,
            showRoot = true,
            onDismiss = onDismiss,
            onConfirm = { name, root -> onCreateWorkspace(name, root) },
        )
        is WorkspaceDialogs.RenameWorkspace -> WorkspaceFieldsDialog(
            title = "Rename workspace",
            initialName = dialog.name,
            initialRoot = "",
            showRoot = false,
            onDismiss = onDismiss,
            onConfirm = { name, _ -> onRenameWorkspace(dialog.workspaceId, name) },
        )
        is WorkspaceDialogs.RenameTerminal -> TerminalNameDialog(
            initialName = dialog.name.orEmpty(),
            onDismiss = onDismiss,
            onConfirm = { name -> onRenameTerminal(dialog.tabId, name) },
        )
        null -> Unit
    }
}

@Composable
private fun WorkspaceFieldsDialog(
    title: String,
    initialName: String,
    initialRoot: String,
    showRoot: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, root: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var root by remember { mutableStateOf(initialRoot) }
    val canConfirm = name.isNotBlank() && (!showRoot || root.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                if (showRoot) {
                    OutlinedTextField(
                        value = root,
                        onValueChange = { root = it },
                        label = { Text("Root directory") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), root.trim()) },
                enabled = canConfirm,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TerminalNameDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename terminal") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Leave blank to use the default") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim().ifEmpty { null }) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
