package com.mrndtvndv.term.ui.workspace

import android.content.ClipData
import android.graphics.Typeface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.server.TerminalProgress
import com.mrndtvndv.term.ui.keyboard.KeyBar
import com.mrndtvndv.term.ui.keyboard.ModifierKeys
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardState
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardVisibilityTracker
import com.mrndtvndv.term.ui.keyboard.sendKeyCode
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalBlurRegion
import com.termux.terminal.compose.TerminalImeController
import com.termux.terminal.compose.TerminalImeVisibility
import com.termux.terminal.compose.TerminalWallpaperConfig
import com.termux.terminal.compose.session.ProgramStatusState
import kotlin.math.roundToInt

class TerminalAppearance(
    val typeface: Typeface,
    val fontSizeRange: IntRange,
    val wallpaper: TerminalWallpaperConfig,
)

class TerminalPaneCallbacks(
    val onUploadMedia: () -> Unit,
    val onUploadFile: () -> Unit,
    val onCommitContent: (ClipData) -> Boolean,
    val onOpenUrl: (String) -> Unit,
    val onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
)

/** Gap between the key bar and the bottom inset, also the rows' clearance above the bar. */
private val KeyBarGap = 6.dp

/** Overlay mode keeps the canvas grid under the bar; otherwise rows stop above it. */
internal fun keyBarInsetPx(canvas: Rect, keyBarTop: Float, gapPx: Float, overlay: Boolean): Int =
    if (!overlay && keyBarTop > 0f) (canvas.bottom - keyBarTop + gapPx).roundToInt().coerceAtLeast(0) else 0

/** The pill is blurred in the canvas's own coordinates. */
private fun keyBarBlurRegions(pill: Rect, canvas: Rect): List<TerminalBlurRegion> =
    if (pill.isEmpty) emptyList() else listOf(TerminalBlurRegion(pill.translate(-canvas.topLeft), pill.height / 2))

private fun agentsSlot(state: ProgramStatusState, onOpen: () -> Unit): (@Composable () -> Unit)? {
    if (state.items.isEmpty()) return null
    val agents: @Composable () -> Unit = { AgentsButton(state = state, onClick = onOpen) }
    return agents
}

/** Fills the page above the IME and inside the horizontal cutouts; the status and navigation bars sit over it. */
@Composable
private fun Modifier.terminalCanvasLayout(onBounds: (Rect) -> Unit): Modifier = this
    .fillMaxSize()
    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
    .windowInsetsPadding(WindowInsets.ime)
    .onGloballyPositioned { onBounds(it.boundsInRoot()) }

/** Lifts the bar above the navigation bar or the IME, and reports its top edge. */
@Composable
private fun Modifier.keyBarLayout(onTop: (Float) -> Unit): Modifier = this
    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
    .padding(bottom = KeyBarGap)
    .onGloballyPositioned { onTop(it.boundsInRoot().top) }

/**
 * The terminal fills the page, so the wallpaper and rows run under the status and navigation bars.
 * The key bar floats above the navigation bar and the keyboard. Rows stop above the bar through
 * the canvas's bottom inset unless overlay is enabled; the top inset keeps them below the status bar.
 */
