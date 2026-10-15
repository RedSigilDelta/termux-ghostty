package com.mrndtvndv.term.ui.sftp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransfer
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransferManager
import com.mrndtvndv.term.ui.sftp.transfer.TransferType
import com.mrndtvndv.term.workspace.SftpBrowserKey
import com.mrndtvndv.term.workspace.SftpBrowserState
import com.mrndtvndv.term.workspace.SftpBrowserStore
import com.mrndtvndv.term.workspace.SftpScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** The listing of [path]. Every state names its folder, so a listing is never shown as another folder's. */
sealed interface SftpUiState {
    val path: String

    data class Loading(override val path: String) : SftpUiState
    data class Success(
        override val path: String,
        val files: List<SftpFile>,
        val gitStatuses: Map<String, String> = emptyMap()
    ) : SftpUiState
    data class Error(override val path: String, val message: String) : SftpUiState
}

/** [expanded] is the browser's durable choice; [children] and [loading] are this connection's listings. */
data class SftpTreeState(
    val expanded: Set<String> = emptySet(),
    val children: Map<String, List<SftpFile>> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val loaded: Set<String> = emptySet(),
)

private data class SftpTreeListings(
    val children: Map<String, List<SftpFile>> = emptyMap(),
    val loading: Set<String> = emptySet(),
    val loaded: Set<String> = emptySet(),
)

data class SftpDownloadState(
    val fileName: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
)

data class SftpUploadState(
    val fileName: String,
    val bytesUploaded: Long,
    val totalBytes: Long,
)

private fun List<SftpFile>.sortedForDisplay() =
    sortedWith(compareBy<SftpFile> { !it.isDirectory }.thenBy { it.name.lowercase() })

