package com.termux.app.workspace.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerKindTest {

    @Test
    fun markdownExtensionsOpenMarkdownViewer() {
        assertEquals(ViewerKind.MARKDOWN, viewerKindFor("README.md"))
        assertEquals(ViewerKind.MARKDOWN, viewerKindFor("notes.MARKDOWN"))
    }

    @Test
    fun htmlExtensionsOpenHtmlViewer() {
        assertEquals(ViewerKind.HTML, viewerKindFor("index.html"))
        assertEquals(ViewerKind.HTML, viewerKindFor("page.HTM"))
    }

    @Test
    fun textAndCodeExtensionsOpenTextViewer() {
        assertEquals(ViewerKind.TEXT, viewerKindFor("build.gradle"))
        assertEquals(ViewerKind.TEXT, viewerKindFor("config.toml"))
        assertEquals(ViewerKind.TEXT, viewerKindFor("Main.kt"))
    }

    @Test
    fun filesWithoutExtensionOpenTextViewer() {
        assertEquals(ViewerKind.TEXT, viewerKindFor("Makefile"))
        assertEquals(ViewerKind.TEXT, viewerKindFor(".bashrc"))
    }

    @Test
    fun otherExtensionsHaveNoViewer() {
        assertNull(viewerKindFor("photo.png"))
        assertNull(viewerKindFor("archive.tar.gz"))
    }

    @Test
    fun nulByteInSniffWindowIsBinary() {
        assertTrue(looksBinary(byteArrayOf(0x41, 0x00, 0x42)))
    }

    @Test
    fun textWithoutNulIsNotBinary() {
        assertFalse(looksBinary("plain text\n".toByteArray()))
    }

    @Test
    fun nulByteAfterSniffWindowIsNotChecked() {
        assertFalse(looksBinary(ByteArray(8192) { 0x41 } + byteArrayOf(0)))
    }
}
