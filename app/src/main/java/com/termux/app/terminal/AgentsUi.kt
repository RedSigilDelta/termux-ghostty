package com.termux.app.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.app.workspace.LiveTerminal
import com.termux.terminal.compose.session.ProgramStatusItem
import com.termux.terminal.compose.session.ProgramStatusState

data class AgentEntry(
    val workspaceId: String,
    val workspaceName: String,
    val tabId: String,
    val tabTitle: String,
    val sessionHandle: String,
    val item: ProgramStatusItem,
    val rank: Int,
)

class AgentsUiState {
    var entries by mutableStateOf<List<AgentEntry>>(emptyList())
        private set

    /** Highest-ranked entry across everything, for the key bar icon; null when nothing needs attention. */
    val mostUrgent: AgentEntry? get() = entries.maxByOrNull { it.rank }?.takeIf { it.rank > 0 }

    var sheetVisible by mutableStateOf(false)

    fun refresh(terminals: List<LiveTerminal>, statuses: Map<String, ProgramStatusState>) {
        entries = terminals.flatMap { live ->
            val state = statuses[live.session.mHandle] ?: return@flatMap emptyList()
            val tabTitle = live.session.mSessionName ?: live.tab.name ?: "Terminal"
            state.items.map { item ->
                AgentEntry(
                    workspaceId = live.workspace.id,
                    workspaceName = live.workspace.name,
                    tabId = live.tab.id,
                    tabTitle = tabTitle,
                    sessionHandle = live.session.mHandle,
                    item = item,
                    rank = state.rank(item),
                )
            }.sortedByDescending { it.rank }
        }
    }
}
