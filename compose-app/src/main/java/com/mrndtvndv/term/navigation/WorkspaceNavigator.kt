package com.mrndtvndv.term.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.mrndtvndv.term.workspace.Tab

/**
 * Back handling for one open workspace. [activeTab] is the tab currently shown. Git tabs keep their review stacks
 * here. An SFTP tab's folders are durable in [Tab.Sftp]; its composed browser attaches so back can pop them.
 */
class WorkspaceNavigator(private val activeTab: () -> Tab?) {
    private val reviewStacks = mutableMapOf<String, NavBackStack<NavKey>>()
    private val sftpBack = mutableMapOf<String, () -> Boolean>()

    fun reviewStack(tabId: String): NavBackStack<NavKey> =
        reviewStacks.getOrPut(tabId) { NavBackStack<NavKey>(ReviewRoute.Changes) }

    /** [goBack] pops the SFTP tab's browser one folder and returns false at its root. */
    fun attachSftp(tabId: String, goBack: () -> Boolean) {
        sftpBack[tabId] = goBack
    }

    fun detachSftp(tabId: String, goBack: () -> Boolean) {
        if (sftpBack[tabId] === goBack) sftpBack.remove(tabId)
    }

    /** True when back only pops the active tab's inner stack, so the workspace itself must not be popped. */
    val handlesBack: Boolean
        get() = when (val tab = activeTab()) {
            is Tab.Sftp -> tab.browser.back.isNotEmpty()
            is Tab.Git -> (reviewStacks[tab.id]?.size ?: 0) > 1
            else -> false
        }

    fun openDiff(tabId: String, route: ReviewRoute) {
        reviewStack(tabId).add(route)
    }

    fun dropTab(tabId: String) {
        sftpBack.remove(tabId)
        reviewStacks.remove(tabId)
    }

    fun goBack(): Boolean = when (val tab = activeTab()) {
        is Tab.Sftp -> sftpBack[tab.id]?.invoke() ?: false
        is Tab.Git -> {
            val stack = reviewStacks[tab.id]
            if (stack == null || stack.size <= 1) {
                false
            } else {
                stack.removeAt(stack.lastIndex)
                true
            }
        }
        else -> false
    }
}
