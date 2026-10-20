package com.mrndtvndv.term.ui.workspace

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.activity.BackEventCompat
import androidx.activity.ExperimentalActivityApi
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mrndtvndv.term.AppContainer
import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.data.prefs.KeyBarHostAction
import com.mrndtvndv.term.herdr.HerdrAgentStatus
import com.mrndtvndv.term.herdr.HerdrState
import com.mrndtvndv.term.herdr.panesIn
import com.mrndtvndv.term.navigation.LocalAnimationsEnabled
import com.mrndtvndv.term.server.TerminalEntry
import com.mrndtvndv.term.workspace.HerdrFileContext
import com.mrndtvndv.term.workspace.Tab
import com.mrndtvndv.term.workspace.herdrSheetTabId
import com.mrndtvndv.term.workspace.newId
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt

private enum class SidebarSide(val dir: Float, val originX: Float, val alignment: Alignment) {
    Left(dir = +1f, originX = 0f, alignment = Alignment.CenterStart),
    Right(dir = -1f, originX = 1f, alignment = Alignment.CenterEnd),
}

private const val SidebarRevealFraction = 0.786f
private const val SidebarParallaxRatio = 0.30f
private const val CardMinScale = 0.90f
private val CardMaxCornerRadius = 28.dp
private val CardShadowBlurRadius = 32.dp
private val CardShadowOffsetX = 7.dp
private const val CardShadowBaseAlpha = 0.44f
private const val ShadowBitmapScale = 0.25f
private const val SidebarRevealDurationMs = 300
private val SidebarRevealEasing = CubicBezierEasing(0.07f, 0.92f, 0.38f, 1.00f)

