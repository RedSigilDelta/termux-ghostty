package com.mrndtvndv.term.ui.workspace

import com.mrndtvndv.term.workspace.HerdrFileContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the one Git/SFTP modal shows. The modal stays up across phases; only its content changes.
 *
 * [presentationId] is stable for one open of the modal (Opening, Ready, Error and Retry) and differs for every new
 * open, so the host keys its modal by it. [requestId] identifies the capture request that owns the phase, so late
 * callbacks of an older request can be told apart. [exiting] means the hide animation runs: the capture is already
 * cancelled and Retry is unavailable.
 */
sealed interface HerdrSheetState {
    val kind: HerdrSheetKind
    val requestId: Long
    val presentationId: Long
    val exiting: Boolean

    /** Shown immediately on tap while the coherent context capture runs. No workspace content is guessed. */
    data class Opening(
        override val kind: HerdrSheetKind,
        override val requestId: Long,
        override val presentationId: Long,
        override val exiting: Boolean = false,
    ) : HerdrSheetState

    /** The capture bound to its originating request, exactly once. */
    data class Ready(
        val capture: HerdrSheetCapture,
        override val requestId: Long,
        override val presentationId: Long,
        override val exiting: Boolean = false,
    ) : HerdrSheetState {
        override val kind: HerdrSheetKind get() = capture.kind
    }

    /** The capture or its restart failed or was superseded. The sheet offers Retry in place. */
    data class Error(
        override val kind: HerdrSheetKind,
        override val requestId: Long,
        override val presentationId: Long,
        val message: String,
        override val exiting: Boolean = false,
    ) : HerdrSheetState
}

private fun HerdrSheetState.asExiting(): HerdrSheetState = when (this) {
    is HerdrSheetState.Opening -> copy(exiting = true)
    is HerdrSheetState.Ready -> copy(exiting = true)
    is HerdrSheetState.Error -> copy(exiting = true)
}

/**
 * Finishes a hide animation. A hide that was cancelled, or that completes after its presentation left composition,
 * must not report a successful dismissal.
 */
internal fun finishHideIfCompleted(cause: Throwable?, presentationShown: Boolean, finish: () -> Unit) {
    if (cause == null && presentationShown) finish()
}

/**
 * Owns the lifecycle of one Git/SFTP sheet request: Opening -> Ready / Error, Retry, dismissal. Main thread only.
 *
 * Context is the server-global snapshot focus, not a verified client focus. [capture] must be the event-coherent
 * capture (`AppViewModel.captureHerdrContext`), never a cached or independent snapshot. Every result is checked
 * against the current request before it is applied, so cancelled work that still completes is inert. Every entry
 * point that acts on a sheet takes the request id its caller was created for; the current state is only a guard.
 *
 * @param capture Null means a newer Herdr focus intent superseded the capture.
 * @param restartControl The existing explicit control-client retry. Suspends until the restart has an outcome, runs
 *   inside the request job on a user Retry, after Opening is published and before [capture]. A failure becomes the
 *   inline Error.
 * @param prepare Runs once per Ready capture, only after it has been accepted (for example the per-open Git reset).
 */
