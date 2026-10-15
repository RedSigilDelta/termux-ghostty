package com.mrndtvndv.term.ui.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationStateTest {
    @Test
    fun `post preserves Herdr terminal destination`() {
        val state = NotificationState()

        state.post(
            title = "Build finished",
            body = "Ready",
            serverId = "server-1",
            tabId = "app-tab-1",
            herdrTerminalId = "terminal-1",
        )

        val notification = state.notification.value
        assertEquals("server-1", notification?.serverId)
        assertEquals("app-tab-1", notification?.tabId)
        assertEquals("terminal-1", notification?.herdrTerminalId)

        state.dismiss()
        assertNull(state.notification.value)
    }
}
