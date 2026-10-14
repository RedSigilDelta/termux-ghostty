package com.termux.app.workspace.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FilesTabTest {

    private val home = "/data/data/com.termux/files/home"

    @Test
    fun homeIsASingleTildeCrumb() {
        assertEquals(listOf("~" to home), breadcrumbSegments(home, home))
    }

    @Test
    fun pathInsideHomeStartsAtTilde() {
        assertEquals(
            listOf("~" to home, "projects" to "$home/projects", "app" to "$home/projects/app"),
            breadcrumbSegments("$home/projects/app", home),
        )
    }

    @Test
    fun pathOutsideHomeStartsAtRoot() {
        assertEquals(
            listOf("/" to "/", "sdcard" to "/sdcard", "Download" to "/sdcard/Download"),
            breadcrumbSegments("/sdcard/Download", home),
        )
    }

    @Test
    fun siblingWithHomePrefixIsOutsideHome() {
        assertEquals(
            listOf("/" to "/", "data" to "/data", "home2" to "/data/home2"),
            breadcrumbSegments("/data/home2", "/data/home"),
        )
    }

    @Test
    fun filesystemRootIsASingleSlashCrumb() {
        assertEquals(listOf("/" to "/"), breadcrumbSegments("/", home))
    }
}
