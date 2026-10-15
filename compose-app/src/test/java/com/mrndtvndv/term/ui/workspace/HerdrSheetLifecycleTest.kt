package com.mrndtvndv.term.ui.workspace

import com.mrndtvndv.term.workspace.HerdrFileContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HerdrSheetLifecycleTest {
    private val allGates = mutableListOf<NonCooperativeGate<*>>()

    private fun gated(body: suspend TestScope.() -> Unit) = gatedTest({ allGates }, body)

    private inner class Harness(
        val scope: TestScope,
        private val onPrepare: (HerdrSheetKind, String) -> Unit = { _, _ -> },
        private val home: () -> String = { "/home/user" },
        val restart: suspend () -> Unit = {},
    ) {
        val capturesStarted = mutableListOf<NonCooperativeGate<HerdrFileContext?>>()
        val prepared = mutableListOf<String>()
        lateinit var requests: HerdrSheetRequests

        fun build(scope: kotlinx.coroutines.CoroutineScope = this.scope.backgroundScope): Harness {
            requests = HerdrSheetRequests(
                scope = scope,
                // Ignores cancellation, like non-cooperative native work.
                capture = {
                    val gate = NonCooperativeGate<HerdrFileContext?>(null).also {
                        capturesStarted += it
                        allGates += it
                    }
                    gate.await()
                },
                restartControl = restart,
                prepare = { kind, key ->
                    prepared += key
                    onPrepare(kind, key)
                },
                homeDir = home,
                newOpenId = { "open" },
            )
            return this
        }
    }

    private fun ready(key: String, cwd: String = "/work") =
        HerdrFileContext.Ready(scopeKey = key, label = key, cwd = cwd, stale = false)

    @Test
    fun presentationIdIsStableThroughPhasesAndRetryButNewPerOpen() = gated {
        val h = Harness(this).build()
        h.requests.open(HerdrSheetKind.Git)
        val first = h.requests.state.value!!
        runCurrent()
        h.capturesStarted[0].complete(HerdrFileContext.Unavailable("down"))
        runCurrent()
        val error = h.requests.state.value!!
        assertEquals(first.presentationId, error.presentationId)
        h.requests.retry(error.requestId)
        runCurrent()
        val retrying = h.requests.state.value!!
        assertEquals(first.presentationId, retrying.presentationId)
        h.capturesStarted[1].complete(ready("a"))
        runCurrent()
        val readyState = h.requests.state.value as HerdrSheetState.Ready
        assertEquals(first.presentationId, readyState.presentationId)

        // Close and reopen without any frame in between.
        h.requests.dismiss(readyState.requestId)
        h.requests.open(HerdrSheetKind.Git)
        assertNotEquals(first.presentationId, h.requests.state.value!!.presentationId)
    }

    @Test
    fun staleRetryAndDismissIdsAreIgnored() = gated {
        val h = Harness(this).build()
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val oldId = h.requests.state.value!!.requestId
        h.capturesStarted[0].complete(HerdrFileContext.Unavailable("down"))
        runCurrent()
        h.requests.dismiss(oldId)
        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        val current = h.requests.state.value
        h.capturesStarted[1].complete(HerdrFileContext.Unavailable("down again"))
        runCurrent()
        val currentError = h.requests.state.value as HerdrSheetState.Error

        h.requests.retry(oldId)
        h.requests.dismiss(oldId)
        assertFalse(h.requests.beginDismiss(oldId))
        assertEquals(currentError, h.requests.state.value)
        assertEquals(2, h.capturesStarted.size)
        assertEquals(HerdrSheetKind.Sftp, current?.kind)
    }

    @Test
    fun beginDismissCancelsPendingCaptureButKeepsPresentation() = gated {
        val h = Harness(this).build()
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val id = h.requests.state.value!!.requestId

        assertTrue(h.requests.beginDismiss(id))
        val exiting = h.requests.state.value as HerdrSheetState.Opening
        assertTrue(exiting.exiting)
        // A second begin (double back, swipe plus back) does not start another hide.
        assertFalse(h.requests.beginDismiss(id))

        h.capturesStarted[0].complete(ready("late"))
        runCurrent()
        assertTrue(h.capturesStarted[0].returned && h.capturesStarted[0].returnedAfterCancellation)
        assertTrue("Late capture must not prepare Git", h.prepared.isEmpty())
        assertEquals(exiting, h.requests.state.value)

        h.requests.dismiss(id)
        assertNull(h.requests.state.value)
    }

    @Test
    fun retryIsIgnoredWhileExiting() = gated {
        val h = Harness(this).build()
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        h.capturesStarted[0].complete(HerdrFileContext.Unavailable("down"))
        runCurrent()
        val id = h.requests.state.value!!.requestId
        assertTrue(h.requests.beginDismiss(id))
        h.requests.retry(id)
        runCurrent()
        assertTrue((h.requests.state.value as HerdrSheetState.Error).exiting)
        assertEquals(1, h.capturesStarted.size)
    }

    @Test
    fun cancelledHideDoesNotFinishDismissal() {
        var finished = 0
        finishHideIfCompleted(CancellationException("cancelled"), presentationShown = true) { finished++ }
        finishHideIfCompleted(null, presentationShown = false) { finished++ }
        assertEquals(0, finished)
        finishHideIfCompleted(null, presentationShown = true) { finished++ }
        assertEquals(1, finished)
    }

    @Test
    fun finishingOldHideCannotDismissNewerSheet() = gated {
        val h = Harness(this).build()
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val oldId = h.requests.state.value!!.requestId
        assertTrue(h.requests.beginDismiss(oldId))
        h.requests.dismiss(oldId)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val newer = h.requests.state.value
        // The old modal's hide completes late.
        finishHideIfCompleted(null, presentationShown = true) { h.requests.dismiss(oldId) }
        assertEquals(newer, h.requests.state.value)
        assertFalse(h.requests.beginDismiss(oldId))
    }

    @Test
    fun throwingPrepareBecomesInlineError() = gated {
        val h = Harness(this, onPrepare = { _, _ -> error("prepare failed") }).build()
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        h.capturesStarted[0].complete(ready("a"))
        runCurrent()
        assertEquals("prepare failed", (h.requests.state.value as HerdrSheetState.Error).message)
    }

    @Test
    fun blankCwdFallbackFailureBecomesErrorWithoutPreparing() = gated {
        val h = Harness(this, home = { error("no home") }).build()
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        h.capturesStarted[0].complete(ready("a", cwd = ""))
        runCurrent()
        assertEquals("no home", (h.requests.state.value as HerdrSheetState.Error).message)
        assertTrue(h.prepared.isEmpty())
    }

    @Test
    fun throwingRestartPublishesErrorForCurrentRequestWithoutCapturing() = gated {
        val h = Harness(this, restart = { error("restart failed") }).build()
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        h.capturesStarted[0].complete(HerdrFileContext.Unavailable("down"))
        runCurrent()
        val error = h.requests.state.value as HerdrSheetState.Error

        h.requests.retry(error.requestId)
        assertTrue(h.requests.state.value is HerdrSheetState.Opening)
        runCurrent()
        val failed = h.requests.state.value as HerdrSheetState.Error
        assertEquals("restart failed", failed.message)
        assertNotEquals(error.requestId, failed.requestId)
        assertEquals(1, h.capturesStarted.size)
    }

    @Test
    fun restartGatesCaptureAndOpeningIsAlreadyVisible() = gated {
        val restartGate = CompletableDeferred<Unit>()
        val h = Harness(this, restart = { restartGate.await() }).build()
        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        h.capturesStarted[0].complete(HerdrFileContext.Unavailable("down"))
        runCurrent()
        h.requests.retry(h.requests.state.value!!.requestId)
        runCurrent()
        assertTrue(h.requests.state.value is HerdrSheetState.Opening)
        assertEquals("Capture waits for the restart", 1, h.capturesStarted.size)

        restartGate.complete(Unit)
        runCurrent()
        assertEquals(2, h.capturesStarted.size)
        h.capturesStarted[1].complete(ready("b"))
        runCurrent()
        assertTrue(h.requests.state.value is HerdrSheetState.Ready)
    }

    @Test
    fun dismissDuringRestartNeverCaptures() = gated {
        val restartGate = NonCooperativeGate(Unit).also { allGates += it }
        val h = Harness(this, restart = { restartGate.await() }).build()
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        h.capturesStarted[0].complete(HerdrFileContext.Unavailable("down"))
        runCurrent()
        val error = h.requests.state.value!!
        h.requests.retry(error.requestId)
        runCurrent()
        assertTrue(h.requests.beginDismiss(h.requests.state.value!!.requestId))
        restartGate.complete(Unit)
        runCurrent()
        assertTrue("Restart returned after the dismissal", restartGate.returnedAfterCancellation)
        assertEquals(1, h.capturesStarted.size)
        assertTrue(h.prepared.isEmpty())
    }

    @Test
    fun parentScopeCancellationIsInertEvenIfCaptureIgnoresIt() = gated {
        val parent = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val h = Harness(this).build(parent)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val opening = h.requests.state.value
        parent.cancel()
        h.capturesStarted[0].complete(ready("a"))
        runCurrent()
        assertTrue(h.capturesStarted[0].returnedAfterCancellation)
        assertTrue(h.prepared.isEmpty())
        assertEquals(opening, h.requests.state.value)
    }

    @Test
    fun reentrantCancelDuringPrepareCannotResurrectSheet() = gated {
        lateinit var h: Harness
        h = Harness(this, onPrepare = { _, _ -> h.requests.cancel() }).build()
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        h.capturesStarted[0].complete(ready("a"))
        runCurrent()
        assertNull(h.requests.state.value)
    }
}
