package com.mrndtvndv.term.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.mrndtvndv.term.data.prefs.KeyBarAction
import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import com.mrndtvndv.term.navigation.KeyBarItemStart

internal data class KeyBarEditorCallbacks(
    val onEdit: (String) -> Unit,
    val onMove: (Int, Int) -> Unit,
    val onDelete: (KeyBarItem, Int) -> Unit,
    val onReset: () -> Unit,
    val onAddAction: (KeyBarAction) -> AddedKeyBarItem?,
    val onUndoAdded: (String) -> Unit,
    val onCreateCustom: (KeyBarItemStart) -> Unit,
    val onFull: () -> Unit,
    val onBack: () -> Unit,
)

internal data class AddedKeyBarItem(val id: String, val label: String)

private data class KeyBarEditorContentState(
    val items: List<KeyBarItem>,
    val itemIds: List<String>,
    val isFull: Boolean,
    val reorderState: KeyBarReorderState,
    val padding: PaddingValues,
)

@Composable
internal fun KeyBarEditorScreen(
    items: List<KeyBarItem>,
    snackbarHostState: SnackbarHostState,
    callbacks: KeyBarEditorCallbacks,
) {
    var confirmReset by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    val reorderState = remember { KeyBarReorderState() }
    val itemIds = items.map { it.id }
    LaunchedEffect(itemIds) {
        if (!reorderState.isDragging) reorderState.replaceOrder(itemIds)
    }
    val isFull = items.size >= KeyBarLimits.MaxItems

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { KeyBarEditorTopBar(callbacks.onBack) { confirmReset = true } },
        floatingActionButton = {
            KeyBarAddFab(
                isFull = isFull,
                onFull = callbacks.onFull,
                onAdd = { showAddSheet = true },
            )
        },
    ) { padding ->
        KeyBarEditorBody(
            state = KeyBarEditorContentState(items, itemIds, isFull, reorderState, padding),
            callbacks = callbacks,
            onAdd = { showAddSheet = true },
            onReset = { confirmReset = true },
        )
    }

    if (confirmReset) {
        ResetDialog(
            onConfirm = {
                confirmReset = false
                callbacks.onReset()
            },
            onDismiss = { confirmReset = false },
        )
    }

    if (showAddSheet) {
        KeyBarAddSheet(
            items = items,
            onDismiss = { showAddSheet = false },
            onAddAction = callbacks.onAddAction,
            onUndoAdded = callbacks.onUndoAdded,
            onCreateCustom = { start ->
                showAddSheet = false
                callbacks.onCreateCustom(start)
            },
        )
    }
}