/**
 * Controller of one SFTP browser, for an ordinary tab or a Herdr sheet alike. Navigation lives in [browsers] under
 * [key] and is written there before anything loads; this view model holds only the connection's listings and jobs.
 * [initialDir] seeds a browser that does not exist yet. An existing browser resumes where it was.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class SftpViewModel(
    private val client: SftpClient,
    private val execCommand: suspend (String) -> String,
    private val transferManager: SftpTransferManager?,
    private val ownerKey: String,
    private val browsers: SftpBrowserStore,
    private val key: SftpBrowserKey,
    initialDir: String,
) : ViewModel() {
    init {
        browsers.openBrowser(key, initialDir)
    }

    val browser: StateFlow<SftpBrowserState?> = browsers.observeBrowser(key)
        .stateIn(viewModelScope, SharingStarted.Eagerly, browsers.browser(key))

    private val _uiState = MutableStateFlow<SftpUiState>(SftpUiState.Loading(browsers.browser(key)?.dir ?: initialDir))
    val uiState = _uiState.asStateFlow()

    private val _treeListings = MutableStateFlow(SftpTreeListings())
    val tree: StateFlow<SftpTreeState> = combine(browser, _treeListings) { state, listings ->
        SftpTreeState(state?.expanded.orEmpty().toSet(), listings.children, listings.loading, listings.loaded)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SftpTreeState(browser.value?.expanded.orEmpty().toSet()))

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _activeDownloadId = MutableStateFlow<String?>(null)
    private val _activeUploadId = MutableStateFlow<String?>(null)

    private val _fallbackTransfers = MutableStateFlow<List<SftpTransfer>>(emptyList())
    val transfers: StateFlow<List<SftpTransfer>> = if (transferManager != null) {
        transferManager.transfers
            .map { list -> list.filter { it.ownerKey == ownerKey } }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = transferManager.transfers.value.filter { it.ownerKey == ownerKey }
            )
    } else {
        _fallbackTransfers.asStateFlow()
    }

    private val activeDownload: StateFlow<SftpTransfer?> = transfers
        .map { list -> list.firstOrNull { it.type == TransferType.DOWNLOAD && it.isRunning } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    private val activeUpload: StateFlow<SftpTransfer?> = transfers
        .map { list -> list.firstOrNull { it.type == TransferType.UPLOAD && it.isRunning } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )

    private val _fallbackDownloadState = MutableStateFlow<SftpDownloadState?>(null)
    val downloadState: StateFlow<SftpDownloadState?> = if (transferManager != null) {
        combine(transfers, _activeDownloadId) { list, activeId ->
            if (activeId == null) return@combine null
            val active = list.firstOrNull {
                it.id == activeId &&
                    it.type == TransferType.DOWNLOAD &&
                    it.isRunning &&
                    !it.isMinimized
            } ?: return@combine null

            SftpDownloadState(
                fileName = active.fileName,
                bytesDownloaded = active.transferredBytes,
                totalBytes = active.totalBytes,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )
    } else {
        _fallbackDownloadState.asStateFlow()
    }

    private val _fallbackUploadState = MutableStateFlow<SftpUploadState?>(null)
    val uploadState: StateFlow<SftpUploadState?> = if (transferManager != null) {
        combine(transfers, _activeUploadId) { list, activeId ->
            if (activeId == null) return@combine null
            val active = list.firstOrNull {
                it.id == activeId &&
                    it.type == TransferType.UPLOAD &&
                    it.isRunning &&
                    !it.isMinimized
            } ?: return@combine null

            SftpUploadState(
                fileName = active.fileName,
                bytesUploaded = active.transferredBytes,
                totalBytes = active.totalBytes,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null
        )
    } else {
        _fallbackUploadState.asStateFlow()
    }

    private var downloadJob: Job? = null
    private var uploadJob: Job? = null

    private var listJob: Job? = null
    private var listRequest = 0L

    /** Latest tree request per folder. A completion whose request is no longer here is dropped. */
    private val treeRequests = mutableMapOf<String, Long>()
    private val treeJobs = mutableMapOf<String, Job>()
    private var nextTreeRequest = 0L

    /** Folders whose children were listed since the current listing started. */
    private val freshChildren = mutableSetOf<String>()

    private val _trailPath = MutableStateFlow(browsers.browser(key)?.dir.orEmpty())
    val trailPath = _trailPath.asStateFlow()

    init {
        browsers.browser(key)?.let { load(it.dir) }
    }

    private fun isPathPrefix(prefix: String, fullPath: String): Boolean {
        if (prefix == fullPath || prefix == "/" || prefix.isEmpty()) return true
        val formattedPrefix = if (prefix.endsWith("/")) prefix else "$prefix/"
        return fullPath.startsWith(formattedPrefix)
    }

    fun openFolder(path: String) {
        navigate { it.open(path) }
    }

    /** Goes one folder up the stack. False at the bottom, so the host can close the browser instead. */
    fun goBack(): Boolean {
        if (browsers.browser(key)?.goBack() == null) return false
        navigate { it.goBack() ?: it }
        return true
    }

    fun refresh() {
        browsers.browser(key)?.let { load(it.dir) }
    }

    fun setTreeMode(enabled: Boolean) {
        browsers.updateBrowser(key) { it.copy(treeMode = enabled) }
        loadVisibleTree()
    }

    fun saveScroll(path: String, position: SftpScroll) {
        browsers.updateBrowser(key) { it.withScroll(path, position) }
    }

    private fun navigate(transform: (SftpBrowserState) -> SftpBrowserState) {
        browsers.updateBrowser(key, transform)
        val dir = browsers.browser(key)?.dir ?: return
        if (!isPathPrefix(dir, _trailPath.value)) _trailPath.value = dir
        if (dir != _uiState.value.path) load(dir)
    }

    /** Lists [path], replacing any earlier listing. A superseded request never touches state. */
    @Suppress("TooGenericExceptionCaught")
    private fun load(path: String) {
        listJob?.cancel()
        val request = ++listRequest
        val current = _uiState.value
        val refreshing = current is SftpUiState.Success && current.path == path
        resetTree(keepChildren = current.path == path)
        if (!refreshing) _uiState.value = SftpUiState.Loading(path)
        _isRefreshing.value = refreshing
        listJob = viewModelScope.launch {
            try {
                val files = client.listFiles(path).sortedForDisplay()
                val gitStatuses = gitStatuses(path)
                if (request != listRequest) return@launch
                _uiState.value = SftpUiState.Success(path, files, gitStatuses)
                loadVisibleTree()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == listRequest) {
                    _uiState.value = SftpUiState.Error(path, e.localizedMessage ?: "Failed to load directory")
                }
            } finally {
                if (request == listRequest) _isRefreshing.value = false
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun gitStatuses(path: String): Map<String, String> =
        try {
            parseGitStatuses(execCommand("git -C \"$path\" status --porcelain --ignored=no ."))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not a git repo, or git is not installed.
            emptyMap()
        }

    private fun parseGitStatuses(output: String): Map<String, String> {
        val gitStatuses = mutableMapOf<String, String>()
        output.lines().forEach { line ->
            if (line.length >= 4) { // Status (2 chars), space, then filename
                val file = line.substring(3).removeSurrounding("\"")
                val parts = file.split("/")
                gitStatuses[file] = line.substring(0, 2)
                for (depth in 1 until parts.size) {
                    gitStatuses[parts.take(depth).joinToString("/")] = "M"
                }
            }
        }
        return gitStatuses
    }

    fun toggleExpanded(dir: SftpFile) {
        val state = browsers.browser(key) ?: return
        if (dir.path in state.expanded) {
            browsers.updateBrowser(key) { it.collapse(dir.path) }
            cancelTree(dir.path)
            return
        }
        browsers.updateBrowser(key) { it.expand(dir.path) }
        if (dir.path in freshChildren || dir.path in treeRequests) {
            loadVisibleTree()
        } else {
            fetchChildren(dir.path, autoExpand = true)
        }
    }

    /** Drops every tree request. [keepChildren] keeps the shown children while a refresh lists them again. */
    private fun resetTree(keepChildren: Boolean) {
        treeJobs.values.forEach { it.cancel() }
        treeJobs.clear()
        treeRequests.clear()
        freshChildren.clear()
        _treeListings.update { SftpTreeListings(children = if (keepChildren) it.children else emptyMap()) }
    }

    /** Cancels the requests of [path] and of every folder below it. */
    private fun cancelTree(path: String) {
        val cancelled = treeRequests.keys.filter { isPathPrefix(path, it) }
        cancelled.forEach { folder ->
            treeRequests.remove(folder)
            treeJobs.remove(folder)?.cancel()
        }
        _treeListings.update { it.copy(loading = it.loading - cancelled.toSet()) }
    }

    /** Lists every expanded folder the tree shows that has not been listed since the current listing. */
    private fun loadVisibleTree() {
        val state = browsers.browser(key) ?: return
        val listing = _uiState.value as? SftpUiState.Success ?: return
        if (!state.treeMode || listing.path != state.dir) return
        val expanded = state.expanded.toSet()
        val children = _treeListings.value.children
        val pending = ArrayDeque(listing.files)
        while (pending.isNotEmpty()) {
            val folder = pending.removeFirst()
            if (!folder.isDirectory || folder.path !in expanded) continue
            when {
                folder.path in freshChildren -> children[folder.path]?.let(pending::addAll)
                folder.path !in treeRequests -> fetchChildren(folder.path, autoExpand = false)
            }
        }
    }

    /**
     * Lists the children of [path]. [autoExpand] continues an explicit expand down a chain of single folders,
     * unless the user collapsed that folder.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun fetchChildren(path: String, autoExpand: Boolean) {
        val request = ++nextTreeRequest
        treeRequests[path] = request
        treeJobs.remove(path)?.cancel()
        _treeListings.update { it.copy(loading = it.loading + path) }
        val job = viewModelScope.launch {
            val children = try {
                client.listFiles(path).sortedForDisplay()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The folder stays expanded; it shows no children until it is listed again.
                null
            }
            if (treeRequests[path] != request) return@launch
            treeRequests.remove(path)
            treeJobs.remove(path)
            freshChildren += path
            _treeListings.update { listings ->
                val listed = if (children == null) listings.children - path else listings.children + (path to children)
                listings.copy(children = listed, loading = listings.loading - path, loaded = listings.loaded + path)
            }
            if (autoExpand && children != null) autoExpandOnlyChild(path, children)
            loadVisibleTree()
        }
        if (treeRequests[path] == request) treeJobs[path] = job
    }

    private fun autoExpandOnlyChild(path: String, children: List<SftpFile>) {
        val onlyDir = children.singleOrNull()?.takeIf { it.isDirectory } ?: return
        val state = browsers.browser(key) ?: return
        if (path !in state.expanded || onlyDir.path in state.collapsed) return
        browsers.updateBrowser(key) { it.autoExpand(onlyDir.path) }
        if (onlyDir.path !in freshChildren && onlyDir.path !in treeRequests) {
            fetchChildren(onlyDir.path, autoExpand = true)
        }
    }

    @Suppress("SwallowedException")
    fun downloadAndOpenFile(
        file: SftpFile,
        cacheDir: File,
        onFileReady: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val manager = transferManager
        if (manager != null) {
            var downloadId: String? = null
            downloadId = manager.startDownload(
                sftpClient = client,
                file = file,
                cacheDir = cacheDir,
                ownerKey = ownerKey,
                onFileReady = { localFile ->
                    if (_activeDownloadId.value == downloadId) {
                        _activeDownloadId.value = null
                    }
                    onFileReady(localFile)
                },
                onError = { error ->
                    if (_activeDownloadId.value == downloadId) {
                        _activeDownloadId.value = null
                    }
                    onError(error)
                }
            )
            _activeDownloadId.value = downloadId
            return
        }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _fallbackDownloadState.value = SftpDownloadState(file.name, 0L, file.size)
            try {
                val tempDir = File(cacheDir, "sftp_cache").apply { mkdirs() }
                val localFile = File(tempDir, file.name)
                client.downloadFile(file.path, localFile) { progress ->
                    if (isActive) {
                        _fallbackDownloadState.value = SftpDownloadState(file.name, progress, file.size)
                    }
                }
                if (isActive) {
                    _fallbackDownloadState.value = null
                    onFileReady(localFile)
                }
            } catch (e: CancellationException) {
                _fallbackDownloadState.value = null
            } catch (e: Exception) {
                _fallbackDownloadState.value = null
                onError(e.localizedMessage ?: "Failed to download file")
            }
        }
    }

    fun cancelDownload() {
        val manager = transferManager
        if (manager != null) {
            val id = _activeDownloadId.value ?: activeDownload.value?.id
            if (id != null) {
                manager.cancel(id)
            }
            _activeDownloadId.value = null
            return
        }
        downloadJob?.cancel()
        downloadJob = null
        _fallbackDownloadState.value = null
    }

    @Suppress("SwallowedException")
    fun uploadFile(
        source: File,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val fileName = source.name
        val dir = currentDirectory.trimEnd('/')
        val remotePath = if (dir == "/") "/$fileName" else "$dir/$fileName"

        val manager = transferManager
        if (manager != null) {
            var uploadId: String? = null
            uploadId = manager.startUpload(
                sftpClient = client,
                source = source,
                remotePath = remotePath,
                ownerKey = ownerKey,
                onSuccess = {
                    if (_activeUploadId.value == uploadId) {
                        _activeUploadId.value = null
                    }
                    onSuccess()
                    refresh()
                },
                onError = { error ->
                    if (_activeUploadId.value == uploadId) {
                        _activeUploadId.value = null
                    }
                    onError(error)
                }
            )
            _activeUploadId.value = uploadId
            return
        }

        uploadJob?.cancel()
        uploadJob = viewModelScope.launch {
            _fallbackUploadState.value = SftpUploadState(fileName, 0L, source.length())
            try {
                client.uploadFile(source, remotePath) { progress ->
                    if (isActive) {
                        _fallbackUploadState.value = SftpUploadState(fileName, progress, source.length())
                    }
                }
                if (isActive) {
                    _fallbackUploadState.value = null
                    onSuccess()
                    refresh()
                }
            } catch (e: CancellationException) {
                _fallbackUploadState.value = null
            } catch (e: Exception) {
                _fallbackUploadState.value = null
                onError(e.localizedMessage ?: "Failed to upload file")
            }
        }
    }

    fun cancelUpload() {
        val manager = transferManager
        if (manager != null) {
            val id = _activeUploadId.value ?: activeUpload.value?.id
            if (id != null) {
                manager.cancel(id)
            }
            _activeUploadId.value = null
            return
        }
        uploadJob?.cancel()
        uploadJob = null
        _fallbackUploadState.value = null
    }

    fun backgroundDownload(id: String? = null) {
        val targetId = id ?: _activeDownloadId.value ?: activeDownload.value?.id ?: return
        transferManager?.minimize(targetId)
    }

    fun restoreDownload(id: String? = null) {
        val targetId = id ?: _activeDownloadId.value ?: activeDownload.value?.id ?: return
        _activeDownloadId.value = targetId
        transferManager?.restore(targetId)
    }

    fun backgroundUpload(id: String? = null) {
        val targetId = id ?: _activeUploadId.value ?: activeUpload.value?.id ?: return
        transferManager?.minimize(targetId)
    }

    fun restoreUpload(id: String? = null) {
        val targetId = id ?: _activeUploadId.value ?: activeUpload.value?.id ?: return
        _activeUploadId.value = targetId
        transferManager?.restore(targetId)
    }

    fun cancelTransfer(id: String) {
        transferManager?.cancel(id)
        if (_activeDownloadId.value == id) _activeDownloadId.value = null
        if (_activeUploadId.value == id) _activeUploadId.value = null
    }

    @Suppress("TooGenericExceptionCaught")
    fun deleteFile(file: SftpFile, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                client.deleteFile(file.path)
                onSuccess()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Failed to delete ${file.name}")
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun renameFile(file: SftpFile, newName: String, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {}) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed.contains('/')) {
            onError("Invalid name")
            return
        }
        if (trimmed == file.name) return

        val parent = file.path.substringBeforeLast('/', "")
        val newPath = if (parent.isEmpty()) "/$trimmed" else "$parent/$trimmed"
        viewModelScope.launch {
            try {
                client.renameFile(file.path, newPath)
                onSuccess()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.localizedMessage ?: "Failed to rename ${file.name}")
            }
        }
    }

    private val currentDirectory: String
        get() = browsers.browser(key)?.dir?.takeIf { it.isNotBlank() } ?: "/"
}
