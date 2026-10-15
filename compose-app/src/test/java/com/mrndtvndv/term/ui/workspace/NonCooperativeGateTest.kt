package com.mrndtvndv.term.ui.workspace

import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonCooperativeGateTest {
    @Test
    fun teardownDrainsWorkThatWasQueuedWhenTheTestBodyEnded() {
        val gates = mutableListOf<NonCooperativeGate<Unit>>()
        gatedTest({ gates }) {
            backgroundScope.launch {
                NonCooperativeGate(Unit).also { gates += it }.await()
            }
        }
        assertEquals(1, gates.size)
        assertTrue(gates.single().returned)
        assertTrue(gates.none { it.waiting })
    }

    @Test
    fun teardownAlsoDrainsCaptureStartedByAReleasedRestart() {
        val gates = mutableListOf<NonCooperativeGate<Unit>>()
        gatedTest({ gates }) {
            backgroundScope.launch {
                NonCooperativeGate(Unit).also { gates += it }.await()
                NonCooperativeGate(Unit).also { gates += it }.await()
            }
        }
        assertEquals(2, gates.size)
        assertTrue(gates.all { it.returned })
        assertTrue(gates.none { it.waiting })
    }
}
