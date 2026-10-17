package com.mrndtvndv.term.ui.settings

import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarLayout
import com.mrndtvndv.term.data.prefs.KeyBarLimits
import java.util.UUID

/** Replaces the item with the same id, or appends it while the layout is under [KeyBarLimits.MaxItems]. */
fun KeyBarLayout.withItemSaved(item: KeyBarItem): KeyBarLayout {
    val index = items.indexOfFirst { it.id == item.id }
    return when {
        index >= 0 -> copy(items = items.toMutableList().apply { set(index, item) })
        items.size < KeyBarLimits.MaxItems -> copy(items = items + item)
        else -> this
    }
}

fun KeyBarLayout.withoutItem(id: String): KeyBarLayout = copy(items = items.filterNot { it.id == id })

fun KeyBarLayout.withItemInserted(index: Int, item: KeyBarItem): KeyBarLayout {
    if (items.size >= KeyBarLimits.MaxItems || items.any { it.id == item.id }) return this

    val insertionIndex = index.coerceIn(0, items.size)
    return copy(items = items.toMutableList().apply { add(insertionIndex, item) })
}

fun KeyBarLayout.withItemDuplicated(id: String): KeyBarLayout {
    if (items.size >= KeyBarLimits.MaxItems) return this

    val index = items.indexOfFirst { it.id == id }
    if (index < 0) return this

    val duplicate = items[index].copy(id = UUID.randomUUID().toString())
    return copy(items = items.toMutableList().apply { add(index + 1, duplicate) })
}

fun KeyBarLayout.withItemMoved(from: Int, to: Int): KeyBarLayout =
    copy(items = items.toMutableList().apply { add(to, removeAt(from)) })
