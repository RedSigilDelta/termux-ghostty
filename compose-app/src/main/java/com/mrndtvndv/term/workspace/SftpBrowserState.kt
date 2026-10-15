package com.mrndtvndv.term.workspace

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Most folders whose scroll position a browser remembers. The least recently saved is dropped first. */
private const val MaxScrollFolders = 64

@Serializable
data class SftpScroll(val index: Int = 0, val offset: Int = 0)

/**
 * Durable navigation of one SFTP browser, shared by ordinary SFTP tabs and Herdr sheets. [back] holds the folders
 * below [dir], bottom first. [expanded] and [collapsed] are absolute tree folders: a collapse is remembered so the
 * browser never expands that folder on its own. [scroll] is the position per folder.
 */
@Serializable
data class SftpBrowserState(
    val dir: String,
    val back: List<String> = emptyList(),
    val treeMode: Boolean = false,
    val expanded: List<String> = emptyList(),
    val collapsed: List<String> = emptyList(),
    val scroll: Map<String, SftpScroll> = emptyMap(),
) {
    /** Pops back to [path] when it is already on the stack, or pushes it. */
    fun open(path: String): SftpBrowserState {
        if (path == dir) return this
        val index = back.lastIndexOf(path)
        return if (index >= 0) copy(dir = path, back = back.take(index)) else copy(dir = path, back = back + dir)
    }

    /** The state one folder up the stack, or null at the bottom. */
    fun goBack(): SftpBrowserState? = back.lastOrNull()?.let { copy(dir = it, back = back.dropLast(1)) }

    fun expand(path: String): SftpBrowserState =
        copy(expanded = if (path in expanded) expanded else expanded + path, collapsed = collapsed - path)

    fun collapse(path: String): SftpBrowserState =
        copy(expanded = expanded - path, collapsed = if (path in collapsed) collapsed else collapsed + path)

    /** Expands a folder the browser chose itself. An explicit collapse wins. */
    fun autoExpand(path: String): SftpBrowserState =
        if (path in collapsed || path in expanded) this else copy(expanded = expanded + path)

    fun scrollOf(path: String): SftpScroll = scroll[path] ?: SftpScroll()

    fun withScroll(path: String, position: SftpScroll): SftpBrowserState {
        if (scroll[path] == position) return this
        val kept = (scroll - path).entries.toList().takeLast(MaxScrollFolders - 1)
        return copy(scroll = kept.associate { it.key to it.value } + (path to position))
    }
}

/** Identity of a browser. Never a cwd or pane id: those change while the browser stays the same. */
sealed interface SftpBrowserKey {
    /** An ordinary SFTP tab of a workspace on [serverId]. */
    data class SftpTab(val serverId: String, val tabId: String) : SftpBrowserKey

    /** The browser of one remote Herdr workspace, read through one control socket. */
    data class HerdrWorkspace(val scope: HerdrFileScope) : SftpBrowserKey
}

/** A Herdr workspace's browser, stored with the workspaces so it outlives the connection and the process. */
@Serializable
data class HerdrSftpBrowser(val scope: HerdrFileScope, val browser: SftpBrowserState)

/** Durable browser state. Main-thread only, and every write is visible to [browser] as soon as it returns. */
interface SftpBrowserStore {
    fun browser(key: SftpBrowserKey): SftpBrowserState?

    fun observeBrowser(key: SftpBrowserKey): Flow<SftpBrowserState?>

    /** Creates the browser at [initialDir] unless it exists. An existing browser keeps its navigation. */
    fun openBrowser(key: SftpBrowserKey, initialDir: String)

    /** Applies [transform] to an existing browser. A missing browser is left missing. */
    fun updateBrowser(key: SftpBrowserKey, transform: (SftpBrowserState) -> SftpBrowserState)
}
