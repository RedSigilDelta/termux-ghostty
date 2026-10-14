package com.mrndtvndv.term.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.mrndtvndv.term.workspace.Tab

/** Per-tab back stacks for one open workspace. [activeTab] is the tab currently shown. */
class WorkspaceNavigator(private val activeTab: () -> Tab?) {
    private val sftpStacks = mutableMapOf<String, NavBackStack<NavKey>>()
    private val reviewStacks = mutableMapOf<String, NavBackStack<NavKey>>()

    fun sftpStack(tab: Tab.Sftp): NavBackStack<NavKey> =
        sftpStacks.getOrPut(tab.id) { NavBackStack<NavKey>(SftpFolder(tab.dir)) }

    fun reviewStack(tabId: String): NavBackStack<NavKey> =
        reviewStacks.getOrPut(tabId) { NavBackStack<NavKey>(ReviewRoute.Changes) }

    private val currentStack: NavBackStack<NavKey>?
        get() = when (val tab = activeTab()) {
            is Tab.Sftp -> sftpStacks[tab.id]
            is Tab.Git -> reviewStacks[tab.id]
            else -> null
        }

    /** True when back only pops the active tab's inner stack, so the workspace itself must not be popped. */
    val handlesBack: Boolean
        get() = (currentStack?.size ?: 0) > 1

    fun openDiff(tabId: String, route: ReviewRoute) {
        reviewStack(tabId).add(route)
    }

    fun openFolder(tabId: String, path: String) {
        val stack = sftpStacks[tabId] ?: return
        val index = stack.indexOfLast { (it as? SftpFolder)?.path == path }
        if (index < 0) {
            stack.add(SftpFolder(path))
        } else {
            while (stack.lastIndex > index) stack.removeAt(stack.lastIndex)
        }
    }

    fun dropTab(tabId: String) {
        sftpStacks.remove(tabId)
        reviewStacks.remove(tabId)
    }

    fun goBack(): Boolean {
        val stack = currentStack ?: return false
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}