@Suppress("LongParameterList")
@Composable
fun TerminalPane(
    session: TerminalSession,
    progress: TerminalProgress?,
    settings: AppSettings,
    appearance: TerminalAppearance,
    isActive: Boolean,
    programStatus: ProgramStatusState,
    onProgramStatusOpened: () -> Unit,
    tabsButton: (@Composable () -> Unit)?,
    callbacks: TerminalPaneCallbacks,
    keyBarActions: (@Composable () -> Unit)? = null,
) {
    val modifierKeys = remember { ModifierKeys() }
    val imeController = remember(session) { TerminalImeController() }
    val density = LocalDensity.current
    val keyBarGapPx = with(density) { KeyBarGap.toPx() }
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }
    var keyBarTop by remember { mutableFloatStateOf(0f) }
    var keyBarPill by remember { mutableStateOf(Rect.Zero) }
    var showAgents by remember { mutableStateOf(false) }

    SoftKeyboardStateEffects(
        session = session,
        settings = settings,
        imeController = imeController,
        isTerminalActive = isActive,
        onUpdateSettings = callbacks.onUpdateSettings,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        TerminalCanvas(
            session = session,
            settings = settings,
            typeface = appearance.typeface,
            fontSizeRange = appearance.fontSizeRange,
            onFontSizeChange = { size -> callbacks.onUpdateSettings { it.copy(fontSize = size) } },
            modifierKeys = modifierKeys,
            onUploadMedia = callbacks.onUploadMedia,
            onUploadFile = callbacks.onUploadFile,
            onCommitContent = callbacks.onCommitContent,
            onOpenUrl = callbacks.onOpenUrl,
            isTerminalActive = isActive,
            imeController = imeController,
            wallpaperConfig = appearance.wallpaper,
            contentTopInsetPx = WindowInsets.statusBars.getTop(density),
            contentBottomInsetPx = keyBarInsetPx(canvasBounds, keyBarTop, keyBarGapPx, settings.overlayKeyBar),
            blurRegions = keyBarBlurRegions(keyBarPill, canvasBounds),
            modifier = Modifier.terminalCanvasLayout { canvasBounds = it },
        )
        KeyBar(
            modifiers = modifierKeys,
            onKey = { keyCode -> sendKeyCode(session, keyCode, modifierKeys) },
            joystickMode = settings.arrowJoystickMode,
            tabsButton = tabsButton,
            agents = agentsSlot(programStatus) {
                showAgents = true
                onProgramStatusOpened()
            },
            extraActions = keyBarActions,
            modifier = Modifier.align(Alignment.BottomCenter).keyBarLayout { keyBarTop = it },
            pillModifier = Modifier.onGloballyPositioned { keyBarPill = it.boundsInRoot() },
        )
        TerminalProgressStrip(
            progress = progress, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
        )
        ProgramStatusSheet(
            state = programStatus,
            visible = showAgents,
            onDismiss = { showAgents = false },
        )
    }
}

@Composable
private fun SoftKeyboardStateEffects(
    session: TerminalSession,
    settings: AppSettings,
    imeController: TerminalImeController,
    isTerminalActive: Boolean,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
) {
    val isLifecycleResumed by rememberLifecycleResumed()
    val imeVisibility = imeController.visibility
    val keyboardVisibilityTracker = remember(session) { SoftKeyboardVisibilityTracker() }
    val currentRememberKeyboardState = rememberUpdatedState(settings.rememberSoftKeyboardState)
    val currentLastSoftKeyboardState = rememberUpdatedState(settings.lastSoftKeyboardState)
    val currentOnUpdateSettings = rememberUpdatedState(onUpdateSettings)

    LaunchedEffect(imeController, imeVisibility, isTerminalActive, isLifecycleResumed) {
        if (imeVisibility == TerminalImeVisibility.UNKNOWN) return@LaunchedEffect
        val visibilityToPersist = keyboardVisibilityTracker.observe(
            isVisible = imeVisibility == TerminalImeVisibility.VISIBLE,
            isTerminalActive = isTerminalActive,
            isLifecycleResumed = isLifecycleResumed
        )
        if (visibilityToPersist != null && currentRememberKeyboardState.value) {
            val state = if (visibilityToPersist) SoftKeyboardState.VISIBLE else SoftKeyboardState.HIDDEN
            currentOnUpdateSettings.value { it.copy(lastSoftKeyboardState = state) }
        }
    }
    LaunchedEffect(session, isTerminalActive, settings.rememberSoftKeyboardState, isLifecycleResumed) {
        if (!settings.rememberSoftKeyboardState) return@LaunchedEffect
        if (!isTerminalActive || !isLifecycleResumed) return@LaunchedEffect
        val stateToRestore = currentLastSoftKeyboardState.value
        if (stateToRestore == SoftKeyboardState.UNKNOWN) return@LaunchedEffect
        withFrameNanos { }
        if (stateToRestore == SoftKeyboardState.VISIBLE) {
            imeController.show()
        } else {
            imeController.hide()
        }
    }
}

@Composable
private fun rememberLifecycleResumed(): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    val isResumed = remember(lifecycleOwner) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            isResumed.value = when (event) {
                Lifecycle.Event.ON_RESUME -> true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                Lifecycle.Event.ON_DESTROY -> false
                else -> lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return isResumed
}
