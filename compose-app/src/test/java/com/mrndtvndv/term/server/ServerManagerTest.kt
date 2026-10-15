package com.mrndtvndv.term.server

import com.mrndtvndv.term.domain.ServerConfig
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerManagerTest {
    private val config = ServerConfig(id = "srv", label = "test", isLocal = true)

    private fun server(config: ServerConfig) = Server(config = config, homeDir = "/home/test")

    @Test
    fun concurrentConnectsShareOneServer() = runBlocking {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val manager = ServerManager(create = { cfg ->
            calls.incrementAndGet()
            started.complete(Unit)
            gate.await()
            server(cfg)
        })

        val first = async { manager.connect(config) }
        started.await()
        val second = async { manager.connect(config) }
        gate.complete(Unit)

        assertSame(first.await(), second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun failedConnectCanBeRetried() = runBlocking {
        var attempts = 0
        val manager = ServerManager(create = { cfg ->
            attempts++
            if (attempts == 1) throw IOException("refused")
            server(cfg)
        })

        assertTrue(runCatching { manager.connect(config) }.isFailure)
        assertEquals("srv", manager.connect(config).config.id)
        assertEquals(2, attempts)
    }
}
