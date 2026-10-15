@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mrndtvndv.term.ui.workspace

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.navigation.ReviewRoute
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.ui.review.ReviewScreenRoute
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpScreenRoute
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.sftp.openDownloadedFile
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransferManager
import com.mrndtvndv.term.workspace.SftpBrowserKey
import com.mrndtvndv.term.workspace.SftpBrowserStore
import com.mrndtvndv.term.workspace.herdrFileScopeFromKey
import com.mrndtvndv.term.workspace.herdrSheetTabId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The sheets the Herdr key bar opens. [key] names the sheet's view model store. */
enum class HerdrSheetKind(val key: String) {
    Git("git"),
    Sftp("sftp"),
}

/**
 * A sheet's starting point, captured when it opens. The sheet reads nothing live, so a later focus change or a
 * download that finishes afterwards stays with the collection it was opened from. SFTP uses [cwd] only when the
 * collection has no browser yet; otherwise it resumes the saved one. [openId] resets Git navigation per open.
 */
data class HerdrSheetCapture(
    val kind: HerdrSheetKind,
    val scopeKey: String,
    val cwd: String,
    val openId: String,
)

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
    browsers: SftpBrowserStore,
    onViewerOpened: (scopeKey: String, tabId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // A download that completes after this sheet closed must not dismiss a newer sheet.
    val shown = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) {
        onDispose { shown.set(false) }
    }
    val dismiss: () -> Unit = {
        if (shown.get()) {
            scope.launch { sheetState.hide() }.invokeOnCompletion { if (shown.get()) onDismiss() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        KeepSheetAboveIme()
        Box(modifier = Modifier.fillMaxHeight()) {
            when (capture.kind) {
                HerdrSheetKind.Git -> HerdrGitContent(
                    serverId = serverId,
                    server = server,
                    capture = capture,
                    appViewModel = appViewModel,
                    dismiss = dismiss,
                )
                HerdrSheetKind.Sftp -> HerdrSftpContent(
                    serverId = serverId,
                    server = server,
                    capture = capture,
                    appViewModel = appViewModel,
                    browsers = browsers,
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
    dismiss: () -> Unit,
) {
    val stack = remember(capture.openId) { NavBackStack<NavKey>(ReviewRoute.Changes) }
    val onBack: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else dismiss()
    }
    // NavDisplay inside the sheet handles back while it has more than one entry; this covers the root.
    BackHandler(enabled = stack.size <= 1, onBack = dismiss)
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

/**
 * The browser of the captured remote workspace. It starts at the captured cwd only when the workspace has no browser
 * yet; otherwise it resumes. Navigation is durable, so it outlives the sheet, the connection and the process.
 */
@Suppress("LongParameterList")
@Composable
private fun HerdrSftpContent(
    serverId: String,
    server: Server,
    capture: HerdrSheetCapture,
    appViewModel: AppViewModel,
    browsers: SftpBrowserStore,
    onViewerOpened: (scopeKey: String, tabId: String) -> Unit,
    dismiss: () -> Unit,
) {
    val context = LocalContext.current
    val fileScope = herdrFileScopeFromKey(capture.scopeKey)?.takeIf { it.serverId == serverId }
    if (fileScope == null) {
        LaunchedEffect(capture.openId) { dismiss() }
        return
    }
    val store = appViewModel.connectionStore(serverId, herdrSheetTabId(capture.kind.key, capture.scopeKey))
    val sftpViewModel = viewModel(viewModelStoreOwner = store) {
        val sshSession = checkNotNull(server.sshSession)
        SftpViewModel(
            client = checkNotNull(server.sftpClient),
            execCommand = { command -> sshSession.execCommand(command) },
            transferManager = SftpTransferManager.current,
            ownerKey = server.config.id,
            browsers = browsers,
            key = SftpBrowserKey.HerdrWorkspace(fileScope),
            initialDir = capture.cwd,
        )
    }
    val browser by sftpViewModel.browser.collectAsStateWithLifecycle()
    // The browser's NavDisplay handles back above its root folder; this covers the root.
    BackHandler(enabled = browser?.back.isNullOrEmpty(), onBack = dismiss)
    SftpScreenRoute(
        viewModel = sftpViewModel,
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
    )
}