@Composable
private fun KeyBarEditorTopBar(onBack: () -> Unit, onResetRequest: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("Key bar") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Reset to defaults") },
                        onClick = {
                            menuExpanded = false
                            onResetRequest()
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun KeyBarAddFab(isFull: Boolean, onFull: () -> Unit, onAdd: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = { if (isFull) onFull() else onAdd() },
        icon = { Icon(Icons.Default.Add, contentDescription = null) },
        text = { Text("Add key") },
        containerColor = if (isFull) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        contentColor = if (isFull) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        modifier = Modifier.semantics {
            contentDescription = if (isFull) "Add key. The key bar is full." else "Add key"
        },
    )
}

@Composable
private fun KeyBarEditorBody(
    state: KeyBarEditorContentState,
    callbacks: KeyBarEditorCallbacks,
    onAdd: () -> Unit,
    onReset: () -> Unit,
) {
    val items = state.items
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val edgeZonePx = with(density) { 56.dp.toPx() }
    val maxScrollStepPx = with(density) { 12.dp.toPx() }

    LaunchedEffect(state.reorderState.draggingId) {
        if (state.reorderState.draggingId == null) return@LaunchedEffect

        while (state.reorderState.isDragging) {
            withFrameNanos { }
            val step = state.reorderState.autoScrollStep(edgeZonePx, maxScrollStepPx)
            if (step == 0f) continue

            val consumed = scrollState.scrollBy(step)
            state.reorderState.compensateForScroll(consumed)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(state.padding)
                .onGloballyPositioned { coordinates ->
                    val top = coordinates.positionInRoot().y
                    state.reorderState.measureViewport(top, top + coordinates.size.height)
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PreviewStage(
                    items = items,
                    full = state.isFull,
                    onEdit = callbacks.onEdit,
                    onAdd = onAdd,
                )
                ItemCountHeader(items.size)
                if (items.isEmpty()) {
                    EmptyKeyBarState(onAdd = onAdd, onReset = onReset)
                } else {
                    KeyBarItemList(state = state, callbacks = callbacks)
                }
                Spacer(modifier = Modifier.height(if (items.isEmpty()) 20.dp else 88.dp))
            }
        }
    }
}

@Composable
private fun KeyBarItemList(state: KeyBarEditorContentState, callbacks: KeyBarEditorCallbacks) {
    val itemsById = state.items.associateBy { it.id }
    val orderedItems = if (
        state.reorderState.order.size == state.items.size && state.reorderState.order.all { it in itemsById }
    ) {
        state.reorderState.order.mapNotNull(itemsById::get)
    } else {
        state.items
    }
    Column {
        orderedItems.forEach { item ->
            val index = state.items.indexOfFirst { it.id == item.id }
            key(item.id) {
                SwipeableItemRow(
                    item = item,
                    index = index,
                    itemIds = state.itemIds,
                    reorderState = state.reorderState,
                    onMove = callbacks.onMove,
                    onEdit = { callbacks.onEdit(item.id) },
                    onDelete = { callbacks.onDelete(item, index) },
                )
            }
        }
    }
}

@Composable
private fun SwipeableItemRow(
    item: KeyBarItem,
    index: Int,
    itemIds: List<String>,
    reorderState: KeyBarReorderState,
    onMove: (Int, Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
            onDelete()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .onSizeChanged(reorderState::measureRow)
            .then(if (reorderState.draggingId == item.id) Modifier.zIndex(1f) else Modifier)
            .graphicsLayer {
                if (reorderState.draggingId == item.id) translationY = reorderState.translationY
            },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !reorderState.isDragging,
        backgroundContent = {
            if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Row(
                        modifier = Modifier.padding(end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("Delete", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
    ) {
        ItemRowContent(
            data = ItemRowData(item, index, itemIds.size, itemIds),
            reorderState = reorderState,
            callbacks = ItemRowCallbacks(onMove, onEdit, onDelete),
        )
    }
}

private data class ItemRowData(
    val item: KeyBarItem,
    val index: Int,
    val itemCount: Int,
    val itemIds: List<String>,
)

private data class ItemRowCallbacks(
    val onMove: (Int, Int) -> Unit,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
)

@Composable
private fun ItemRowContent(
    data: ItemRowData,
    reorderState: KeyBarReorderState,
    callbacks: ItemRowCallbacks,
) {
    val item = data.item
    val dragging = reorderState.draggingId == item.id
    val summary = item.rowSummary()
    val customActions = itemCustomActions(data, callbacks)
    val rowShape = RoundedCornerShape(12.dp)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .then(if (dragging) Modifier.shadow(8.dp, rowShape) else Modifier)
            .background(
                color = if (dragging) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = if (dragging) rowShape else RectangleShape,
            )
            .clickable(
                role = Role.Button,
                onClickLabel = "Edit ${item.label}",
                onClick = callbacks.onEdit,
            )
            .semantics(mergeDescendants = true) { this.customActions = customActions },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemRowDragHandle(data, reorderState, callbacks.onMove, dragging)
        BarChip(item)
        Spacer(modifier = Modifier.width(12.dp))
        ItemRowDetails(item, summary, Modifier.weight(1f).padding(end = 10.dp))
    }
}

private fun itemCustomActions(data: ItemRowData, callbacks: ItemRowCallbacks): List<CustomAccessibilityAction> =
    buildList {
        if (data.index > 0) {
            add(CustomAccessibilityAction("Move ${data.item.label} up") {
                callbacks.onMove(data.index, data.index - 1)
                true
            })
            add(CustomAccessibilityAction("Move ${data.item.label} to start") {
                callbacks.onMove(data.index, 0)
                true
            })
        }
        if (data.index < data.itemCount - 1) {
            add(CustomAccessibilityAction("Move ${data.item.label} down") {
                callbacks.onMove(data.index, data.index + 1)
                true
            })
            add(CustomAccessibilityAction("Move ${data.item.label} to end") {
                callbacks.onMove(data.index, data.itemCount - 1)
                true
            })
        }
        add(CustomAccessibilityAction("Delete ${data.item.label}") {
            callbacks.onDelete()
            true
        })
    }

@Composable
private fun ItemRowDragHandle(
    data: ItemRowData,
    reorderState: KeyBarReorderState,
    onMove: (Int, Int) -> Unit,
    dragging: Boolean,
) {
    val hapticFeedback = LocalHapticFeedback.current
    var handleCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 64.dp)
            .onGloballyPositioned { handleCoordinates = it }
            .pointerInput(data.item.id, data.itemIds) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        val pointerY = handleCoordinates?.localToRoot(position)?.y
                        if (pointerY != null && reorderState.begin(data.item.id, data.itemIds, pointerY)) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDragEnd = {
                        reorderState.finish()?.let { (from, to) ->
                            if (from != to) onMove(from, to)
                        }
                    },
                    onDragCancel = { reorderState.cancel(data.itemIds) },
                    onDrag = { change, dragAmount ->
                        if (reorderState.isDragging) {
                            change.consume()
                            reorderState.dragBy(dragAmount.y)
                        }
                    },
                )
            }
            .semantics { contentDescription = "Drag to move ${data.item.label}" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.DragHandle,
            contentDescription = null,
            tint = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ItemRowDetails(item: KeyBarItem, summary: String, modifier: Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.label,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.tap is KeyBarAction.Host) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "HERDR",
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun KeyBarItem.rowSummary(): String = when (tap) {
    KeyBarAction.Ctrl -> "tap arms · hold locks"
    KeyBarAction.Alt -> "tap arms · tap twice locks"
    KeyBarAction.Joystick -> "joystick"
    else -> swipeActions().joinToString(" · ") { (direction, action) ->
        "${direction.arrow} ${action.summary()}"
    }.ifEmpty { "No swipes" }
}

internal fun KeyBarItem.swipeActions(): List<Pair<KeyBarDirection, KeyBarAction>> = listOfNotNull(
    swipeUp?.let { KeyBarDirection.Up to it },
    swipeDown?.let { KeyBarDirection.Down to it },
    swipeLeft?.let { KeyBarDirection.Left to it },
    swipeRight?.let { KeyBarDirection.Right to it },
)

private class KeyBarReorderState {
    val order = mutableStateListOf<String>()
    var draggingId by mutableStateOf<String?>(null)
        private set
    var translationY by mutableStateOf(0f)
        private set
    private var pointerY by mutableStateOf<Float?>(null)
    private var viewportTop by mutableStateOf(0f)
    private var viewportBottom by mutableStateOf(0f)
    private var rowHeightPx by mutableStateOf(0f)
    private var startIndex = -1
    private var currentIndex = -1

    val isDragging: Boolean
        get() = draggingId != null

    fun replaceOrder(ids: List<String>) {
        order.clear()
        order.addAll(ids)
    }

    fun measureRow(size: androidx.compose.ui.unit.IntSize) {
        rowHeightPx = size.height.toFloat()
    }

    fun measureViewport(top: Float, bottom: Float) {
        viewportTop = top
        viewportBottom = bottom
    }

    fun begin(id: String, ids: List<String>, initialPointerY: Float): Boolean {
        replaceOrder(ids)
        val index = order.indexOf(id)
        if (index < 0) return false

        draggingId = id
        startIndex = index
        currentIndex = index
        translationY = 0f
        pointerY = initialPointerY
        return true
    }

    fun dragBy(deltaY: Float) {
        if (!isDragging || rowHeightPx <= 0f) return
        pointerY = pointerY?.plus(deltaY)
        translateBy(deltaY)
    }

    fun compensateForScroll(consumed: Float) {
        if (!isDragging || consumed == 0f) return
        translateBy(consumed)
    }

    fun autoScrollStep(edgeZonePx: Float, maxStepPx: Float): Float {
        val fingerY = pointerY ?: return 0f
        if (viewportBottom <= viewportTop || edgeZonePx <= 0f) return 0f

        val distanceFromTop = fingerY - viewportTop
        val distanceFromBottom = viewportBottom - fingerY
        return when {
            distanceFromTop in 0f..edgeZonePx ->
                -maxStepPx * ((edgeZonePx - distanceFromTop) / edgeZonePx).coerceIn(0f, 1f)
            distanceFromBottom in 0f..edgeZonePx ->
                maxStepPx * ((edgeZonePx - distanceFromBottom) / edgeZonePx).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    private fun translateBy(deltaY: Float) {
        translationY += deltaY

        while (translationY >= rowHeightPx && currentIndex < order.lastIndex) {
            val movedId = order.removeAt(currentIndex)
            currentIndex += 1
            order.add(currentIndex, movedId)
            translationY -= rowHeightPx
        }
        while (translationY <= -rowHeightPx && currentIndex > 0) {
            val movedId = order.removeAt(currentIndex)
            currentIndex -= 1
            order.add(currentIndex, movedId)
            translationY += rowHeightPx
        }
    }

    fun finish(): Pair<Int, Int>? {
        if (!isDragging) return null
        val result = startIndex to currentIndex
        clearDrag()
        return result
    }

    fun cancel(ids: List<String>) {
        replaceOrder(ids)
        clearDrag()
    }

    private fun clearDrag() {
        draggingId = null
        translationY = 0f
        pointerY = null
        startIndex = -1
        currentIndex = -1
    }
}

@Composable
private fun ResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reset key bar?") },
        text = { Text("Restore the default keys. Your custom items will be removed.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Reset") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun EditorCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
