package com.example.runningapp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Max HR field's whole job is to never lose a number quietly, so these are the cases where it
 * could: leaving without blurring, and leaving with something it cannot store.
 */
class MaxHrFieldStateTest {

    private var committed: Int? = null
    private val onCommit: (Int) -> Unit = { committed = it }

    @Test
    fun `a typed number is committed on the way out, without needing a blur first`() {
        // Back doesn't blur the field in touch mode. Dropping the value here is the silent
        // discard #112 exists to delete, moved one gesture along.
        val state = MaxHrFieldState(storedMaxHr = 190)
        state.onTyped("181")

        assertTrue(state.onLeaveAttempt(onCommit))
        assertEquals(181, committed)
    }

    @Test
    fun `leaving with an unusable entry is refused once, visibly`() {
        val state = MaxHrFieldState(storedMaxHr = 190)
        state.onTyped("250")

        assertFalse(state.onLeaveAttempt(onCommit))
        assertTrue(state.refused)
        // Keeps what was typed rather than reverting to the stored number and saying nothing.
        assertEquals("250", state.typed)
        assertNull(committed)
    }

    @Test
    fun `a second attempt to leave goes through rather than trapping the runner`() {
        val state = MaxHrFieldState(storedMaxHr = 190)
        state.onTyped("250")
        state.onLeaveAttempt(onCommit)

        assertTrue(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }

    @Test
    fun `fixing the entry after a refusal lets the first Back through again`() {
        val state = MaxHrFieldState(storedMaxHr = 190)
        state.onTyped("250")
        state.onLeaveAttempt(onCommit)
        state.onTyped("25")

        // Still unusable, so the count of refusals starts over rather than spending the one the
        // previous entry already used.
        assertFalse(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }

    @Test
    fun `leaving without touching the field commits nothing`() {
        // A visit must not spend the one-shot history recompute on the placeholder Max HR.
        val state = MaxHrFieldState(storedMaxHr = 190)

        assertTrue(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }

    @Test
    fun `blur refuses an unusable entry without clearing what was typed`() {
        val state = MaxHrFieldState(storedMaxHr = 190)
        state.onTyped("99")

        state.onCommitAttempt(onCommit)

        assertTrue(state.refused)
        assertEquals("99", state.typed)
        assertNull(committed)
    }

    @Test
    fun `typing again clears the error rather than leaving it stale`() {
        val state = MaxHrFieldState(storedMaxHr = 190)
        state.onTyped("99")
        state.onCommitAttempt(onCommit)

        state.onTyped("991")

        assertFalse(state.refused)
    }

    @Test
    fun `retyping the number it already holds still counts as a deliberate set`() {
        // That statement is exactly what the one-shot flag records.
        val state = MaxHrFieldState(storedMaxHr = 190)
        state.onTyped("190")

        state.onCommitAttempt(onCommit)

        assertEquals(190, committed)
    }

    @Test
    fun `a committed value is not committed a second time on the way out`() {
        val state = MaxHrFieldState(storedMaxHr = 190)
        state.onTyped("181")
        state.onCommitAttempt(onCommit)
        committed = null

        assertTrue(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }
}
