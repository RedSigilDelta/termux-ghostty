@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mrndtvndv.term.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * The full-screen modal for Git or SFTP. One modal serves Opening, Ready and Error, so a finished capture never
 * dismisses or re-animates it. Ready shows the sheet at the captured directory; back pops its inner stack first,
 * then dismisses. [onViewerOpened] receives the collection a previewed file was opened in, not the current one.
 * The modal is keyed by the server and [HerdrSheetState.presentationId], so a sheet opened after another closed is
 * a new modal even within one frame. [onBeginDismiss] invalidates pending work when a dismissal starts and says
 * whether to hide; [onDismiss] removes the sheet once the hide ended. Every callback is bound to the request of the
 * composition that created it, so a late download or hide callback of an older request cannot start or finish a
 * newer one.
 */
@Suppress("LongParameterList")
@Composable
fun HerdrSheetHost(
    state: HerdrSheetState,
    serverId: String,
    server: Server,
    appViewModel: AppViewModel,
    browsers: SftpBrowserStore,
    onViewerOpened: (scopeKey: String, tabId: String) -> Unit,
    onRetry: (requestId: Long) -> Unit,
    onBeginDismiss: (requestId: Long) -> Boolean,
    onDismiss: (requestId: Long) -> Unit,
    savedState: SaveableStateHolder,
) {
    // The request counter restarts per server, so presentationId alone could reuse another server's modal.
    key(serverId, state.presentationId) {
        HerdrSheetModal(
            state = state,
            serverId = serverId,
            server = server,
            appViewModel = appViewModel,
            browsers = browsers,
            onViewerOpened = onViewerOpened,
            onRetry = onRetry,
            onBeginDismiss = onBeginDismiss,
            onDismiss = onDismiss,
            savedState = savedState,
        )
    }
}

@Suppress("LongParameterList")
@Composable
private fun HerdrSheetModal(
    state: HerdrSheetState,
    serverId: String,
    server: Server,
    appViewModel: AppViewModel,
    browsers: SftpBrowserStore,
    onViewerOpened: (scopeKey: String, tabId: String) -> Unit,
    onRetry: (requestId: Long) -> Unit,
    onBeginDismiss: (requestId: Long) -> Boolean,
    onDismiss: (requestId: Long) -> Unit,
    savedState: SaveableStateHolder,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    // Set false when this modal leaves composition, so its late callbacks do nothing.
    val shown = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) {
        onDispose { shown.set(false) }
    }
    val requestId = state.requestId
    val dismiss: () -> Unit = {
        // The owner refuses a stale request or a second hide, so only the current request starts the animation.
        if (shown.get() && onBeginDismiss(requestId)) {
            scope.launch { sheetState.hide() }.invokeOnCompletion { cause ->
                finishHideIfCompleted(cause, shown.get()) { onDismiss(requestId) }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (shown.get()) onDismiss(requestId) },
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        KeepSheetAboveIme()
        Box(modifier = Modifier.fillMaxHeight()) {
            when (state) {
                is HerdrSheetState.Opening -> HerdrSheetMessage(
                    kind = state.kind,
                    message = null,
                    onRetry = null,
                    dismiss = dismiss,
                )
                is HerdrSheetState.Error -> HerdrSheetMessage(
                    kind = state.kind,
                    message = state.message,
                    onRetry = { if (!state.exiting) onRetry(requestId) },
                    retryEnabled = !state.exiting,
                    dismiss = dismiss,
                )
                is HerdrSheetState.Ready -> HerdrReadyContent(
                    capture = state.capture,
                    serverId = serverId,
                    server = server,
                    appViewModel = appViewModel,
                    browsers = browsers,
                    onViewerOpened = onViewerOpened,
                    dismiss = dismiss,
                    savedState = savedState,
                )
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun HerdrReadyContent(
    capture: HerdrSheetCapture,
    serverId: String,
    server: Server,
    appViewModel: AppViewModel,
    browsers: SftpBrowserStore,
    onViewerOpened: (scopeKey: String, tabId: String) -> Unit,
    dismiss: () -> Unit,
    savedState: SaveableStateHolder,
) {
    savedState.SaveableStateProvider(herdrSheetTabId(capture.kind.key, capture.scopeKey)) {
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

/** Opening (spinner) and Error (message with Retry) share one layout, labelled with the action being opened. */
@Composable
private fun HerdrSheetMessage(
    kind: HerdrSheetKind,
    message: String?,
    onRetry: (() -> Unit)?,
    dismiss: () -> Unit,
    retryEnabled: Boolean = true,
) {
    BackHandler(onBack = dismiss)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val action = if (kind == HerdrSheetKind.Git) "Git" else "Files"
        if (message == null) {
            CircularProgressIndicator()
            Text(
                text = "Opening $action…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            Text(text = "Couldn't open $action", style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            onRetry?.let {
                Button(onClick = it, enabled = retryEnabled, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Retry")
                }
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
