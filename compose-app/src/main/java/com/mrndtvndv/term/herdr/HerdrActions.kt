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
 * the server sees requests in submission order. Pane and tab IDs are resolved from stable terminal IDs, never from IDs
 * captured when the user tapped. Focus takes its pane from the last-known snapshot of a live client without waiting,
 * and retries once from a fresh snapshot when a reply shows the ID is stale. Other actions resolve from a snapshot
 * fetched after the action started.
 *
 * Submit from the main thread. Lock order is this owner's lock, then the client's.
 */
class HerdrActions(
    val client: HerdrControlClient,
    scope: CoroutineScope,
    private val freshTimeoutMillis: Long = HerdrControlClient.FRESH_SNAPSHOT_TIMEOUT_MILLIS,
    private val onFailure: (HerdrActionResult.Failed) -> Unit = {},
    private val onFocusTiming: (String) -> Unit = {},
    private val focusBridge: FocusBridgeClient? = null,
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

    /**
     * Focuses the pane running [terminalId]. Upstream `pane.focus` also selects its workspace and tab. The first send
     * uses the last-known snapshot, so a stale ID can focus another pane before its acknowledgment shows the mismatch.
     * A terminal missing from that snapshot ends as [HerdrActionFailure.TargetGone] with no remote request, and that
     * outcome is not reported. A focus that may have landed on another pane is reported instead.
     */
    fun focusTerminal(terminalId: String): Ticket {
        val started = System.nanoTime()
        val timing: (String) -> Unit = { stage ->
            onFocusTiming("trace=$started stage=$stage elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        }
        timing("submitted")
        return submit(focusIntent = true, notifyTargetGone = false) {
            timing("action_started")
            // Set before a mismatched acknowledgment throws, so a later not-found follows a wrong-pane focus.
            var focusedWrongPane = false
            val acked = try {
                withResolvedPane(terminalId, cached = true) { pane ->
                    timing("pane_resolved")
                    val info = HerdrWire.decodePaneInfo(focusPaneRequest(pane.id, timing))
                    // Pane IDs are positional upstream, so a stale ID can name another terminal's pane.
                    if (info.terminalId != terminalId) {
                        focusedWrongPane = true
                        failMismatchedFocus()
                    }
                    info
                }
            } catch (error: HerdrException) {
                if (focusedWrongPane && error.error.serverCode in NOT_FOUND_CODES) failMismatchedFocus()
                throw error
            }
            timing("focus_acknowledged")
            focused(acked.tabId, timing) { it.focusedPane()?.terminalId == terminalId }
        }
    }

    private fun failMismatchedFocus(): Nothing = throw HerdrException(MISMATCH_ERROR)

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
    private fun submit(
        focusIntent: Boolean,
        notifyTargetGone: Boolean = true,
        body: suspend Step.() -> HerdrActionResult,
    ): Ticket {
        val result = CompletableDeferred<HerdrActionResult>()
        fun finish(outcome: HerdrActionResult) {
            if (!result.complete(outcome)) return
            if (outcome is HerdrActionResult.Failed && outcome.reason != HerdrActionFailure.Superseded) {
                // A focus whose pane is gone is an expected result when panes move or close, so it is not reported.
                val quiet = !notifyTargetGone && outcome.reason == HerdrActionFailure.TargetGone
                if (!quiet) onFailure(outcome)
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
        suspend fun send(method: String, params: JsonObject, timing: ((String) -> Unit)? = null): JsonObject {
            checkpoint()
            return withContext(NonCancellable) { client.request(method, params, timing) }.also { checkpoint() }
        }

        /**
         * Sends pane.focus through the focus bridge when one is configured and usable, otherwise through the direct
         * Herdr socket. The direct socket is used only while nothing has been written, so a focus is never sent twice.
         */
        suspend fun focusPaneRequest(paneId: String, timing: ((String) -> Unit)?): JsonObject {
            checkpoint()
            val viaBridge = focusBridge?.tryFocusPane(paneId, timing) { checkpoint() }
            if (viaBridge != null) return viaBridge.also { checkpoint() }
            return send("pane.focus", buildJsonObject { put("pane_id", paneId) }, timing)
        }

        /**
         * The pane for [terminalId] in the last-known snapshot, with no wait. Only the Live phase qualifies: it is
         * published with a snapshot from the current connection, so a snapshot kept from a lost connection is never
         * used. `snapshotFresh` is deliberately ignored, since a stale ID is caught by the reply. Any other phase fails
         * as disconnected without a remote request.
         */
        private fun lastKnownPane(terminalId: String): HerdrPane? {
            checkpoint()
            val state = client.state.value
            if (state.phase != HerdrPhase.Live) {
                throw HerdrException(HerdrError(HerdrErrorKind.Disconnected, "Herdr is not connected"))
            }
            return state.snapshot?.paneForTerminal(terminalId)
        }

        /**
         * Runs [action] on the pane that runs [terminalId]. With [cached], the first attempt takes the pane from the
         * last-known snapshot without waiting; otherwise it takes it from a fresh one. A not-found or mismatched reply
         * means the ID went stale, so the pane is resolved once more from a fresh snapshot.
         */
        suspend fun <T> withResolvedPane(
            terminalId: String,
            cached: Boolean = false,
            action: suspend (HerdrPane) -> T,
        ): T {
            var attempt = 1
            while (true) {
                val lastKnown = cached && attempt == 1
                val pane = (if (lastKnown) lastKnownPane(terminalId) else fresh().paneForTerminal(terminalId))
                    ?: throw targetGone()
                try {
                    return action(pane)
                } catch (error: HerdrException) {
                    if (attempt >= RESOLVE_ATTEMPTS || error.error.serverCode !in RETRY_CODES) throw error
                }
                attempt++
            }
        }

        /** A focus the server acknowledged, plus whether a later snapshot shows it as server-global focus. */
        suspend fun focused(
            tabId: String,
            timing: ((String) -> Unit)? = null,
            isFocused: (HerdrSnapshot) -> Boolean,
        ): HerdrActionResult {
            checkpoint()
            timing?.invoke("observation_started")
            val observation = when (val after = client.awaitFreshSnapshot(freshTimeoutMillis)) {
                is HerdrFreshSnapshot.Ready -> when {
                    !after.state.snapshotFresh -> GlobalFocus.Unknown
                    isFocused(after.snapshot) -> GlobalFocus.Observed
                    else -> GlobalFocus.NotObserved
                }
                is HerdrFreshSnapshot.Unavailable -> GlobalFocus.Unknown
            }
            timing?.invoke("observation_finished:$observation")
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
                // A mismatch is not a missing pane: another pane may hold focus, so it falls through and is reported.
                error.serverCode in NOT_FOUND_CODES -> HerdrActionFailure.TargetGone
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
