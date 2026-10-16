package com.mrndtvndv.term.ui.settings

import com.mrndtvndv.term.data.prefs.KeyBarItem
import com.mrndtvndv.term.data.prefs.KeyBarLayout
import com.mrndtvndv.term.data.prefs.KeyBarLimits

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

fun KeyBarLayout.withItemMoved(from: Int, to: Int): KeyBarLayout =
    copy(items = items.toMutableList().apply { add(to, removeAt(from)) })