/** A server's Herdr session: one full-screen terminal running Herdr's own UI, sheets, and file previews. */
@OptIn(ExperimentalActivityApi::class)
@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")
@Composable
fun HerdrSessionRoute(
    serverId: String,
    appViewModel: AppViewModel,
    container: AppContainer,
    settings: AppSettings,
    appearance: TerminalAppearance,
    host: TerminalHostCallbacks,
    onOpenServerList: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val server = appViewModel.getServer(serverId)
    val workspaceState by appViewModel.workspaces.collectAsStateWithLifecycle()
    val tab = remember(workspaceState) { appViewModel.herdrFullUiTab(serverId) }
    val terminals by (server?.terminals ?: flowOf(emptyMap<String, TerminalEntry>()))
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val serverListState by appViewModel.serverListState.collectAsStateWithLifecycle()
    val contextFlow = remember(serverId) { appViewModel.herdrContext(serverId) }
    val fileContext by contextFlow.collectAsStateWithLifecycle()
    val ready = fileContext as? HerdrFileContext.Ready
    val scopeKey = ready?.scopeKey
    val viewersFlow: StateFlow<List<Tab.Viewer>> = remember(serverId, scopeKey) {
        if (scopeKey == null) {
            MutableStateFlow(emptyList<Tab.Viewer>())
        } else {
            appViewModel.herdrViewers(serverId, scopeKey)
        }
    }
    val viewers by viewersFlow.collectAsStateWithLifecycle()
    val currentScopeKey by rememberUpdatedState(scopeKey)
    val coroutineScope = rememberCoroutineScope()
    // Composition-scoped: nothing outside this route holds the request, so it cannot outlive the route.
    val sheetRequests = remember(serverId) {
        HerdrSheetRequests(
            scope = coroutineScope,
            // Server-global snapshot focus captured coherently, not a verified client focus.
            capture = { appViewModel.captureHerdrContext(serverId) },
            restartControl = { appViewModel.restartHerdr(serverId) },
            // Git starts at the captured cwd; SFTP resumes this workspace's existing browser.
            prepare = { kind, scopeKey ->
                if (kind == HerdrSheetKind.Git) {
                    appViewModel.connectionStore(serverId, herdrSheetTabId(kind.key, scopeKey)).viewModelStore.clear()
                }
            },
            homeDir = { appViewModel.getServer(serverId)?.homeDir.orEmpty() },
            newOpenId = ::newId,
        )
    }
    val sheet by sheetRequests.state.collectAsStateWithLifecycle()
    DisposableEffect(sheetRequests) {
        onDispose { sheetRequests.cancel() }
    }
    var sidebarOpen by rememberSaveable { mutableStateOf(false) }
    var sidebarSide by rememberSaveable { mutableStateOf(SidebarSide.Left) }
    val revealAnimatable = remember { Animatable(0f) }
    val animationsEnabled = LocalAnimationsEnabled.current
    val isSidebarVisible by remember {
        derivedStateOf { sidebarOpen || revealAnimatable.value > 0f }
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val maxOffsetPx = containerSize.width * SidebarRevealFraction
    val sheetStates = rememberSaveableStateHolder()
    val herdrState by (server?.herdr?.state ?: flowOf(HerdrState()))
        .collectAsStateWithLifecycle(initialValue = HerdrState())
    val agentButtonStatus = when {
        herdrState.snapshot?.panes.orEmpty().any {
            it.agent != null && it.agentStatus == HerdrAgentStatus.Blocked
        } -> HerdrAgentStatus.Blocked
        herdrState.snapshot?.panes.orEmpty().any {
            it.agent != null && it.agentStatus == HerdrAgentStatus.Done
        } -> HerdrAgentStatus.Done
        else -> null
    }
    val sessionWorkspace = workspaceState.workspaces.firstOrNull {
        it.id == appViewModel.herdrSessionWorkspaceId(serverId)
    }
    val activeTabId = sessionWorkspace?.activeTabId
        ?.takeIf { id -> id == tab?.id || viewers.any { it.id == id } } ?: tab?.id
    val store = appViewModel.connectionStore(serverId)
    val workspaceViewModel = viewModel(viewModelStoreOwner = store) {
        WorkspaceViewModel(sessionManager = container.sessionManager)
    }

    LaunchedEffect(serverId) {
        appViewModel.ensureHerdrSession(serverId)
        appViewModel.refreshHerdrContext(serverId)
    }
    LaunchedEffect(sidebarOpen, animationsEnabled) {
        val target = if (sidebarOpen) 1f else 0f
        if (animationsEnabled) {
            revealAnimatable.animateTo(
                target,
                tween(SidebarRevealDurationMs, easing = SidebarRevealEasing),
            )
        } else {
            revealAnimatable.snapTo(target)
        }
    }

    if (server == null || tab == null) {
        HerdrLoading()
        return
    }

    fun openSidebar() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        sidebarOpen = true
    }

    BackHandler(enabled = sidebarOpen && sheet == null) {
        sidebarOpen = false
    }
    BackHandler(enabled = activeTabId != tab.id && sheet == null && !sidebarOpen) {
        appViewModel.activateTab(tab.id)
    }
    PredictiveBackHandler(enabled = activeTabId == tab.id && sheet == null && !sidebarOpen) { progressFlow ->
        var detectedEdge: Int? = null
        try {
            progressFlow.collect { backEvent ->
                if (detectedEdge == null) {
                    detectedEdge = backEvent.swipeEdge
                    sidebarSide = when (backEvent.swipeEdge) {
                        BackEventCompat.EDGE_RIGHT -> SidebarSide.Right
                        BackEventCompat.EDGE_LEFT -> SidebarSide.Left
                        else -> sidebarSide
                    }
                }
            }
            sheetRequests.cancel()
            appViewModel.refreshHerdrContext(serverId)
            openSidebar()
        } catch (_: CancellationException) {
            // A cancelled predictive gesture must not open the sidebar.
        }
    }

    fun closeViewer(tabId: String) {
        val index = viewers.indexOfFirst { it.id == tabId }
        val next = viewers.getOrNull(index + 1) ?: viewers.getOrNull(index - 1)
        appViewModel.closeHerdrViewer(tabId)
        if (activeTabId == tabId) appViewModel.activateTab(next?.id ?: tab.id)
    }

    // The sheet shows at once as Opening; the coherent capture runs in parallel with its entrance animation.
    fun openSheet(kind: HerdrSheetKind) {
        if (!sidebarOpen) sheetRequests.open(kind)
    }

    val onHostAction: (KeyBarHostAction) -> Unit = { kind ->
        when (kind) {
            KeyBarHostAction.Git -> openSheet(HerdrSheetKind.Git)
            KeyBarHostAction.Sftp -> openSheet(HerdrSheetKind.Sftp)
        }
    }
    val agentsButton: @Composable () -> Unit = {
        val statusDotColor = when (agentButtonStatus) {
            HerdrAgentStatus.Blocked -> MaterialTheme.colorScheme.error
            HerdrAgentStatus.Done -> MaterialTheme.colorScheme.tertiary
            else -> null
        }
        val buttonDescription = when (agentButtonStatus) {
            HerdrAgentStatus.Blocked -> "Agents, needs input"
            HerdrAgentStatus.Done -> "Agents, done"
            else -> "Agents"
        }
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        var hasPressed by remember { mutableStateOf(false) }
        val cornerRadius = remember { Animatable(12.dp, Dp.VectorConverter) }
        val shapeMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Dp>()
        LaunchedEffect(pressed, shapeMotion) {
            if (pressed) {
                hasPressed = true
                cornerRadius.animateTo(24.dp, shapeMotion)
                return@LaunchedEffect
            }
            if (!hasPressed) return@LaunchedEffect
            cornerRadius.animateTo(24.dp, shapeMotion)
            delay(5)
            cornerRadius.animateTo(12.dp, shapeMotion)
        }
        Surface(
            shape = RoundedCornerShape(cornerRadius.value),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shadowElevation = 3.dp,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(interactionSource = interactionSource, indication = null, role = Role.Button) {
                        sheetRequests.cancel()
                        appViewModel.refreshHerdrContext(serverId)
                        openSidebar()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.SmartToy, contentDescription = buttonDescription)
                statusDotColor?.let { color ->
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(10.dp).size(8.dp)
                            .background(color, CircleShape),
                    )
                }
            }
        }
    }
    val paneCallbacks: (TerminalSession) -> TerminalPaneCallbacks = { session ->
        host.paneCallbacks(session, container.preferences::update)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .onSizeChanged { containerSize = it },
    ) {
        if (isSidebarVisible) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(SidebarRevealFraction)
                    .align(sidebarSide.alignment)
                    .graphicsLayer {
                        val progress = revealAnimatable.value
                        translationX = -sidebarSide.dir * (1f - progress) *
                            (maxOffsetPx * SidebarParallaxRatio)
                    },
            ) {
                sheetStates.SaveableStateProvider("agents:$serverId") {
                    HerdrAgentsSheet(
                        state = herdrState,
                        currentServer = server.config,
                        serverListState = serverListState,
                        collapsedWorkspaceIds = workspaceState.collapsedHerdrAgentWorkspaces(serverId),
                        onWorkspaceCollapsedChange = { workspaceId, collapsed ->
                            appViewModel.setHerdrAgentWorkspaceCollapsed(serverId, workspaceId, collapsed)
                        },
                        onNewTab = { workspaceId -> appViewModel.createHerdrTab(serverId, workspaceId) },
                        onNewWorkspace = { sourceWorkspaceId ->
                            appViewModel.createHerdrWorkspace(serverId, sourceWorkspaceId)
                        },
                        onSplitPane = { paneId, direction ->
                            // The action resolves the pane again from its stable terminal when it runs.
                            val terminalId = herdrState.snapshot?.panes?.firstOrNull { it.id == paneId }?.terminalId
                            if (terminalId == null) {
                                appViewModel.notificationState.post("Couldn't split pane", "That pane has closed")
                            } else {
                                appViewModel.splitHerdrPane(serverId, terminalId, direction)
                            }
                        },
                        onFocusPane = { pane -> appViewModel.focusHerdrTerminal(serverId, pane.terminalId) },
                        onFocusTab = { remoteTab ->
                            // Tab IDs are positional, so one of the tab's panes identifies it when the action runs.
                            val anchor = herdrState.snapshot?.panesIn(remoteTab.id)?.firstOrNull()?.terminalId
                            if (anchor == null) {
                                appViewModel.notificationState.post("Couldn't focus tab", "That tab has closed")
                            } else {
                                appViewModel.focusHerdrTabOf(serverId, anchor)
                            }
                        },
                        onClosePane = { pane -> appViewModel.closeHerdrPane(serverId, pane.terminalId) },
                        onSwitchServer = appViewModel::connect,
                        onSwitchToLocal = appViewModel::startLocalTerminal,
                        onOpenServerList = onOpenServerList,
                        onOpenSettings = onOpenSettings,
                        onDismiss = { sidebarOpen = false },
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = revealAnimatable.value
                    val scale = 1f - (1f - CardMinScale) * progress
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(sidebarSide.originX, 0.5f)
                    translationX = sidebarSide.dir * progress * maxOffsetPx
                },
        ) {
            WorkspacePager(
                tabs = listOf(tab) + viewers,
                currentTabId = activeTabId,
                onSelectTab = { appViewModel.activateTab(it.id) },
                empty = {},
            ) { page, isActive ->
                when (page) {
                    is Tab.Terminal -> TerminalTab(
                        serverId = serverId,
                        tab = page,
                        entry = terminals[page.id],
                        isActive = isActive && sheet == null && !sidebarOpen,
                        appViewModel = appViewModel,
                        workspaceViewModel = workspaceViewModel,
                        settings = settings,
                        appearance = appearance,
                        paneCallbacks = paneCallbacks,
                        tabsButton = agentsButton,
                        onHostAction = onHostAction,
                    )
                    is Tab.Viewer -> Column(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                            .statusBarsPadding(),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = page.path.substringAfterLast('/'),
                                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                                maxLines = 1,
                            )
                            IconButton(onClick = { closeViewer(page.id) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close preview")
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            ViewerTab(
                                client = server.sftpClient,
                                tab = page,
                                onClose = { closeViewer(page.id) },
                                tabsButton = agentsButton,
                            )
                        }
                    }
                    else -> Unit
                }
            }
            if (isSidebarVisible) {
                CardFrameOverlay(
                    side = sidebarSide,
                    progressProvider = { revealAnimatable.value },
                    maskColor = MaterialTheme.colorScheme.surfaceContainerLow,
                )
            }
            if (sidebarOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { sidebarOpen = false }
                        },
                )
            }
        }
        // One host for every phase keeps the same modal, so a finished capture neither dismisses nor re-animates it.
        sheet?.let { current ->
            HerdrSheetHost(
                state = current,
                serverId = serverId,
                server = server,
                appViewModel = appViewModel,
                browsers = container.workspaceRepository,
                // Show the preview only if its captured collection is still current.
                onViewerOpened = { openedScope, tabId ->
                    val eligible = sheetRequests.canSelectPreview(current.requestId, openedScope)
                    if (openedScope == currentScopeKey && eligible) {
                        appViewModel.activateTab(tabId)
                    }
                },
                onRetry = sheetRequests::retry,
                onBeginDismiss = sheetRequests::beginDismiss,
                onDismiss = sheetRequests::dismiss,
                savedState = sheetStates,
            )
        }
    }
}

