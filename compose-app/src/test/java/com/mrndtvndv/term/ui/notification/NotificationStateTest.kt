package com.mrndtvndv.term.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationStateTest {
    @Test
    fun `post preserves the typed destination`() {
        val state = NotificationState()
        val destination = NotificationDestination.HerdrSession("server-1", "/home/u/.config/herdr/herdr.sock")

        state.post(title = "Build finished", body = "Ready", destination = destination)

        assertEquals(destination, state.notification.value?.destination)
        state.dismiss()
        assertNull(state.notification.value)
    }

    @Test
    fun `app messages have no destination`() {
        val state = NotificationState()

        state.post("Couldn't focus pane", "Herdr is not connected")

        assertNull(state.notification.value?.destination)
    }
}
