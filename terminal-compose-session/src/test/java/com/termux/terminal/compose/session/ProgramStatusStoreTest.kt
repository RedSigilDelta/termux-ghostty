package com.termux.terminal.compose.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramStatusStoreTest {

    @Test
    fun markSeenPublishesHandleToAll() {
        val store = ProgramStatusStore()

        store.markSeen("a")

        assertTrue("a" in store.all.value)
        assertEquals(ProgramStatusState(), store.all.value["a"])
    }

    @Test
    fun removeDropsHandleFromAll() {
        val store = ProgramStatusStore()
        store.markSeen("a")
        store.markSeen("b")

        store.remove("a")

        assertFalse("a" in store.all.value)
        assertEquals(setOf("b"), store.all.value.keys)
    }
}