@Composable
private fun CardFrameOverlay(
    side: SidebarSide,
    progressProvider: () -> Float,
    maskColor: Color,
) {
    val currentSide by rememberUpdatedState(side)
    val currentMaskColor by rememberUpdatedState(maskColor)
    Spacer(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val activeSide = currentSide
                val activeMaskColor = currentMaskColor
                val cornerRadiusPx = CardMaxCornerRadius.toPx()
                val cardRect = Rect(0f, 0f, size.width, size.height)
                val roundedCard = RoundRect(cardRect, CornerRadius(cornerRadiusPx))
                val fullCardPath = Path().apply { addRect(cardRect) }
                val roundedCardPath = Path().apply { addRoundRect(roundedCard) }
                val cornerMaskPath = Path.combine(
                    PathOperation.Difference,
                    fullCardPath,
                    roundedCardPath,
                )

                val bitmapWidth = (size.width * ShadowBitmapScale).roundToInt().coerceAtLeast(1)
                val bitmapHeight = (size.height * ShadowBitmapScale).roundToInt().coerceAtLeast(1)
                val scaledRadius = cornerRadiusPx * ShadowBitmapScale
                val scaledBlur = CardShadowBlurRadius.toPx() * ShadowBitmapScale
                val scaledOffsetX = activeSide.dir * CardShadowOffsetX.toPx() * ShadowBitmapScale
                val shadowBitmap = createCardShadowBitmap(
                    bitmapWidth = bitmapWidth,
                    bitmapHeight = bitmapHeight,
                    cornerRadiusPx = scaledRadius,
                    blurRadiusPx = scaledBlur,
                    offsetX = scaledOffsetX,
                )

                val dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                onDrawBehind {
                    val progress = progressProvider()
                    if (progress > 0f) {
                        drawImage(
                            image = shadowBitmap,
                            dstSize = dstSize,
                            alpha = progress,
                            filterQuality = FilterQuality.Low,
                        )
                        drawPath(cornerMaskPath, activeMaskColor)
                    }
                }
            },
    )
}

