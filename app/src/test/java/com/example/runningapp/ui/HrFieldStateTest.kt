package com.example.runningapp.ui

import com.example.runningapp.parseMaxHr
import com.example.runningapp.parseRestingHr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heart-rate field's whole job is to never lose a number quietly, so these are the cases where
 * it could: leaving without blurring, and leaving with something it cannot store.
 *
 * Exercised through Max HR, the field these rules were written for. The resting heart rate field
 * is the same object with a different [parseRestingHr] — the cases below are what makes that
 * sharing safe, and the ones at the end are what proves the two ranges stay distinct.
 */
class HrFieldStateTest {

    private var committed: Int? = null
    private val onCommit: (Int) -> Unit = { committed = it }

    @Test
    fun `a typed number is committed on the way out, without needing a blur first`() {
        // Back doesn't blur the field in touch mode. Dropping the value here is the silent
        // discard #112 exists to delete, moved one gesture along.
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
        state.onTyped("181")

        assertTrue(state.onLeaveAttempt(onCommit))
        assertEquals(181, committed)
    }

    @Test
    fun `leaving with an unusable entry is refused once, visibly`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
        state.onTyped("250")

        assertFalse(state.onLeaveAttempt(onCommit))
        assertTrue(state.refused)
        // Keeps what was typed rather than reverting to the stored number and saying nothing.
        assertEquals("250", state.typed)
        assertNull(committed)
    }

    @Test
    fun `a second attempt to leave goes through rather than trapping the runner`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
        state.onTyped("250")
        state.onLeaveAttempt(onCommit)

        assertTrue(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }

    @Test
    fun `fixing the entry after a refusal lets the first Back through again`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
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
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)

        assertTrue(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }

    @Test
    fun `blur refuses an unusable entry without clearing what was typed`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
        state.onTyped("99")

        state.onCommitAttempt(onCommit)

        assertTrue(state.refused)
        assertEquals("99", state.typed)
        assertNull(committed)
    }

    @Test
    fun `typing again clears the error rather than leaving it stale`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
        state.onTyped("99")
        state.onCommitAttempt(onCommit)

        state.onTyped("991")

        assertFalse(state.refused)
    }

    @Test
    fun `retyping the number it already holds still counts as a deliberate set`() {
        // That statement is exactly what the one-shot flag records.
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
        state.onTyped("190")

        state.onCommitAttempt(onCommit)

        assertEquals(190, committed)
    }

    @Test
    fun `a committed value is not committed a second time on the way out`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
        state.onTyped("181")
        state.onCommitAttempt(onCommit)
        committed = null

        assertTrue(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }

    // --- The same rules, wearing the resting heart rate's range (#172) ---

    @Test
    fun `the field refuses by its own range, not by Max HR's`() {
        // 60 is a fine resting heart rate and an impossible maximum. One field state, two ranges:
        // whatever the field is for is what decides, so neither number is judged by the other's.
        val resting = HrFieldState(stored = 0, parse = { parseRestingHr(it, maxHr = 190) })
        resting.onTyped("60")

        assertTrue(resting.onLeaveAttempt(onCommit))
        assertEquals(60, committed)

        committed = null
        val maxHr = HrFieldState(stored = 190, parse = ::parseMaxHr)
        maxHr.onTyped("60")

        assertFalse(maxHr.onLeaveAttempt(onCommit))
        assertNull(committed)
    }

    @Test
    fun `an unstated number shows an empty field rather than a zero`() {
        // Zero is how storage spells "nobody has said"; shown, it reads as a heart rate somebody
        // chose. Nothing is committed until something is typed, so the blank stays a blank.
        val state = HrFieldState(stored = 0, parse = { parseRestingHr(it, maxHr = 190) })

        assertEquals("", state.typed)
        assertTrue(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }
}
