package com.mrndtvndv.term.ui.workspace

import com.mrndtvndv.term.workspace.HerdrFileContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HerdrSheetRequestsTest {
    private val allGates = mutableListOf<NonCooperativeGate<*>>()

    private fun gated(body: suspend TestScope.() -> Unit) = gatedTest({ allGates }, body)

    private inner class Harness(scope: TestScope) {
        val gates = mutableListOf<NonCooperativeGate<HerdrFileContext?>>()
        var restarts = 0
        val prepared = mutableListOf<Pair<HerdrSheetKind, String>>()
        private var openIds = 0

        /** Each capture waits on its own gate and ignores cancellation, returning even after its job was cancelled. */
        val requests = HerdrSheetRequests(
            scope = scope.backgroundScope,
            capture = {
                val gate = NonCooperativeGate<HerdrFileContext?>(null).also {
                    gates += it
                    allGates += it
                }
                gate.await()
            },
            restartControl = { restarts++ },
            prepare = { kind, scopeKey -> prepared += kind to scopeKey },
            homeDir = { "/home/user" },
            newOpenId = { "open-${++openIds}" },
        )
    }

    private fun ready(scopeKey: String, cwd: String = "/work") =
        HerdrFileContext.Ready(scopeKey = scopeKey, label = scopeKey, cwd = cwd, stale = false)

    @Test
    fun opensImmediatelyBeforeCaptureCompletes() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Git)
        // Published synchronously, before the capture ran or finished.
        val opening = h.requests.state.value as HerdrSheetState.Opening
        assertEquals(HerdrSheetKind.Git, opening.kind)
        runCurrent()
        assertEquals(1, h.gates.size)
        assertTrue(h.requests.state.value is HerdrSheetState.Opening)
        assertTrue(h.prepared.isEmpty())
    }

    @Test
    fun readyBindsCaptureOnceToItsRequest() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        h.gates[0].complete(ready("scope-a", cwd = ""))
        runCurrent()
        val state = h.requests.state.value as HerdrSheetState.Ready
        assertEquals("scope-a", state.capture.scopeKey)
        assertEquals("/home/user", state.capture.cwd)
        assertEquals("open-1", state.capture.openId)
        assertEquals(1, h.prepared.size)
        // A second open while one is up neither captures again nor rebinds.
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        assertEquals(1, h.gates.size)
        assertEquals(state, h.requests.state.value)
    }

    @Test
    fun gitPrepareRunsOnlyForAcceptedCapture() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        h.requests.cancel()
        h.gates[0].complete(ready("scope-a"))
        runCurrent()
        assertTrue("A cancelled capture must not clear a Git store", h.prepared.isEmpty())
        assertNull(h.requests.state.value)
    }

    @Test
    fun unavailableShowsErrorInPlaceAndRetryRecaptures() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        h.gates[0].complete(HerdrFileContext.Unavailable("Herdr is not connected"))
        runCurrent()
        val error = h.requests.state.value as HerdrSheetState.Error
        assertEquals("Herdr is not connected", error.message)
        assertEquals(0, h.restarts)

        h.requests.retry(error.requestId)
        // New request, same presentation, back to Opening without ever leaving a non-null state.
        val opening = h.requests.state.value as HerdrSheetState.Opening
        assertEquals(HerdrSheetKind.Git, opening.kind)
        assertTrue(opening.requestId != error.requestId)
        assertEquals(error.presentationId, opening.presentationId)
        runCurrent()
        assertEquals(1, h.restarts)
        h.gates[1].complete(ready("scope-b"))
        runCurrent()
        val state = h.requests.state.value as HerdrSheetState.Ready
        assertEquals("scope-b", state.capture.scopeKey)
        assertEquals(listOf(HerdrSheetKind.Git to "scope-b"), h.prepared)
    }

    @Test
    fun retryIsIgnoredOutsideError() = gated {
        val h = Harness(this)
        h.requests.retry(1)
        assertNull(h.requests.state.value)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        h.requests.retry(h.requests.state.value!!.requestId)
        assertEquals(0, h.restarts)
        assertEquals(1, h.gates.size)
    }

    @Test
    fun supersededCaptureShowsRetryableError() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        h.gates[0].complete(null)
        runCurrent()
        val error = h.requests.state.value as HerdrSheetState.Error
        assertEquals(HerdrSheetKind.Sftp, error.kind)
        assertTrue(error.message.isNotBlank())
        assertTrue(h.prepared.isEmpty())
    }

    @Test
    fun failingCaptureBecomesError() = gated {
        val requests = HerdrSheetRequests(
            scope = backgroundScope,
            capture = { error("boom") },
            restartControl = {},
            prepare = { _, _ -> },
            homeDir = { "/" },
            newOpenId = { "x" },
        )
        requests.open(HerdrSheetKind.Git)
        runCurrent()
        assertEquals("boom", (requests.state.value as HerdrSheetState.Error).message)
    }

    @Test
    fun lateSuccessAfterDismissIsInert() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val id = h.requests.state.value!!.requestId
        h.requests.dismiss(id)
        assertNull(h.requests.state.value)
        h.gates[0].complete(ready("scope-a"))
        runCurrent()
        assertTrue("Capture must have returned its Ready", h.gates[0].returned)
        assertTrue("...after the request was cancelled", h.gates[0].returnedAfterCancellation)
        assertNull(h.requests.state.value)
        assertTrue(h.prepared.isEmpty())
    }

    @Test
    fun lateErrorAfterDismissIsInert() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        h.requests.dismiss(h.requests.state.value!!.requestId)
        h.gates[0].complete(HerdrFileContext.Unavailable("late"))
        runCurrent()
        assertTrue(h.gates[0].returned && h.gates[0].returnedAfterCancellation)
        assertNull(h.requests.state.value)
    }

    @Test
    fun lateResultOfOlderRequestCannotTouchNewerSheet() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val oldId = h.requests.state.value!!.requestId
        h.requests.dismiss(oldId)
        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        val newOpening = h.requests.state.value as HerdrSheetState.Opening

        // The first request finishes late, ignoring cancellation.
        h.gates[0].complete(ready("old-scope"))
        runCurrent()
        assertTrue(h.gates[0].returned && h.gates[0].returnedAfterCancellation)
        assertEquals(newOpening, h.requests.state.value)
        assertTrue(h.prepared.isEmpty())

        h.gates[1].complete(ready("new-scope"))
        runCurrent()
        assertEquals("new-scope", (h.requests.state.value as HerdrSheetState.Ready).capture.scopeKey)
        assertEquals(listOf(HerdrSheetKind.Sftp to "new-scope"), h.prepared)
    }

    @Test
    fun staleDismissDoesNotCloseNewerSheet() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val oldId = h.requests.state.value!!.requestId
        h.requests.dismiss(oldId)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val current = h.requests.state.value
        // An old hide animation or download callback completing now.
        h.requests.dismiss(oldId)
        assertEquals(current, h.requests.state.value)
    }

    @Test
    fun staleDismissDoesNotCloseRetriedSheet() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val firstId = h.requests.state.value!!.requestId
        h.gates[0].complete(HerdrFileContext.Unavailable("down"))
        runCurrent()
        h.requests.retry(firstId)
        runCurrent()
        h.requests.dismiss(firstId)
        assertTrue(h.requests.state.value is HerdrSheetState.Opening)
    }

    @Test
    fun resultOfFailedAttemptCannotOverrideRetry() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        h.gates[0].complete(HerdrFileContext.Unavailable("down"))
        runCurrent()
        h.requests.retry(h.requests.state.value!!.requestId)
        runCurrent()
        // The retry's capture is the only one that may bind.
        h.gates[1].complete(ready("scope-r"))
        runCurrent()
        assertEquals("scope-r", (h.requests.state.value as HerdrSheetState.Ready).capture.scopeKey)
    }

    @Test
    fun cancelDropsOpeningForAgents() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        h.requests.cancel()
        assertNull(h.requests.state.value)
        h.gates[0].complete(ready("scope-a"))
        runCurrent()
        assertTrue(h.gates[0].returned && h.gates[0].returnedAfterCancellation)
        assertNull(h.requests.state.value)
    }

    @Test
    fun lateReadyAfterBeginDismissIsInertAndKeepsExitingSheet() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        val id = h.requests.state.value!!.requestId
        assertTrue(h.requests.beginDismiss(id))
        val exiting = h.requests.state.value
        h.gates[0].complete(ready("scope-a"))
        runCurrent()
        assertTrue(h.gates[0].returned && h.gates[0].returnedAfterCancellation)
        assertEquals(exiting, h.requests.state.value)
        assertTrue(h.prepared.isEmpty())
    }

    @Test
    fun lateErrorAfterBeginDismissKeepsExitingOpening() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Git)
        runCurrent()
        assertTrue(h.requests.beginDismiss(h.requests.state.value!!.requestId))
        val exiting = h.requests.state.value
        h.gates[0].complete(HerdrFileContext.Unavailable("late"))
        runCurrent()
        assertTrue(h.gates[0].returnedAfterCancellation)
        assertEquals(exiting, h.requests.state.value)
    }

    @Test
    fun inlineErrorStillShowsForLiveRequest() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        h.gates[0].complete(HerdrFileContext.Unavailable("down"))
        runCurrent()
        assertFalse(h.gates[0].returnedAfterCancellation)
        val error = h.requests.state.value as HerdrSheetState.Error
        assertEquals("down", error.message)
        assertFalse(error.exiting)
    }

    @Test
    fun previewSelectionEligibility() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        val openingId = h.requests.state.value!!.requestId
        assertFalse("Not Ready yet", h.requests.canSelectPreview(openingId, "scope-a"))
        h.gates[0].complete(ready("scope-a"))
        runCurrent()
        val id = h.requests.state.value!!.requestId

        assertTrue("Before hide", h.requests.canSelectPreview(id, "scope-a"))
        assertFalse("Wrong scope", h.requests.canSelectPreview(id, "scope-b"))
        assertFalse("Stale request", h.requests.canSelectPreview(id - 1, "scope-a"))

        assertTrue(h.requests.beginDismiss(id))
        assertFalse("After beginDismiss", h.requests.canSelectPreview(id, "scope-a"))

        h.requests.dismiss(id)
        assertFalse("After dismiss", h.requests.canSelectPreview(id, "scope-a"))

        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        h.gates[1].complete(ready("scope-a"))
        runCurrent()
        val reopened = h.requests.state.value!!.requestId
        assertTrue(reopened != id)
        assertFalse("Old request after reopen", h.requests.canSelectPreview(id, "scope-a"))
        assertTrue("Reopened sheet", h.requests.canSelectPreview(reopened, "scope-a"))
    }

    @Test
    fun dismissingReadyClearsState() = gated {
        val h = Harness(this)
        h.requests.open(HerdrSheetKind.Sftp)
        runCurrent()
        h.gates[0].complete(ready("scope-a"))
        runCurrent()
        h.requests.dismiss((h.requests.state.value as HerdrSheetState.Ready).requestId)
        assertNull(h.requests.state.value)
    }
}
