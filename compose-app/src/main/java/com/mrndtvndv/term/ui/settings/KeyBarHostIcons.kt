package com.mrndtvndv.term.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Commit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.data.prefs.KeyBarHostAction

fun KeyBarHostAction.displayName(): String = when (this) {
    KeyBarHostAction.Git -> "Git"
    KeyBarHostAction.Sftp -> "SFTP"
}

/** The icons the Herdr route uses for its built-in Git and Files buttons. */
internal fun KeyBarHostAction.icon(): ImageVector = when (this) {
    KeyBarHostAction.Git -> Icons.Default.Commit
    KeyBarHostAction.Sftp -> Icons.Default.Folder
}

@Composable
internal fun HostActionPreview(kind: KeyBarHostAction) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = kind.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${kind.displayName()} icon. Herdr sessions only: outside Herdr this item is hidden " +
                "and its swipes are not offered.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
