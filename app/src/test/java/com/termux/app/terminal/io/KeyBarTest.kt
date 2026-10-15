package com.termux.app.terminal.io

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBarTest {

    @Test
    fun holdDirectionIsNullInsideDeadZone() {
        assertNull(joystickDirection(0f, 0f, 10f))
        assertNull(joystickDirection(6f, 6f, 10f))
    }

    @Test
    fun holdDirectionFollowsDominantAxis() {
        assertEquals(ArrowDirection.RIGHT, joystickDirection(20f, 5f, 10f))
        assertEquals(ArrowDirection.LEFT, joystickDirection(-20f, 5f, 10f))
        assertEquals(ArrowDirection.DOWN, joystickDirection(5f, 20f, 10f))
        assertEquals(ArrowDirection.UP, joystickDirection(5f, -20f, 10f))
    }

    @Test
    fun holdDiagonalTieGoesHorizontal() {
        assertEquals(ArrowDirection.RIGHT, joystickDirection(20f, 20f, 10f))
        assertEquals(ArrowDirection.LEFT, joystickDirection(-20f, -20f, 10f))
    }

    @Test
    fun repeatDelayStartsAt350ThenAccelerates() {
        assertEquals(350L, repeatDelayMillis(0))
        assertEquals(120L, repeatDelayMillis(1))
        assertTrue(repeatDelayMillis(2) < repeatDelayMillis(1))
        (1..20).zipWithNext().forEach { (previous, next) ->
            assertTrue(repeatDelayMillis(next) <= repeatDelayMillis(previous))
        }
    }

    @Test
    fun repeatDelayFloorsAt40Millis() {
        assertEquals(40L, repeatDelayMillis(100))
    }

    private fun tracker() = ArrowDragTracker(10f).apply { start(100f, 100f) }

    @Test
    fun verticalStepsAreLongerThanHorizontal() {
        val tracker = ArrowDragTracker(10f, 30f).apply { start(100f, 100f) }

        assertNull(tracker.move(100f, 120f))
        assertEquals(ArrowSteps(ArrowDirection.DOWN, 1), tracker.move(100f, 130f))
        assertEquals(ArrowSteps(ArrowDirection.RIGHT, 1), ArrowDragTracker(10f, 30f).apply { start(0f, 0f) }.move(10f, 0f))
    }

    @Test
    fun pulledUntilFingerReturnsWithinHalfStepOfWhereItWentDown() {
        val tracker = ArrowDragTracker(10f, 30f).apply { start(100f, 100f) }

        assertTrue(tracker.isPulled(ArrowDirection.UP, 100f, 80f))
        assertTrue(tracker.isPulled(ArrowDirection.UP, 100f, 85f))
        assertFalse(tracker.isPulled(ArrowDirection.UP, 100f, 90f))
        assertTrue(tracker.isPulled(ArrowDirection.RIGHT, 105f, 100f))
        assertFalse(tracker.isPulled(ArrowDirection.RIGHT, 104f, 100f))
        assertFalse(tracker.isPulled(ArrowDirection.DOWN, 100f, 80f))
    }

    @Test
    fun dragEmitsNothingBeforeOneFullStep() {
        assertNull(tracker().move(109f, 100f))
    }

    @Test
    fun dragEmitsOneArrowPerStepInDragDirection() {
        assertEquals(ArrowSteps(ArrowDirection.RIGHT, 2), tracker().move(125f, 100f))
        assertEquals(ArrowSteps(ArrowDirection.LEFT, 1), tracker().move(90f, 100f))
        assertEquals(ArrowSteps(ArrowDirection.DOWN, 3), tracker().move(100f, 130f))
        assertEquals(ArrowSteps(ArrowDirection.UP, 1), tracker().move(100f, 90f))
    }

    @Test
    fun dragCarriesRemainderBetweenMoves() {
        val tracker = tracker()

        assertEquals(ArrowSteps(ArrowDirection.RIGHT, 2), tracker.move(125f, 100f))
        assertNull(tracker.move(128f, 100f))
        assertEquals(ArrowSteps(ArrowDirection.RIGHT, 1), tracker.move(130f, 100f))
    }

    @Test
    fun dragPicksDominantAxisAndTieGoesHorizontal() {
        assertEquals(ArrowSteps(ArrowDirection.DOWN, 1), tracker().move(103f, 111f))
        assertEquals(ArrowSteps(ArrowDirection.RIGHT, 1), tracker().move(111f, 111f))
    }

    @Test
    fun firstStepLocksAxisUntilFingerLifts() {
        val tracker = tracker()

        assertEquals(ArrowSteps(ArrowDirection.RIGHT, 1), tracker.move(110f, 100f))
        assertEquals(true, tracker.lockedHorizontal)
        assertNull(tracker.move(110f, 150f))

        tracker.start(0f, 0f)
        assertNull(tracker.lockedHorizontal)
        assertEquals(ArrowSteps(ArrowDirection.DOWN, 1), tracker.move(0f, 10f))
    }

    @Test
    fun reversingNeedsAFreshStepFromLastEmitPoint() {
        val tracker = tracker()

        tracker.move(120f, 100f)
        assertNull(tracker.move(115f, 100f))
        assertEquals(ArrowSteps(ArrowDirection.LEFT, 1), tracker.move(110f, 100f))
    }

    @Test
    fun ctrlTapArmsThenCancelsWithoutLocking() {
        val keys = ModifierKeys()

        keys.tapCtrl()
        assertEquals(KeyModifierState.ARMED, keys.ctrl)
        keys.tapCtrl()
        assertEquals(KeyModifierState.OFF, keys.ctrl)

        keys.tapAlt()
        assertEquals(KeyModifierState.ARMED, keys.alt)
    }

    @Test
    fun holdingCtrlLocksUntilTappedAgain() {
        val keys = ModifierKeys()

        keys.holdCtrl()
        assertEquals(KeyModifierState.LOCKED, keys.ctrl)
        assertTrue(keys.readCtrl())
        keys.clearConsumed()
        assertEquals(KeyModifierState.LOCKED, keys.ctrl)

        keys.tapCtrl()
        assertEquals(KeyModifierState.OFF, keys.ctrl)
    }

    @Test
    fun readDoesNotChangeStateUntilClearConsumed() {
        val keys = ModifierKeys()
        keys.tapCtrl()

        assertTrue(keys.readCtrl())
        assertEquals(KeyModifierState.ARMED, keys.ctrl)

        keys.clearConsumed()
        assertEquals(KeyModifierState.OFF, keys.ctrl)
    }

    @Test
    fun armedModifierIsClearedAfterMetaState() {
        val keys = ModifierKeys()
        keys.tapCtrl()

        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, keys.metaState())
        assertEquals(KeyModifierState.OFF, keys.ctrl)
        assertEquals(0, keys.metaState())
    }

    @Test
    fun lockedModifierSurvivesMetaState() {
        val keys = ModifierKeys()
        keys.holdCtrl()

        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, keys.metaState())
        assertEquals(KeyModifierState.LOCKED, keys.ctrl)
        assertEquals(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, keys.metaState())
    }

    @Test
    fun metaStateCombinesCtrlAndAlt() {
        val keys = ModifierKeys()
        keys.tapCtrl()
        keys.tapAlt()

        assertEquals(
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON,
            keys.metaState(),
        )
    }
}
