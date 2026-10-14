package com.termux.app.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.session.TerminalSessionBackend

/** Keeps a live frame per terminal tab while the tabs grid is shown. Main thread only. */
class TabThumbnails {
    var frames by mutableStateOf<Map<String, TerminalFrame>>(emptyMap())
        private set
    private val observers = HashMap<String, AutoCloseable>()

    fun start(backends: Map<String, TerminalSessionBackend>) {
        stop()
        frames = backends.mapNotNull { (tabId, backend) -> backend.currentFrame()?.let { tabId to it } }.toMap()
        for ((tabId, backend) in backends) {
            observers[tabId] = backend.observeFrames {
                backend.currentFrame()?.let { frames = frames + (tabId to it) }
            }
        }
    }

    // Frames are kept so the grid's exit animation still shows them; start() replaces them.
    fun stop() {
        observers.values.forEach { it.close() }
        observers.clear()
    }
}
