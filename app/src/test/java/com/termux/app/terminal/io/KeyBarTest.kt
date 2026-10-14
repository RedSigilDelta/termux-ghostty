package com.termux.app.terminal.io

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyBarTest {

    @Test
    fun joystickDirectionIsNullInsideDeadZone() {
        assertNull(joystickDirection(0f, 0f, 10f))
        assertNull(joystickDirection(6f, 6f, 10f))
    }

    @Test
    fun joystickDirectionFollowsDominantAxis() {
        assertEquals(ArrowDirection.RIGHT, joystickDirection(20f, 5f, 10f))
        assertEquals(ArrowDirection.LEFT, joystickDirection(-20f, 5f, 10f))
        assertEquals(ArrowDirection.DOWN, joystickDirection(5f, 20f, 10f))
        assertEquals(ArrowDirection.UP, joystickDirection(5f, -20f, 10f))
    }

    @Test
    fun joystickDiagonalTieGoesHorizontal() {
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

    @Test
    fun modifierTapCyclesOffArmedLocked() {
        val keys = ModifierKeys()

        keys.tapCtrl()
        assertEquals(KeyModifierState.ARMED, keys.ctrl)
        keys.tapCtrl()
        assertEquals(KeyModifierState.LOCKED, keys.ctrl)
        keys.tapCtrl()
        assertEquals(KeyModifierState.OFF, keys.ctrl)

        keys.tapAlt()
        assertEquals(KeyModifierState.ARMED, keys.alt)
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
        keys.tapCtrl()
        keys.tapCtrl()

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
