package com.mrndtvndv.term.herdr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Remote Herdr actions of one control client, owned by the process-scoped session manager rather than any activity.
 *
 * Actions run one at a time in submission order, so the steps of one action never interleave with another's. Each
 * focus-changing submission is a new intent and supersedes earlier ones: a superseded action completes as
 * [HerdrActionFailure.Superseded] and stops before its next send. A request already sent is allowed to finish, so
 * the server sees requests in submission order. Pane and tab IDs are resolved from stable terminal IDs against a
 * snapshot fetched after the action started, never from IDs captured when the user tapped.
 *
 * Submit from the main thread. Lock order is this owner's lock, then the client's.
 */
class HerdrActions(
    val client: HerdrControlClient,
    scope: CoroutineScope,
    private val freshTimeoutMillis: Long = HerdrControlClient.FRESH_SNAPSHOT_TIMEOUT_MILLIS,
    private val onFailure: (HerdrActionResult.Failed) -> Unit = {},
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val actionScope = CoroutineScope(scope.coroutineContext + job)
    private val serial = Mutex()
    private val lock = Any()

    // Guarded by [lock].
    private var intent = 0L
    private var current: Running? = null
    private var closed = false

    /** A submitted action. [result] always completes, including when superseded or closed. */
    class Ticket(val intent: Long, val result: Deferred<HerdrActionResult>)

    /** True while no newer intent was submitted after [intent] and this owner is open. */
    fun isLatest(intent: Long): Boolean = synchronized(lock) { !closed && this.intent == intent }

    /** Records a newer local intent with no remote step, such as a notification opening the session. */
    fun supersede(): Long = synchronized(lock) { newIntentLocked() }

    /** Focuses the pane running [terminalId]. Upstream `pane.focus` also selects its workspace and tab. */
    fun focusTerminal(terminalId: String): Ticket = submit(focusIntent = true) {
        val acked = withResolvedPane(terminalId) { pane ->
            val info = HerdrWire.decodePaneInfo(send("pane.focus", buildJsonObject { put("pane_id", pane.id) }))
            // Pane IDs are positional upstream, so a stale ID can name another terminal's pane.
            if (info.terminalId != terminalId) throw HerdrException(MISMATCH_ERROR)
            info
        }
        focused(acked.tabId) { it.focusedPane()?.terminalId == terminalId }
    }

    /** Focuses the tab that currently holds [anchorTerminalId]. Tab IDs are positional, so a pane anchors the tab. */
    fun focusTabOf(anchorTerminalId: String): Ticket = submit(focusIntent = true) {
        var tabId = ""
        withResolvedPane(anchorTerminalId) { pane ->
            tabId = pane.tabId
            send("tab.focus", buildJsonObject { put("tab_id", pane.tabId) })
        }
        focused(tabId) { snapshot -> snapshot.paneForTerminal(anchorTerminalId)?.tabId == snapshot.focusedTabId }
    }

    fun createTab(workspaceId: String): Ticket = submit(focusIntent = true) {
        send("tab.create", buildJsonObject {
            put("workspace_id", workspaceId)
            put("focus", true)
        })
        done()
    }

    fun createWorkspace(sourceWorkspaceId: String): Ticket = submit(focusIntent = true) {
        send("workspace.create", buildJsonObject {
            put("source_workspace_id", sourceWorkspaceId)
            put("focus", true)
        })
        done()
    }

    fun splitPane(terminalId: String, direction: String): Ticket = submit(focusIntent = true) {
        withResolvedPane(terminalId) { pane ->
            send("pane.split", buildJsonObject {
                put("target_pane_id", pane.id)
                put("direction", direction)
                put("focus", true)
            })
        }
        done()
    }

    /** Closes the pane running [terminalId]. Not a focus intent, so a later focus never cancels it. */
    fun closePane(terminalId: String): Ticket = submit(focusIntent = false) {
        withResolvedPane(terminalId) { pane -> send("pane.close", buildJsonObject { put("pane_id", pane.id) }) }
        done()
    }

    /**
     * A snapshot fetched after every action submitted before this call has finished. Returns null when a newer
     * intent arrives before it completes, so a sheet never opens on a workspace the user has since moved away from.
     */
    suspend fun awaitSettledSnapshot(): HerdrFreshSnapshot? {
        val startIntent = synchronized(lock) { intent }
        val fresh = serial.withLock { client.awaitFreshSnapshot(freshTimeoutMillis, requireCoherent = true) }
        // A closed owner still reports why the snapshot is unavailable.
        return fresh.takeIf { synchronized(lock) { closed || intent == startIntent } }
    }

    /** Fails every pending action as disconnected. Called when the connection that owns [client] ends. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            current = null
        }
        job.cancel()
    }

    private fun newIntentLocked(): Long {
        intent++
        current?.let { previous ->
            previous.result.complete(SUPERSEDED)
            previous.job.cancel()
        }
        current = null
        return intent
    }

    @Suppress("TooGenericExceptionCaught")
    private fun submit(focusIntent: Boolean, body: suspend Step.() -> HerdrActionResult): Ticket {
        val result = CompletableDeferred<HerdrActionResult>()
        fun finish(outcome: HerdrActionResult) {
            if (!result.complete(outcome)) return
            if (outcome is HerdrActionResult.Failed && outcome.reason != HerdrActionFailure.Superseded) {
                onFailure(outcome)
            }
        }
        synchronized(lock) {
            if (closed) {
                finish(DISCONNECTED)
                return Ticket(intent, result)
            }
            val ticketIntent = if (focusIntent) newIntentLocked() else intent
            // Undispatched, so each action queues on the mutex in submission order.
            val job = actionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val outcome = serial.withLock { Step(ticketIntent, focusIntent).body() }
                    finish(outcome)
                } catch (error: CancellationException) {
                    finish(if (isClosed()) DISCONNECTED else SUPERSEDED)
                    throw error
                } catch (error: HerdrException) {
                    finish(failureOf(error.error))
                } catch (error: Exception) {
                    // Never let an unexpected failure escape into the process scope.
                    val message = error.message ?: "Herdr request failed"
                    finish(HerdrActionResult.Failed(HerdrActionFailure.Rejected, message))
                } finally {
                    finish(DISCONNECTED)
                    synchronized(lock) { if (current?.result === result) current = null }
                }
            }
            if (focusIntent && !result.isCompleted) current = Running(job, result)
            return Ticket(ticketIntent, result)
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    /** One action's view of the client. Superseded focus intents stop at each checkpoint. */
    private inner class Step(private val stepIntent: Long, private val focusIntent: Boolean) {
        fun checkpoint() {
            if (focusIntent && !isLatest(stepIntent)) throw CancellationException("superseded")
            if (isClosed()) throw CancellationException("closed")
        }

        suspend fun fresh(): HerdrSnapshot {
            checkpoint()
            return when (val fresh = client.awaitFreshSnapshot(freshTimeoutMillis)) {
                is HerdrFreshSnapshot.Ready -> fresh.snapshot.also { checkpoint() }
                is HerdrFreshSnapshot.Unavailable -> throw HerdrException(fresh.error)
            }
        }

        /** Sends after a checkpoint. Once written, the request finishes even if a newer intent arrives meanwhile. */
        suspend fun send(method: String, params: JsonObject): JsonObject {
            checkpoint()
            return withContext(NonCancellable) { client.request(method, params) }.also { checkpoint() }
        }

        /**
         * Runs [action] on the pane that runs [terminalId] in a fresh snapshot. A not-found or mismatched reply means
         * the ID went stale between the snapshot and the request, so the pane is resolved once more from a newer one.
         */
        suspend fun <T> withResolvedPane(terminalId: String, action: suspend (HerdrPane) -> T): T {
            var attempt = 1
            while (true) {
                val pane = fresh().paneForTerminal(terminalId) ?: throw targetGone()
                try {
                    return action(pane)
                } catch (error: HerdrException) {
                    if (attempt >= RESOLVE_ATTEMPTS || error.error.serverCode !in RETRY_CODES) throw error
                }
                attempt++
            }
        }

        /** A focus the server acknowledged, plus whether a later snapshot shows it as server-global focus. */
        suspend fun focused(tabId: String, isFocused: (HerdrSnapshot) -> Boolean): HerdrActionResult {
            checkpoint()
            val observation = when (val after = client.awaitFreshSnapshot(freshTimeoutMillis)) {
                is HerdrFreshSnapshot.Ready -> when {
                    !after.state.snapshotFresh -> GlobalFocus.Unknown
                    isFocused(after.snapshot) -> GlobalFocus.Observed
                    else -> GlobalFocus.NotObserved
                }
                is HerdrFreshSnapshot.Unavailable -> GlobalFocus.Unknown
            }
            checkpoint()
            return HerdrActionResult.Focused(tabId, observation)
        }

        suspend fun done(): HerdrActionResult {
            checkpoint()
            client.refresh()
            return HerdrActionResult.Done
        }

        private fun targetGone() =
            HerdrException(HerdrError(HerdrErrorKind.Rejected, "That pane has closed", TARGET_GONE_CODE))
    }

    private class Running(val job: Job, val result: CompletableDeferred<HerdrActionResult>)

    private companion object {
        const val RESOLVE_ATTEMPTS = 2
        const val TARGET_GONE_CODE = "pane_not_found"
        const val MISMATCH_CODE = "ecto_pane_mismatch"
        val NOT_FOUND_CODES = setOf("pane_not_found", "tab_not_found")
        val RETRY_CODES = NOT_FOUND_CODES + MISMATCH_CODE
        val MISMATCH_ERROR = HerdrError(
            HerdrErrorKind.Rejected,
            "Herdr focused a different pane than requested, so another pane may now be focused",
            MISMATCH_CODE,
        )
        val SUPERSEDED =
            HerdrActionResult.Failed(HerdrActionFailure.Superseded, "A newer Herdr action replaced this one")
        val DISCONNECTED = HerdrActionResult.Failed(HerdrActionFailure.Disconnected, "Herdr is not connected")

        fun failureOf(error: HerdrError): HerdrActionResult.Failed {
            val reason = when {
                error.serverCode in RETRY_CODES -> HerdrActionFailure.TargetGone
                error.kind == HerdrErrorKind.Timeout -> HerdrActionFailure.Timeout
                error.kind in DISCONNECT_KINDS -> HerdrActionFailure.Disconnected
                else -> HerdrActionFailure.Rejected
            }
            return HerdrActionResult.Failed(reason, error.message)
        }

        val DISCONNECT_KINDS = setOf(HerdrErrorKind.Transport, HerdrErrorKind.Disconnected, HerdrErrorKind.EventsLost)
    }
}

sealed interface HerdrActionResult {
    data object Done : HerdrActionResult

    /**
     * Herdr acknowledged the focus request for the intended target, now in [tabId]. [globalFocus] is what a snapshot
     * fetched after the acknowledgment showed for the server-global focus. Upstream reports no focus per attached
     * client, so this never verifies what the app's own Herdr terminal displays.
     */
    data class Focused(val tabId: String, val globalFocus: GlobalFocus) : HerdrActionResult

    data class Failed(val reason: HerdrActionFailure, val message: String) : HerdrActionResult
}

enum class HerdrActionFailure { Superseded, Disconnected, Timeout, TargetGone, Rejected }

/** Server-global snapshot focus after an acknowledged focus. [Unknown] means no fresh snapshot arrived in time. */
enum class GlobalFocus { Observed, NotObserved, Unknown }
