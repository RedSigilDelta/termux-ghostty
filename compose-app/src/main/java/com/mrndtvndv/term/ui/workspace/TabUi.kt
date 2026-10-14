package com.mrndtvndv.term.ui.workspace

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.mrndtvndv.term.TabType
import com.mrndtvndv.term.server.TerminalEntry
import com.mrndtvndv.term.workspace.Tab
import java.io.File

/** Menu of tab types to add. SFTP and Git are offered only when the server supports them. */
@Composable
fun AddTabMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    canAddSftp: Boolean,
    canAddGit: Boolean,
    onAdd: (TabType) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Terminal") },
            leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
            onClick = {
                onDismiss()
                onAdd(TabType.Terminal)
            },
        )
        if (canAddSftp) {
            DropdownMenuItem(
                text = { Text("SFTP") },
                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onAdd(TabType.Sftp)
                },
            )
        }
        if (canAddGit) {
            DropdownMenuItem(
                text = { Text("Git") },
                leadingIcon = { Icon(Icons.Default.Commit, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onAdd(TabType.Git)
                },
            )
        }
    }
}

internal fun Tab.icon(): ImageVector = when (this) {
    is Tab.Terminal -> Icons.Default.Terminal
    is Tab.Sftp -> Icons.Default.Folder
    is Tab.Git -> Icons.Default.Commit
    is Tab.Viewer -> Icons.AutoMirrored.Filled.Article
}

/** Label for a tab chip. Terminals fall back to the shell's title, then "Terminal". */
fun tabLabel(tab: Tab, terminals: Map<String, TerminalEntry>): String = when (tab) {
    is Tab.Terminal -> tab.name
        ?: terminals[tab.id]?.session?.title?.takeIf { it.isNotBlank() }
        ?: "Terminal"
    is Tab.Sftp -> lastPathSegment(tab.dir)
    is Tab.Git -> "Git · " + lastPathSegment(tab.repoDir)
    is Tab.Viewer -> File(tab.path).name
}

private fun lastPathSegment(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
