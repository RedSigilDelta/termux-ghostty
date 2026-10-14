package com.termux.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.app.settings.ConfigErrorsDialogHost
import com.termux.app.terminal.SessionProcessPoller
import com.termux.app.terminal.TabThumbnails
import com.termux.app.terminal.TabsUiState
import com.termux.app.terminal.io.KeyBar
import com.termux.app.terminal.io.TabsButton
import com.termux.app.terminal.io.ModifierKeys
import com.termux.app.ui.TermuxTheme
import com.termux.app.workspace.Tab
import com.termux.app.workspace.ui.FilesTab
import com.termux.app.workspace.ui.TabIcon
import com.termux.app.workspace.ui.TabKind
import com.termux.app.workspace.ui.TabsGrid
import com.termux.app.workspace.ui.TabsGridActions
import com.termux.app.workspace.ui.ViewerTab
import com.termux.shared.termux.TermuxConstants
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalComposeView
import com.termux.terminal.compose.TerminalThumbnail
import com.termux.terminal.compose.session.TerminalSessionBackend

private val KeyBarGap = 8.dp

class TermuxScreenState(
    marginHorizontal: Int,
    marginVertical: Int,
) {
    var tabsGridVisible by mutableStateOf(false)
    var marginHorizontal by mutableIntStateOf(marginHorizontal)
    var marginVertical by mutableIntStateOf(marginVertical)
    var imeVisible by mutableStateOf(false)
    var fontStamp by mutableLongStateOf(0L)
}

interface TermuxScreenActions : TabsGridActions {
    fun onKey(keyCode: Int)
    fun openTabs()
    fun backendFor(session: TerminalSession): TerminalSessionBackend
    fun onFilesNavigate(tabId: String, dir: String)
    fun onOpenFile(path: String)
    fun onOpenExternal(path: String)
    fun onOpenTerminal(dir: String)
}

fun bindTermuxScreen(
    view: ComposeView,
    screen: TermuxScreenState,
    terminalView: TerminalComposeView,
    modifierKeys: ModifierKeys,
    tabs: TabsUiState,
    thumbnails: TabThumbnails,
    actions: TermuxScreenActions,
) {
    view.setContent {
        TermuxTheme(screen.fontStamp) { TermuxScreen(screen, terminalView, modifierKeys, tabs, thumbnails, actions) }
    }
}

@Composable
fun TermuxScreen(
    screen: TermuxScreenState,
    terminalView: TerminalComposeView,
    modifierKeys: ModifierKeys,
    tabs: TabsUiState,
    thumbnails: TabThumbnails,
    actions: TermuxScreenActions,
) {
    val density = LocalDensity.current
    val keyBarGapPx = with(density) { KeyBarGap.roundToPx() }
    val marginVerticalPx = with(density) { screen.marginVertical.dp.roundToPx() }
    SessionProcessPoller(tabs)

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            AndroidView(
                factory = { terminalView },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = screen.marginHorizontal.dp, vertical = screen.marginVertical.dp),
            )
            val current = tabs.tabs.firstOrNull { it.current }
            val tabsButton: @Composable () -> Unit = {
                TabsButton(
                    tabCount = tabs.tabs.size,
                    tabIcon = {
                        TabIcon(
                            current?.kind ?: TabKind.TERMINAL,
                            current?.let { tabs.processNames[it.id] },
                            current?.title,
                            LocalContentColor.current,
                        )
                    },
                    onOpenTabs = actions::openTabs,
                )
            }
            when (val active = tabs.activeTab) {
                is Tab.Files -> FilesTab(
                    dir = active.dir,
                    root = tabs.activeRoot ?: active.dir,
                    tabsButton = tabsButton,
                    onNavigate = { actions.onFilesNavigate(active.id, it) },
                    onOpenFile = actions::onOpenFile,
                    onOpenTerminal = actions::onOpenTerminal,
                    modifier = Modifier.fillMaxSize(),
                )
                is Tab.Viewer -> ViewerTab(
                    path = active.path,
                    tabsButton = tabsButton,
                    onOpenFile = actions::onOpenFile,
                    onOpenExternal = actions::onOpenExternal,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> KeyBar(
                    modifiers = modifierKeys,
                    onKey = actions::onKey,
                    tabsButton = tabsButton,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = KeyBarGap)
                        .onSizeChanged { size ->
                            terminalView.setContentBottomInset((size.height + keyBarGapPx - marginVerticalPx).coerceAtLeast(0))
                        },
                )
            }
        }
        if (screen.tabsGridVisible) {
            DisposableEffect(tabs.sessions) {
                thumbnails.start(tabs.sessions.mapValues { (_, session) -> actions.backendFor(session) })
                onDispose { thumbnails.stop() }
            }
        }
        TabsGrid(
            visible = screen.tabsGridVisible,
            workspaces = tabs.workspaces,
            tabs = tabs.tabs.map { it.copy(processName = tabs.processNames[it.id]) },
            defaultRoot = TermuxConstants.TERMUX_HOME_DIR_PATH,
            actions = actions,
            preview = { tab, modifier ->
                if (tab.kind == TabKind.TERMINAL) TerminalThumbnail(thumbnails.frames[tab.id], modifier)
            },
        )
        ConfigErrorsDialogHost()
    }
}
