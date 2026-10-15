package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerTest {
    private val config = ServerConfig(id = "srv", label = "test", isLocal = true)

    private fun server() = Server(config = config, homeDir = "/home/test")

    @Test
    fun disconnectActionsRunOnceInRegistrationOrder() {
        val server = server()
        val ran = mutableListOf<String>()
        server.onDisconnect { ran += "first" }
        server.onDisconnect { ran += "second" }

        server.disconnect()
        server.disconnect()

        assertEquals(listOf("first", "second"), ran)
    }

    @Test
    fun throwingActionDoesNotSkipTheRest() {
        val server = server()
        val ran = mutableListOf<String>()
        server.onDisconnect { throw IllegalStateException("control client already closed") }
        server.onDisconnect { ran += "after" }

        server.disconnect()

        assertEquals(listOf("after"), ran)
    }

    @Test
    fun actionRegisteredAfterDisconnectRunsImmediately() {
        val server = server()
        server.disconnect()
        val ran = mutableListOf<String>()

        server.onDisconnect { ran += "late" }

        assertEquals(listOf("late"), ran)
    }
}