private fun createCardShadowBitmap(
    bitmapWidth: Int,
    bitmapHeight: Int,
    cornerRadiusPx: Float,
    blurRadiusPx: Float,
    offsetX: Float,
): ImageBitmap {
    val shadowBitmap = ImageBitmap(bitmapWidth, bitmapHeight)
    val halfStrokePx = blurRadiusPx * 2f
    val strokeRadius = cornerRadiusPx + halfStrokePx
    val bitmapRect = Rect(0f, 0f, bitmapWidth.toFloat(), bitmapHeight.toFloat())
    val bitmapRoundPath = Path().apply {
        addRoundRect(RoundRect(bitmapRect, CornerRadius(cornerRadiusPx)))
    }
    val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = halfStrokePx * 2f
        color = Color.Black.copy(alpha = CardShadowBaseAlpha).toArgb()
        maskFilter = BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL)
    }
    val bitmapCanvas = androidx.compose.ui.graphics.Canvas(shadowBitmap)
    bitmapCanvas.save()
    bitmapCanvas.clipPath(bitmapRoundPath, ClipOp.Intersect)
    bitmapCanvas.nativeCanvas.drawRoundRect(
        offsetX - halfStrokePx,
        -halfStrokePx,
        bitmapWidth + offsetX + halfStrokePx,
        bitmapHeight + halfStrokePx,
        strokeRadius,
        strokeRadius,
        shadowPaint,
    )
    bitmapCanvas.restore()
    return shadowBitmap
}

@Composable
private fun HerdrLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
