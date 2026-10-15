package com.mrndtvndv.term.ui.sftp

import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.workspace.SftpScroll

/** Ownership is fixed even when Compose updates the callback before disposing the previous folder. */
internal class SftpScrollSession(private val folder: String) {
    private var measuredPosition: SftpScroll? = null

    fun record(position: SftpScroll, measuredItems: Int) {
        if (measuredItems > 0) measuredPosition = position
    }

    fun save(write: (String, SftpScroll) -> Unit) {
        measuredPosition?.let { write(folder, it) }
    }
}

/** A root listing is not sufficient to restore a position inside asynchronously expanded descendants. */
internal fun SftpTreeState.hasRestoredChildren(files: List<SftpFile>): Boolean {
    val pending = ArrayDeque(files)
    while (pending.isNotEmpty()) {
        val file = pending.removeFirst()
        if (!file.isDirectory || file.path !in expanded) continue
        if (file.path !in loaded) return false
        children[file.path]?.let(pending::addAll)
    }
    return true
}
