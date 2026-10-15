package com.mrndtvndv.term.ui.sftp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.navigation.AppNavDisplay
import com.mrndtvndv.term.navigation.SftpFolder
import com.mrndtvndv.term.ui.sftp.transfer.TransferType
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface SftpDialog {
    data class Rename(val file: SftpFile) : SftpDialog
    data class Delete(val file: SftpFile) : SftpDialog
}

@Suppress("LongMethod", "LongParameterList")
@Composable
fun SftpScreenRoute(
    viewModel: SftpViewModel,
    isTabActive: Boolean,
    onOpenFile: (SftpFile, File) -> Unit,
    onOpenFileError: (String) -> Unit,
    tabsButton: (@Composable () -> Unit)? = null,
) {
    val browser = viewModel.browser.collectAsStateWithLifecycle().value ?: return
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val tree by viewModel.tree.collectAsStateWithLifecycle()
    val trailPath by viewModel.trailPath.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SftpDialog?>(null) }
    val currentPath = browser.dir
    // Search belongs to the folder it was typed in.
    var isSearchActive by rememberSaveable(currentPath) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(currentPath) { mutableStateOf("") }
    val folders: List<NavKey> = remember(browser.back, currentPath) { (browser.back + currentPath).map(::SftpFolder) }
    val hazeState = rememberHazeState()

    fun showMessage(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val staged = withContext(Dispatchers.IO) { stagePickedFile(context, uri) }
            if (staged == null) {
                showMessage("Failed to read selected file")
            } else {
                viewModel.uploadFile(
                    source = staged,
                    onSuccess = { showMessage("Uploaded ${staged.name}") },
                    onError = ::showMessage
                )
            }
        }
    }

    when (val current = dialog) {
        is SftpDialog.Rename -> SftpRenameDialog(
            file = current.file,
            onDismiss = { dialog = null },
            onRename = { newName ->
                dialog = null
                viewModel.renameFile(
                    file = current.file,
                    newName = newName,
                    onSuccess = { showMessage("Renamed to ${newName.trim()}") },
                    onError = ::showMessage
                )
            }
        )
        is SftpDialog.Delete -> SftpDeleteDialog(
            file = current.file,
            onDismiss = { dialog = null },
            onDelete = {
                dialog = null
                viewModel.deleteFile(
                    file = current.file,
                    onSuccess = { showMessage("Deleted ${current.file.name}") },
                    onError = ::showMessage
                )
            }
        )
        null -> Unit
    }

    SftpTransferDialogs(
        transfers = transfers,
        downloadState = downloadState,
        uploadState = uploadState,
        onCancelTransfer = { viewModel.cancelTransfer(it.id) },
        onBackgroundTransfer = {
            if (it.type == TransferType.DOWNLOAD) {
                viewModel.backgroundDownload(it.id)
            } else {
                viewModel.backgroundUpload(it.id)
            }
        },
        onCancelDownload = viewModel::cancelDownload,
        onCancelUpload = viewModel::cancelUpload
    )

    AppNavDisplay(
        backStack = folders,
        // The display only calls this above the root folder; the host handles back at the root.
        onBack = { viewModel.goBack() },
        entryProvider = entryProvider<NavKey> {
            entry<SftpFolder> { entry ->
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0.dp),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        SftpMinimizedTransferBanner(
                            transfers = transfers,
                            onRestore = {
                                if (it.type == TransferType.DOWNLOAD) {
                                    viewModel.restoreDownload(it.id)
                                } else {
                                    viewModel.restoreUpload(it.id)
                                }
                            },
                            onCancel = { viewModel.cancelTransfer(it.id) }
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .hazeSource(hazeState)
                        ) {
                            SftpBreadcrumbs(
                                currentPath = entry.path,
                                trailPath = trailPath,
                                isTabActive = isTabActive && entry.path == currentPath,
                                onSegmentClick = viewModel::openFolder
                            )
                            SftpDirectory(
                                state = uiState,
                                folder = entry.path,
                                onOpenFolder = viewModel::openFolder,
                                onOpenFile = { file ->
                                    viewModel.downloadAndOpenFile(
                                        file = file,
                                        cacheDir = context.cacheDir,
                                        onFileReady = { staged -> onOpenFile(file, staged) },
                                        onError = onOpenFileError
                                    )
                                },
                                onDownload = { file ->
                                    viewModel.downloadAndOpenFile(
                                        file = file,
                                        cacheDir = context.cacheDir,
                                        onFileReady = { staged ->
                                            scope.launch {
                                                val result = runCatching {
                                                    withContext(Dispatchers.IO) { saveToDownloads(context, staged) }
                                                }
                                                showMessage(
                                                    if (result.isSuccess) {
                                                        "Saved ${file.name} to Downloads"
                                                    } else {
                                                        "Failed to save ${file.name}"
                                                    }
                                                )
                                            }
                                        },
                                        onError = ::showMessage
                                    )
                                },
                                onRename = { dialog = SftpDialog.Rename(it) },
                                onDelete = { dialog = SftpDialog.Delete(it) },
                                searchQuery = searchQuery,
                                onClearSearch = { searchQuery = "" },
                                treeMode = browser.treeMode,
                                tree = tree,
                                onToggleFolder = viewModel::toggleExpanded,
                                initialScroll = browser.scrollOf(entry.path),
                                onScrollChanged = viewModel::saveScroll,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        val hasBottomBanner = innerPadding.calculateBottomPadding() > 0.dp
                        SftpBottomActionBar(
                            isRefreshing = isRefreshing,
                            onRefresh = viewModel::refresh,
                            isSearchActive = isSearchActive,
                            searchQuery = searchQuery,
                            treeMode = browser.treeMode,
                            onSearchClick = { isSearchActive = true },
                            onCloseSearch = {
                                isSearchActive = false
                                searchQuery = ""
                            },
                            onSearchQueryChange = { searchQuery = it },
                            onToggleTree = { viewModel.setTreeMode(!browser.treeMode) },
                            onUploadClick = { uploadPicker.launch("*/*") },
                            hazeState = hazeState,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .then(
                                    if (hasBottomBanner) {
                                        Modifier
                                    } else {
                                        Modifier.navigationBarsPadding()
                                    }
                                )
                                .padding(bottom = 16.dp),
                            trailing = tabsButton
                        )
                    }
                }
            }
        }
    )
}

@Suppress("TooGenericExceptionCaught", "SwallowedException")
private fun stagePickedFile(context: Context, uri: Uri): File? {
    val resolver = context.contentResolver
    val displayName = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "upload_${System.currentTimeMillis()}"
    val stageDir = File(context.cacheDir, "upload_stage").apply { mkdirs() }
    val target = File(stageDir, displayName)
    return try {
        val input = resolver.openInputStream(uri) ?: return null
        input.use { ins -> target.outputStream().use { out -> ins.copyTo(out) } }
        target
    } catch (e: Exception) {
        null
    }
}
