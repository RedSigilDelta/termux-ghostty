package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Reorder state for a tab grid. Keeps [tabs] and [onMove] current, and scrolls while a drag nears an edge. */
@Composable
fun rememberTabReorderState(
    gridState: LazyGridState,
    tabs: List<TabCard>,
    visible: Boolean,
    onMove: (id: String, toIndex: Int) -> Unit,
): TabReorderState {
    val reorder = remember(gridState) { TabReorderState(gridState) }
    SideEffect {
        reorder.knownIds = tabs.map { it.id }
        reorder.onMove = onMove
    }
    LaunchedEffect(visible) { if (!visible) reorder.cancel() }
    val density = LocalDensity.current
    val edgePx = with(density) { 72.dp.toPx() }
    val stepPx = with(density) { 14.dp.toPx() }
    val slopPx = with(density) { 8.dp.toPx() }
    val draggingId = reorder.draggingId
    LaunchedEffect(draggingId) {
        if (draggingId == null) return@LaunchedEffect
        while (true) {
            delay(16)
            val delta = reorder.edgeScrollDelta(edgePx, stepPx, slopPx)
            if (delta != 0f) {
                gridState.scrollBy(delta)
                reorder.refresh()
            }
        }
    }
    return reorder
}

/**
 * Drag-to-reorder state for the tab grid. Positions are in the grid viewport's coordinates, so they stay
 * valid while the grid scrolls. The dragged card follows the pointer and the others make room as it passes.
 * The model only changes on drop, so the pager never sees the intermediate orders.
 */
class TabReorderState(private val gridState: LazyGridState) {
    /** Grid viewport. Drag positions arrive relative to the handle and are converted through it. */
    var viewport: LayoutCoordinates? = null

    /** Tab ids in display order. Refreshed on every composition. */
    var knownIds: List<String> = emptyList()

    /** Reports a drop to the model. Refreshed on every composition. */
    var onMove: (id: String, toIndex: Int) -> Unit = { _, _ -> }

    var draggingId by mutableStateOf<String?>(null)
        private set

    /** Order shown while dragging. */
    private var order by mutableStateOf<List<String>>(emptyList())
    private var startOrder: List<String> = emptyList()

    /** Pointer in viewport coordinates. */
    private var pointer by mutableStateOf(Offset.Zero)
    private var startPointer = Offset.Zero
    private var startSlot = Offset.Zero

    fun start(id: String, source: LayoutCoordinates?, at: Offset) {
        val pointerAt = toViewport(source, at) ?: return
        val slot = slotOf(id) ?: return
        draggingId = id
        startOrder = knownIds
        order = knownIds
        startPointer = pointerAt
        startSlot = slot
        pointer = pointerAt
    }

    fun move(source: LayoutCoordinates?, at: Offset) {
        if (draggingId == null) return
        pointer = toViewport(source, at) ?: return
        reorderUnderPointer()
    }

    /**
     * Offset from the card's laid-out slot that keeps it under the pointer. Derived from the live layout each
     * time, so the card stays put when its slot changes, and when the grid scrolls under it.
     */
    fun translationOf(id: String): Offset {
        if (id != draggingId) return Offset.Zero
        val slot = slotOf(id) ?: return Offset.Zero
        return pointer - startPointer + startSlot - slot
    }

    /** Ends the drag. Reports the new order when it changed and the tab set is unchanged. */
    fun drop() {
        val id = draggingId ?: return
        val stale = knownIds.toSet() != startOrder.toSet()
        val changed = !stale && order != startOrder
        val toIndex = order.indexOf(id)
        reset()
        if (changed) onMove(id, toIndex)
    }

    fun cancel() = reset()

    /** Ends the drag if [id] is the one being dragged, e.g. its grip left composition mid-drag. */
    fun release(id: String) {
        if (draggingId == id) reset()
    }

    /** The cards to lay out: [tabs] with the drag's order applied while dragging. */
    fun arrange(tabs: List<TabCard>): List<TabCard> {
        if (draggingId == null) return tabs
        val byId = tabs.associateBy { it.id }
        return order.mapNotNull { byId[it] } + tabs.filter { it.id !in order }
    }

    /**
     * Scroll step while the pointer is in the top or bottom [edgePx] band and has moved toward that edge by
     * more than [slopPx] since the drag started. Holding a grip that already sits in a band doesn't scroll.
     */
    fun edgeScrollDelta(edgePx: Float, stepPx: Float, slopPx: Float): Float {
        if (draggingId == null) return 0f
        val height = viewport?.size?.height?.toFloat() ?: return 0f
        return when {
            pointer.y < edgePx && pointer.y < startPointer.y - slopPx -> -stepPx
            pointer.y > height - edgePx && pointer.y > startPointer.y + slopPx -> stepPx
            else -> 0f
        }
    }

    /** Re-applies the swap for the current pointer, e.g. after the grid scrolled under it. */
    fun refresh() = reorderUnderPointer()

    private fun reorderUnderPointer() {
        val id = draggingId ?: return
        val target = targetUnderPointer(id) ?: return
        val from = order.indexOf(id)
        val to = order.indexOf(target)
        if (from < 0 || to < 0 || from == to) return
        val next = order.toMutableList()
        val moved = next.removeAt(from)
        next.add(to, moved)
        order = next
    }

    /** The other card whose bounds hold the dragged card's center. Slot space, like the layout offsets. */
    private fun targetUnderPointer(id: String): String? {
        val layout = gridState.layoutInfo.visibleItemsInfo
        val dragged = layout.firstOrNull { it.key == id } ?: return null
        val center = startSlot + (pointer - startPointer) +
            Offset(dragged.size.width / 2f, dragged.size.height / 2f)
        return layout.firstOrNull { it.key != id && contains(it, center) }?.key as? String
    }

    private fun reset() {
        draggingId = null
        order = emptyList()
        startOrder = emptyList()
    }

    private fun slotOf(id: String): Offset? =
        gridState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == id }
            ?.let { Offset(it.offset.x.toFloat(), it.offset.y.toFloat()) }

    private fun toViewport(source: LayoutCoordinates?, at: Offset): Offset? {
        val grid = viewport ?: return null
        if (source == null || !source.isAttached) return null
        return grid.localPositionOf(source, at)
    }

    private fun contains(item: LazyGridItemInfo, point: Offset): Boolean {
        val left = item.offset.x.toFloat()
        val top = item.offset.y.toFloat()
        return point.x in left..left + item.size.width && point.y in top..top + item.size.height
    }
}