class HerdrSheetRequests(
    private val scope: CoroutineScope,
    private val capture: suspend () -> HerdrFileContext?,
    private val restartControl: suspend () -> Unit,
    private val prepare: (HerdrSheetKind, scopeKey: String) -> Unit,
    private val homeDir: () -> String,
    private val newOpenId: () -> String,
) {
    private val mutableState = MutableStateFlow<HerdrSheetState?>(null)
    val state: StateFlow<HerdrSheetState?> = mutableState

    private var generation = 0L
    private var presentations = 0L
    private var job: Job? = null

    /** Publishes Opening before any suspension, then captures in parallel. Ignored while a sheet is up. */
    fun open(kind: HerdrSheetKind) {
        if (mutableState.value != null) return
        start(kind, ++presentations, restart = false)
    }

    /** Runs a new request in the same presentation for the failed sheet. Ignored for stale ids and while exiting. */
    fun retry(requestId: Long) {
        val error = mutableState.value as? HerdrSheetState.Error ?: return
        if (error.requestId != requestId || error.exiting) return
        start(error.kind, error.presentationId, restart = true)
    }

    /**
     * Starts dismissing the sheet owned by [requestId]: pending work is invalidated now but the sheet stays shown
     * until the hide animation ends. Returns whether the caller should run the hide animation; false for a stale id
     * or when a hide already runs.
     */
    fun beginDismiss(requestId: Long): Boolean {
        val current = mutableState.value ?: return false
        if (current.requestId != requestId || current.exiting) return false
        invalidate()
        mutableState.value = current.asExiting()
        return true
    }

    /** Removes the sheet owned by [requestId]. A callback of an older request does nothing. */
    fun dismiss(requestId: Long) {
        if (mutableState.value?.requestId != requestId) return
        cancel()
    }

    /**
     * Whether a viewer opened from the sheet of [requestId] in collection [originatingScope] may take the local
     * selection: that sheet must still be Ready for that collection and not hiding. The viewer itself is created
     * either way.
     */
    fun canSelectPreview(requestId: Long, originatingScope: String): Boolean {
        val ready = mutableState.value as? HerdrSheetState.Ready ?: return false
        return ready.requestId == requestId && !ready.exiting && ready.capture.scopeKey == originatingScope
    }

    /** Drops any sheet and in-flight work, for example when Agents opens or the route is disposed. */
    fun cancel() {
        invalidate()
        mutableState.value = null
    }

    private fun invalidate() {
        generation++
        job?.cancel()
        job = null
    }

    private fun isCurrent(request: Long): Boolean {
        val current = mutableState.value
        return generation == request && current is HerdrSheetState.Opening && current.requestId == request
    }

    private fun start(kind: HerdrSheetKind, presentationId: Long, restart: Boolean) {
        job?.cancel()
        val request = ++generation
        mutableState.value = HerdrSheetState.Opening(kind, request, presentationId)
        job = scope.launch {
            val result = restartThenCapture(restart)
            // A parent scope that was cancelled meanwhile stays inert even if the capture ignored cancellation.
            currentCoroutineContext().ensureActive()
            deliver(request, kind, presentationId, result)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun restartThenCapture(restart: Boolean): HerdrFileContext? = try {
        if (restart) {
            restartControl()
            // A dismissal during the restart must not go on to capture.
            currentCoroutineContext().ensureActive()
        }
        capture()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        HerdrFileContext.Unavailable(failure.message ?: UNREADABLE_MESSAGE)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deliver(request: Long, kind: HerdrSheetKind, presentationId: Long, result: HerdrFileContext?) {
        // Dismissed, superseded or retried meanwhile: even work that ignored cancellation changes nothing.
        if (!isCurrent(request)) return
        val next = try {
            bind(request, kind, presentationId, result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            HerdrSheetState.Error(kind, request, presentationId, failure.message ?: UNREADABLE_MESSAGE)
        }
        // prepare may have re-entered and cancelled this request; it must not resurrect the sheet.
        if (!isCurrent(request)) return
        mutableState.value = next
    }

    private fun bind(request: Long, kind: HerdrSheetKind, presentationId: Long, result: HerdrFileContext?) =
        when (result) {
            is HerdrFileContext.Ready -> {
                val bound = HerdrSheetCapture(
                    kind = kind,
                    scopeKey = result.scopeKey,
                    cwd = result.cwd.ifBlank(homeDir),
                    openId = newOpenId(),
                )
                prepare(kind, result.scopeKey)
                HerdrSheetState.Ready(bound, request, presentationId)
            }
            is HerdrFileContext.Unavailable -> HerdrSheetState.Error(kind, request, presentationId, result.message)
            null -> HerdrSheetState.Error(kind, request, presentationId, SUPERSEDED_MESSAGE)
        }

    private companion object {
        const val SUPERSEDED_MESSAGE = "Herdr focus changed while loading"
        const val UNREADABLE_MESSAGE = "Couldn't read the Herdr workspace"
    }
}
