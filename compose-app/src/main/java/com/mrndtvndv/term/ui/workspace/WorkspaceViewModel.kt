package com.mrndtvndv.term.ui.workspace

import androidx.lifecycle.ViewModel
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.termux.terminal.TerminalSession

class WorkspaceViewModel(
    private val sessionManager: AppSessionManagerAccess,
) : ViewModel() {

    fun observeTerminalProgress(session: TerminalSession) = sessionManager.observeTerminalProgress(session)

    fun observeProgramStatus(session: TerminalSession) = sessionManager.observeProgramStatus(session)

    fun markProgramStatusSeen(session: TerminalSession) = sessionManager.markProgramStatusSeen(session)
}
