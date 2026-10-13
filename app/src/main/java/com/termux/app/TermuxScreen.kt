package com.termux.app

import android.view.View
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.R
import com.termux.app.settings.ConfigErrorsDialogHost
import com.termux.app.settings.TabBarAlign
import com.termux.app.settings.TabBarPosition
import com.termux.app.settings.TermuxSettingsRepository
import com.termux.app.terminal.NewSessionListener
import com.termux.app.terminal.SessionDrawerList
import com.termux.app.terminal.SessionListener
import com.termux.app.terminal.SessionTabStrip
import com.termux.app.terminal.SessionsUiState
import com.termux.app.terminal.io.TerminalToolbar
import com.termux.app.terminal.io.TerminalToolbarState
import com.termux.app.ui.TermuxTheme
import com.termux.terminal.TerminalSession

class TermuxScreenState(
    useSessionTabs: Boolean,
    tabBarPosition: TabBarPosition,
    tabBarAlign: TabBarAlign,
    marginHorizontal: Int,
    marginVertical: Int,
) {
    var drawerOpen by mutableStateOf(false)
    private var locked by mutableStateOf(false)
    var useSessionTabs by mutableStateOf(useSessionTabs)
    var tabBarPosition by mutableStateOf(tabBarPosition)
    var tabBarAlign by mutableStateOf(tabBarAlign)
    var marginHorizontal by mutableIntStateOf(marginHorizontal)
    var marginVertical by mutableIntStateOf(marginVertical)
    var imeVisible by mutableStateOf(false)
    var fontStamp by mutableLongStateOf(0L)

    fun openDrawer() {
        drawerOpen = true
    }

    fun closeDrawer() {
        drawerOpen = false
    }

    fun toggleDrawer() {
        drawerOpen = !drawerOpen
    }

    fun isDrawerOpen(): Boolean = drawerOpen

    fun isDrawerLocked(): Boolean = locked

    fun setDrawerLocked(locked: Boolean) {
        this.locked = locked
        if (locked) closeDrawer()
    }
}

interface TermuxScreenActions {
    fun openSettings()
    fun toggleSoftKeyboard()
    fun toggleTerminalToolbar()
    fun newSession(failsafe: Boolean)
    fun showNewNamedSessionDialog()
    fun onSessionEvent(session: TerminalSession, longPress: Boolean)
    fun currentSession(): TerminalSession?
    fun onExtraKey(key: String, ctrl: Boolean, alt: Boolean, shift: Boolean, fn: Boolean)
    fun sendText(text: String)
    fun onKeysPageSelected()
}

fun bindTermuxScreen(
    view: ComposeView,
    screen: TermuxScreenState,
    terminalView: View,
    toolbar: TerminalToolbarState,
    sessions: SessionsUiState,
    actions: TermuxScreenActions,
) {
    view.setContent {
        TermuxTheme(screen.fontStamp) { TermuxScreen(screen, terminalView, toolbar, sessions, actions) }
    }
}

@Composable
fun TermuxScreen(
    screen: TermuxScreenState,
    terminalView: View,
    toolbar: TerminalToolbarState,
    sessions: SessionsUiState,
    actions: TermuxScreenActions,
) {
    val effective by TermuxSettingsRepository.get().effective.collectAsState()
    val settings = effective.settings
    val animations = settings.animations
    val showKeyboardFab = settings.keyboardFab && !(settings.keyboardFabHideWhileTyping && screen.imeVisible)
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sessionListener = SessionListener { session, longPress -> actions.onSessionEvent(session, longPress) }
    val newSessionListener = NewSessionListener { failsafe -> actions.newSession(failsafe) }
    val edgeSlop = with(LocalDensity.current) { 24.dp.toPx() }

    LaunchedEffect(screen.drawerOpen) {
        when {
            !animations -> drawerState.snapTo(if (screen.drawerOpen) DrawerValue.Open else DrawerValue.Closed)
            screen.drawerOpen -> drawerState.open()
            else -> drawerState.close()
        }
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue }.collect { screen.drawerOpen = it == DrawerValue.Open }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !screen.isDrawerLocked() && drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(240.dp)) {
                IconButton(onClick = actions::openSettings) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.action_open_settings),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    SessionDrawerList(sessions, sessionListener)
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    DrawerActionButton(
                        label = stringResource(R.string.action_toggle_soft_keyboard),
                        onClick = {
                            actions.toggleSoftKeyboard()
                            screen.closeDrawer()
                        },
                        onLongClick = actions::toggleTerminalToolbar,
                        modifier = Modifier.weight(1f),
                    )
                    DrawerActionButton(
                        label = stringResource(R.string.action_new_session),
                        onClick = { actions.newSession(false) },
                        onLongClick = actions::showNewNamedSessionDialog,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = screen.marginHorizontal.dp, vertical = screen.marginVertical.dp),
            ) {
                if (screen.useSessionTabs && screen.tabBarPosition == TabBarPosition.TOP) {
                    SessionTabStrip(sessions, screen.tabBarAlign, sessionListener, newSessionListener)
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(factory = { terminalView }, modifier = Modifier.fillMaxSize())
                    if (showKeyboardFab) {
                        SmallFloatingActionButton(
                            onClick = actions::toggleSoftKeyboard,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (screen.imeVisible) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard,
                                ),
                                contentDescription = stringResource(R.string.action_toggle_soft_keyboard),
                            )
                        }
                    }
                }
                if (screen.useSessionTabs && screen.tabBarPosition == TabBarPosition.BOTTOM) {
                    SessionTabStrip(sessions, screen.tabBarAlign, sessionListener, newSessionListener)
                }
                TerminalToolbar(
                    state = toolbar,
                    session = { actions.currentSession() },
                    onKey = { key, ctrl, alt, shift, fn -> actions.onExtraKey(key, ctrl, alt, shift, fn) },
                    onToggleKeyboard = Runnable { actions.toggleSoftKeyboard() },
                    onSendText = { text -> actions.sendText(text) },
                    onKeysPageSelected = Runnable { actions.onKeysPageSelected() },
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(20.dp)
                    .pointerInput(Unit) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                dragged += amount
                                if (dragged >= edgeSlop && !screen.isDrawerLocked()) {
                                    screen.openDrawer()
                                    dragged = 0f
                                }
                            },
                        )
                    },
            )
            ConfigErrorsDialogHost()
        }
    }
}

@Composable
private fun DrawerActionButton(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}
