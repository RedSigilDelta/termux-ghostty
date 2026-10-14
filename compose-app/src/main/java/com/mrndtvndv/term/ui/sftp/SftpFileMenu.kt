package com.mrndtvndv.term.ui.sftp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
internal fun SftpFileMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenFolder: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        if (onOpenFolder != null) {
            SftpMenuItem("Open folder", Icons.Default.FolderOpen, onDismiss, onOpenFolder)
        }
        if (onDownload != null) {
            SftpMenuItem("Download", Icons.Default.Download, onDismiss, onDownload)
        }
        SftpMenuItem("Rename", Icons.Default.Edit, onDismiss, onRename)
        SftpMenuItem("Delete", Icons.Default.Delete, onDismiss, onDelete, destructive = true)
    }
}

@Composable
private fun SftpMenuItem(
    label: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else LocalContentColor.current
            )
        },
        onClick = {
            onDismiss()
            onClick()
        }
    )
}
