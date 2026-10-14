package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.session.TerminalSessionBackend

/**
 * Keeps a live frame per terminal tab while the tabs grid is shown. Owns the backends it creates.
 * Main thread only.
 */
class TabThumbnails {
    var frames by mutableStateOf<Map<String, TerminalFrame>>(emptyMap())
        private set
    private val observers = HashMap<String, AutoCloseable>()
    private val backends = HashMap<String, TerminalSessionBackend>()

    fun start(sessions: Map<String, TerminalSession>) {
        stop()
        for ((tabId, session) in sessions) {
            val backend = TerminalSessionBackend(session)
            backends[tabId] = backend
            backend.currentFrame()?.let { frames = frames + (tabId to it) }
            observers[tabId] = backend.observeFrames {
                backend.currentFrame()?.let { frames = frames + (tabId to it) }
            }
        }
    }

    // Frames are kept so the grid's exit animation still shows them; start() replaces them.
    fun stop() {
        observers.values.forEach { it.close() }
        observers.clear()
        backends.values.forEach { it.release() }
        backends.clear()
    }
}
