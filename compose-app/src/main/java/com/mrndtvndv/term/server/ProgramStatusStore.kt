package com.mrndtvndv.term.server

import android.util.Log
import com.termux.terminal.ProgramStatusRecord
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val OscLogTag = "OSC7501"

/** Program states from OSC 7501. */
enum class ProgramState { Idle, Working, Done, Error, Blocked }

/** What a blocked program needs from the user, from the OSC 7501 `kind` key. */
enum class ProgramBlockKind { Permission, Question, Auth }

/** One OSC 7501 record. The id is empty for the root record. */
data class ProgramStatusItem(
    val id: String,
    val app: String?,
    val title: String?,
    val msg: String?,
    val state: ProgramState,
    val blockKind: ProgramBlockKind?,
    val progress: Int?,
    val updated: Long,
)

/** Records for one terminal, plus the newest update the user has already looked at. */
data class ProgramStatusState(
    val items: List<ProgramStatusItem> = emptyList(),
    val seenThrough: Long = 0L,
) {
    /** Higher ranks need attention first. Done records only rank while unseen. */
    fun rank(item: ProgramStatusItem): Int = when (item.state) {
        ProgramState.Blocked -> 4
        ProgramState.Error -> 3
        ProgramState.Done -> if (item.updated > seenThrough) 2 else 0
        ProgramState.Working -> 1
        ProgramState.Idle -> 0
    }

    /** The item that most needs attention, or null when nothing does. */
    fun mostUrgent(): ProgramStatusItem? = items.maxByOrNull { rank(it) }?.takeIf { rank(it) > 0 }
}

/**
 * Process-scoped bridge from the terminal's OSC 7501 records to the Compose UI, modeled on
 * [TerminalProgressStore]. Updates arrive on the main thread from the session callback.
 */
class ProgramStatusStore {
    private val statesBySession = mutableMapOf<String, MutableStateFlow<ProgramStatusState>>()

    @Synchronized
    fun observe(sessionHandle: String): StateFlow<ProgramStatusState> =
        statesBySession.getOrPut(sessionHandle) {
            Log.d(OscLogTag, "store create handle=$sessionHandle")
            MutableStateFlow(ProgramStatusState())
        }

    fun update(session: TerminalSession) {
        val items = session.programStatusRecords.map { it.toItem() }
        Log.d(OscLogTag, "store update handle=${session.mHandle} records=${items.size}")
        publish(session.mHandle) { current -> current.copy(items = items) }
    }

    /** Opening the sheet marks everything reported so far as seen. */
    fun markSeen(sessionHandle: String) {
        publish(sessionHandle) { current ->
            val seenThrough = current.items.maxOfOrNull { it.updated } ?: current.seenThrough
            Log.d(OscLogTag, "store markSeen handle=$sessionHandle seenThrough=$seenThrough")
            current.copy(seenThrough = seenThrough)
        }
    }

    fun remove(session: TerminalSession) {
        remove(session.mHandle)
    }

    @Synchronized
    fun remove(sessionHandle: String) {
        Log.d(OscLogTag, "store remove handle=$sessionHandle")
        statesBySession.remove(sessionHandle)?.value = ProgramStatusState()
    }

    @Synchronized
    private fun publish(sessionHandle: String, transform: (ProgramStatusState) -> ProgramStatusState) {
        val flow = statesBySession.getOrPut(sessionHandle) { MutableStateFlow(ProgramStatusState()) }
        flow.value = transform(flow.value)
    }
}

private fun ProgramStatusRecord.toItem(): ProgramStatusItem = ProgramStatusItem(
    id = id,
    app = app?.let(::displaySafe),
    title = title?.let(::displaySafe),
    msg = msg?.let(::displaySafe),
    state = parseProgramState(state),
    blockKind = kind?.let(::parseBlockKind),
    progress = progress.takeIf { it in 0..100 },
    updated = updated,
)

private fun parseProgramState(name: String): ProgramState = when (name) {
    "working" -> ProgramState.Working
    "done" -> ProgramState.Done
    "blocked" -> ProgramState.Blocked
    "error" -> ProgramState.Error
    else -> ProgramState.Idle
}

private fun parseBlockKind(name: String): ProgramBlockKind? = when (name) {
    "permission" -> ProgramBlockKind.Permission
    "question" -> ProgramBlockKind.Question
    "auth" -> ProgramBlockKind.Auth
    else -> null
}

/** Strips bidi overrides and isolates, which the spec says terminals SHOULD disarm. */
private fun displaySafe(text: String): String =
    text.filterNot { ch -> ch in '‪'..'‮' || ch in '⁦'..'⁩' }
