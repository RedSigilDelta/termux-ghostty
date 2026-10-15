package com.mrndtvndv.term.ui.sftp

import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.workspace.SftpScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SftpScrollSessionTest {
    @Test
    fun disposingTheOldFolderCannotWriteIntoTheNewFolder() {
        val writes = mutableMapOf<String, SftpScroll>()
        val first = SftpScrollSession("/a")
        first.record(SftpScroll(42, 7), 100)
        val second = SftpScrollSession("/b")
        second.record(SftpScroll(3, 2), 10)

        val updatedWriter: (String, SftpScroll) -> Unit = { folder, position -> writes[folder] = position }
        second.save(updatedWriter)
        first.save(updatedWriter)

        assertEquals(mapOf("/a" to SftpScroll(42, 7), "/b" to SftpScroll(3, 2)), writes)
    }

    @Test
    fun dismissalBeforeMeasurementDoesNotOverwriteTheSavedDeepPosition() {
        var saved = SftpScroll(42, 7)
        val session = SftpScrollSession("/project")
        session.record(SftpScroll(), 0)
        session.save { _, position -> saved = position }
        assertEquals(SftpScroll(42, 7), saved)
    }

    @Test
    fun temporaryEmptyContentKeepsTheLastMeasuredPosition() {
        var saved = SftpScroll()
        val session = SftpScrollSession("/project")
        session.record(SftpScroll(42, 7), 100)
        session.record(SftpScroll(), 0)
        session.save { _, position -> saved = position }
        assertEquals(SftpScroll(42, 7), saved)
    }

    @Test
    fun restorationWaitsForEveryVisibleExpandedDescendant() {
        val parent = folder("/project/a")
        val child = folder("/project/a/b")
        val initial = SftpTreeState(expanded = setOf(parent.path, child.path))
        assertFalse(initial.hasRestoredChildren(listOf(parent)))

        val parentLoaded = initial.copy(children = mapOf(parent.path to listOf(child)), loaded = setOf(parent.path))
        assertFalse(parentLoaded.hasRestoredChildren(listOf(parent)))
        assertTrue(parentLoaded.copy(loaded = setOf(parent.path, child.path)).hasRestoredChildren(listOf(parent)))
        assertTrue(parentLoaded.copy(expanded = setOf(child.path)).hasRestoredChildren(listOf(parent)))
    }

    @Test
    fun failedChildListingDoesNotLeaveRestorationWaitingForever() {
        val parent = folder("/project/a")
        val failed = SftpTreeState(expanded = setOf(parent.path), loaded = setOf(parent.path))
        assertTrue(failed.hasRestoredChildren(listOf(parent)))
    }

    private fun folder(path: String) = SftpFile(path.substringAfterLast('/'), path, true, 0, 0, 0)
}
