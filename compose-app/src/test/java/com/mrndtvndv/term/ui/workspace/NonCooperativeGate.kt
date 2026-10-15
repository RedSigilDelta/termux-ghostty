package com.mrndtvndv.term.ui.workspace

import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A suspension point that ignores cancellation, like blocking native work. [await] only returns once [complete] or
 * [release] resumes it, even if the awaiting job was cancelled meanwhile; it then returns the value normally.
 */
internal class NonCooperativeGate<T>(private val fallback: T) {
    private var waiter: Continuation<T>? = null
    private var result: Result<T>? = null

    /** Set once [await] returned. */
    var returned = false
        private set

    /** Whether the awaiting job was already cancelled when [await] returned. */
    var returnedAfterCancellation = false
        private set

    val waiting: Boolean get() = waiter != null

    suspend fun await(): T {
        val value = suspendCoroutine<T> { continuation ->
            val done = result
            if (done != null) continuation.resumeWith(done) else waiter = continuation
        }
        returnedAfterCancellation = currentCoroutineContext()[Job]?.isCancelled == true
        returned = true
        return value
    }

    fun complete(value: T) {
        if (result != null) return
        result = Result.success(value)
        waiter?.resumeWith(Result.success(value))
        waiter = null
    }

    /** Teardown: resumes a still-suspended [await] with the fallback. */
    fun release() = complete(fallback)
}

/** Runs [body], then releases every gate [gates] lists and lets the resumed work finish before runTest ends. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun gatedTest(gates: () -> List<NonCooperativeGate<*>>, body: suspend TestScope.() -> Unit) = runTest {
    try {
        body()
    } finally {
        testScheduler.runCurrent()
        var pending = gates().filter { it.waiting }
        while (pending.isNotEmpty()) {
            pending.forEach { it.release() }
            testScheduler.runCurrent()
            // A queued restart may have resumed into a new capture during this drain.
            pending = gates().filter { it.waiting }
        }
    }
}
