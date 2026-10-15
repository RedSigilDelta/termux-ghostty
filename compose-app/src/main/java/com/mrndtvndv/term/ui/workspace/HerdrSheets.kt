@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mrndtvndv.term.ui.workspace

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.navigation.ReviewRoute
import com.mrndtvndv.term.navigation.SftpFolder
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.ui.review.ReviewScreenRoute
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpScreenRoute
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.sftp.openDownloadedFile
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransferManager
import com.mrndtvndv.term.workspace.herdrSheetTabId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The sheets the Herdr key bar opens. [key] names the sheet's view model store. */
enum class HerdrSheetKind(val key: String) {
    Git("git"),
    Sftp("sftp"),
}

/**
 * A sheet's starting point, captured when it opens. The sheet reads nothing live, so a later focus change or a
 * download that finishes afterwards stays with the collection it was opened from. SFTP uses [cwd] only on its
 * first open for a collection; subsequent opens resume navigation. [openId] resets Git navigation per open.
 */
data class HerdrSheetCapture(
    val kind: HerdrSheetKind,
    val scopeKey: String,
    val cwd: String,
    val openId: String,
)

/** Navigation lives alongside the SFTP view model until this server connection is cleared. */
class HerdrSftpNavigation(initialCwd: String) : ViewModel() {
    val backStack = NavBackStack<NavKey>(SftpFolder(initialCwd))
    var treeMode: Boolean = false
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    fun scrollPosition(path: String): Pair<Int, Int> = scrollPositions[path] ?: (0 to 0)

    fun saveScrollPosition(path: String, index: Int, offset: Int) {
        scrollPositions[path] = index to offset
    }
}

/**
 * A full-screen sheet for Git or SFTP at the captured directory. Back pops the sheet's inner stack first, then
 * dismisses it. [onViewerOpened] receives the collection a previewed file was opened in, not the current one.
 */
@Suppress("LongParameterList")
@Composable
fun HerdrSheetHost(
    capture: HerdrSheetCapture,
    serverId: String,
    server: Server,
    appViewModel: AppViewModel,
    onViewerOpened: (scopeKey: String, tabId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val stack = when (capture.kind) {
        HerdrSheetKind.Sftp -> {
            val store = appViewModel.connectionStore(serverId, herdrSheetTabId(capture.kind.key, capture.scopeKey))
            val navigation = viewModel(viewModelStoreOwner = store) { HerdrSftpNavigation(capture.cwd) }
            navigation.backStack
        }
        HerdrSheetKind.Git -> remember(capture.openId) { NavBackStack<NavKey>(ReviewRoute.Changes) }
    }
    val dismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val onBack: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else dismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        KeepSheetAboveIme()
        // NavDisplay inside the sheet handles back while it has more than one entry; this covers the root.
        BackHandler(enabled = stack.size <= 1, onBack = dismiss)
        Box(modifier = Modifier.fillMaxHeight()) {
            when (capture.kind) {
                HerdrSheetKind.Git -> HerdrGitContent(
                    serverId = serverId,
                    server = server,
                    capture = capture,
                    appViewModel = appViewModel,
                    stack = stack,
                    onBack = onBack,
                )
                HerdrSheetKind.Sftp -> HerdrSftpContent(
                    serverId = serverId,
                    server = server,
                    capture = capture,
                    appViewModel = appViewModel,
                    stack = stack,
                    onBack = onBack,
                    onViewerOpened = onViewerOpened,
                    dismiss = dismiss,
                )
            }
        }
    }
}

@Composable
private fun HerdrGitContent(
    serverId: String,
    server: Server,
    capture: HerdrSheetCapture,
    appViewModel: AppViewModel,
    stack: NavBackStack<NavKey>,
    onBack: () -> Unit,
) {
    val store = appViewModel.connectionStore(serverId, herdrSheetTabId(capture.kind.key, capture.scopeKey))
    val reviewViewModel = viewModel(viewModelStoreOwner = store) {
        val sshSession = checkNotNull(server.sshSession)
        ReviewViewModel(
            execCommand = { command -> sshSession.execCommand(command) },
            workspaceDir = MutableStateFlow(capture.cwd),
        )
    }
    ReviewScreenRoute(
        viewModel = reviewViewModel,
        backStack = stack,
        onOpenDiff = { route -> stack.add(route) },
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
    )
}

@Suppress("LongParameterList")
@Composable
private fun HerdrSftpContent(
    serverId: String,
    server: Server,
    capture: HerdrSheetCapture,
    appViewModel: AppViewModel,
    stack: NavBackStack<NavKey>,
    onBack: () -> Unit,
    onViewerOpened: (scopeKey: String, tabId: String) -> Unit,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = appViewModel.connectionStore(serverId, herdrSheetTabId(capture.kind.key, capture.scopeKey))
    val navigation = viewModel(viewModelStoreOwner = store) { HerdrSftpNavigation(capture.cwd) }
    val currentPath = (stack.last() as SftpFolder).path
    val (scrollIndex, scrollOffset) = navigation.scrollPosition(currentPath)
    DisposableEffect(navigation) {
        Log.i("SftpRetention", "sheet opened scope=${capture.scopeKey} browser=${System.identityHashCode(navigation)}")
        onDispose {
            Log.i("SftpRetention", "sheet disposed scope=${capture.scopeKey} path=${navigation.backStack.last()}")
        }
    }
    LaunchedEffect(navigation) {
        snapshotFlow { navigation.backStack.toList() }.collect { folders ->
            Log.i("SftpRetention", "navigation scope=${capture.scopeKey} folders=$folders")
        }
    }
    val sftpViewModel = viewModel(viewModelStoreOwner = store) {
        val sshSession = checkNotNull(server.sshSession)
        SftpViewModel(
            client = checkNotNull(server.sftpClient),
            execCommand = { command -> sshSession.execCommand(command) },
            transferManager = SftpTransferManager.current,
            ownerKey = server.config.id,
            // The sheet's navigation stays local. Its tab directory is never written from here.
            onDirectoryChanged = {},
        )
    }
    SftpScreenRoute(
        viewModel = sftpViewModel,
        backStack = stack,
        onOpenFolder = { path -> openFolder(stack, path) },
        onBack = onBack,
        isTabActive = true,
        onOpenFile = { remote, local ->
            openDownloadedFile(
                context = context,
                file = local,
                onViewText = {
                    // The file belongs to the collection captured at open, even if focus has moved since.
                    val tabId = appViewModel.openHerdrViewer(serverId, capture.scopeKey, remote.path)
                    if (tabId == null) {
                        appViewModel.notificationState.post("Couldn't open file", remote.path)
                    } else {
                        onViewerOpened(capture.scopeKey, tabId)
                        dismiss()
                    }
                },
                onError = { title, message -> appViewModel.notificationState.post(title, message) },
            )
        },
        onOpenFileError = { message -> appViewModel.notificationState.post("SFTP Error", message) },
        tabsButton = null,
        initialScrollIndex = scrollIndex,
        initialScrollOffset = scrollOffset,
        onScrollChanged = { index, offset -> navigation.saveScrollPosition(currentPath, index, offset) },
        initialTreeMode = navigation.treeMode,
        onTreeModeChanged = { navigation.treeMode = it },
    )
}

/** Same rule as the workspace navigator: pop back to an existing folder entry, or push a new one. */
private fun openFolder(stack: NavBackStack<NavKey>, path: String) {
    val index = stack.indexOfLast { (it as? SftpFolder)?.path == path }
    if (index < 0) {
        stack.add(SftpFolder(path))
    } else {
        while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
    }
}
