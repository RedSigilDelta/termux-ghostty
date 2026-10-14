package com.termux.app.workspace.ui

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Html
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.R
import com.termux.app.terminal.io.FloatingBar
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Listing {
    data object Loading : Listing
    data object Unreadable : Listing
    class Loaded(val entries: List<FileEntry>) : Listing
}

private data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val itemCount: Int,
    val length: Long,
    val lastModified: Long,
)

/** Breadcrumb (label to path) pairs for [dir]; a directory inside [home] starts at a "~" crumb. */
internal fun breadcrumbSegments(dir: String, home: String): List<Pair<String, String>> {
    val insideHome = dir == home || dir.startsWith("$home/")
    val base = if (insideHome) home else ""
    val segments = mutableListOf(if (insideHome) "~" to home else "/" to "/")
    var path = base
    dir.removePrefix(base).split('/').filter { it.isNotEmpty() }.forEach { name ->
        path += "/$name"
        segments += name to path
    }
    return segments
}

@Composable
fun FilesTab(
    dir: String,
    root: String,
    tabsButton: @Composable () -> Unit,
    onNavigate: (dir: String) -> Unit,
    onOpenFile: (path: String) -> Unit,
    onOpenTerminal: (dir: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val home = TermuxConstants.TERMUX_HOME_DIR_PATH
    val parent = File(dir).parent
    var refreshKey by remember { mutableIntStateOf(0) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    val listing by produceState<Listing>(Listing.Loading, dir, refreshKey) {
        value = withContext(Dispatchers.IO) { listDirectory(dir) }
    }

    fun runFileOp(failure: Int, op: () -> Boolean) {
        scope.launch {
            if (!withContext(Dispatchers.IO) { op() }) Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
            refreshKey++
        }
    }

    BackHandler(enabled = dir != root && parent != null) {
        if (parent != null) onNavigate(parent)
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                FilesBreadcrumbs(dir = dir, home = home, onNavigate = onNavigate)
                HorizontalDivider()
                when (val current = listing) {
                    Listing.Loading -> Unit
                    Listing.Unreadable -> Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.files_unreadable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    is Listing.Loaded -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        items(current.entries, key = { it.file.path }) { entry ->
                            FileRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) onNavigate(entry.file.path) else onOpenFile(entry.file.path)
                                },
                                onOpenTerminal = { onOpenTerminal(entry.file.path) },
                                onRename = { renaming = entry.file },
                                onDelete = { deleting = entry.file },
                            )
                        }
                    }
                }
            }

            FloatingBar(
                tabsButton = tabsButton,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            ) {
                IconButton(onClick = { parent?.let(onNavigate) }, enabled = parent != null) {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.files_up))
                }
                IconButton(onClick = { creatingFolder = true }) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = stringResource(R.string.files_new_folder))
                }
                IconButton(onClick = { onOpenTerminal(dir) }) {
                    Icon(Icons.Rounded.Terminal, contentDescription = stringResource(R.string.files_open_terminal_here))
                }
            }

            renaming?.let { file ->
                NameDialog(
                    title = stringResource(R.string.files_rename),
                    initialName = file.name,
                    onConfirm = { name ->
                        renaming = null
                        runFileOp(R.string.files_rename_failed) {
                            val target = File(file.parentFile, name)
                            !target.exists() && file.renameTo(target)
                        }
                    },
                    onDismiss = { renaming = null },
                )
            }
            if (creatingFolder) {
                NameDialog(
                    title = stringResource(R.string.files_new_folder),
                    initialName = "",
                    onConfirm = { name ->
                        creatingFolder = false
                        runFileOp(R.string.files_create_failed) { File(dir, name).mkdir() }
                    },
                    onDismiss = { creatingFolder = false },
                )
            }
            deleting?.let { file ->
                AlertDialog(
                    onDismissRequest = { deleting = null },
                    title = { Text(stringResource(R.string.files_delete)) },
                    text = { Text(stringResource(R.string.files_delete_message, file.name)) },
                    confirmButton = {
                        TextButton(onClick = {
                            deleting = null
                            runFileOp(R.string.files_delete_failed) { file.deleteRecursively() }
                        }) {
                            Text(stringResource(R.string.files_delete))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleting = null }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                    },
                )
            }
        }
    }
}

private fun listDirectory(dir: String): Listing {
    val files = File(dir).listFiles() ?: return Listing.Unreadable
    val entries = files.map { file ->
        val isDirectory = file.isDirectory
        FileEntry(
            file = file,
            isDirectory = isDirectory,
            itemCount = if (isDirectory) file.list()?.size ?: 0 else 0,
            length = file.length(),
            lastModified = file.lastModified(),
        )
    }
    return Listing.Loaded(entries.sortedWith(compareBy<FileEntry>({ !it.isDirectory }, { it.file.name.lowercase() })))
}

@Composable
private fun FilesBreadcrumbs(dir: String, home: String, onNavigate: (dir: String) -> Unit) {
    val segments = remember(dir, home) { breadcrumbSegments(dir, home) }
    val lastRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(dir) { lastRequester.bringIntoView() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, (label, path) ->
            val isLast = index == segments.lastIndex
            if (index > 0) {
                Icon(
                    Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .then(if (isLast) Modifier.bringIntoViewRequester(lastRequester) else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onNavigate(path) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    onClick: () -> Unit,
    onOpenTerminal: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val file = entry.file
    var menuOpen by remember { mutableStateOf(false) }
    val supporting = if (entry.isDirectory) {
        pluralStringResource(R.plurals.files_item_count, entry.itemCount, entry.itemCount)
    } else {
        val modified = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.lastModified))
        "${Formatter.formatShortFileSize(context, entry.length)} · $modified"
    }
    Box(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            supportingContent = { Text(supporting, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(fileIcon(entry), contentDescription = null) },
            modifier = Modifier
                .alpha(if (file.name.startsWith(".")) 0.6f else 1f)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        ) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (entry.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_open_terminal_here)) },
                    leadingIcon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onOpenTerminal()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_rename)) },
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_delete)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

private fun fileIcon(entry: FileEntry): ImageVector {
    if (entry.isDirectory) return Icons.Rounded.Folder
    return when (entry.file.extension.lowercase()) {
        "md", "markdown" -> Icons.Rounded.Description
        "html", "htm" -> Icons.Rounded.Html
        "png", "jpg", "jpeg", "gif", "webp" -> Icons.Rounded.Image
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile
    }
}

@Composable
private fun NameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.files_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank() && name != initialName,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
