package com.example.runningapp.ui

import com.example.runningapp.RESTING_HR_UNSTATED
import com.example.runningapp.parseMaxHr
import com.example.runningapp.parseRestingHr
import com.example.runningapp.parseRestingHrAlone
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

    @Test
    fun `a moved range judges the entry without discarding it`() {
        // The resting field's ceiling depends on the Max HR beside it, and a Max HR commit can
        // land seconds later — after its re-tally of history — which is exactly when the second
        // number is being typed. Replacing the rule keeps the entry and applies the new range.
        val state = HrFieldState(stored = 0, parse = { parseRestingHr(it, maxHr = 190) })
        state.onTyped("90")

        state.parse = { parseRestingHr(it, maxHr = 100) }

        assertEquals("90", state.typed)
        assertFalse(state.onLeaveAttempt(onCommit))
        assertTrue(state.refused)
        assertNull(committed)
    }

    // --- The pair judged against each other, not against disk (#172) ---

    @Test
    fun `the number in force is the pending entry once it can be stored`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
        state.onTyped("100")

        assertEquals(100, state.valueInForce)
    }

    @Test
    fun `a half-typed entry leaves the stored number in force`() {
        // "1" on the way to "100" is not a maximum of 1, and the field beside it must not be
        // re-judged against one.
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
        state.onTyped("1")

        assertEquals(190, state.valueInForce)
    }

    @Test
    fun `a resting hr the pending max cannot hold is refused, not accepted and clamped`() {
        // Lowering Max HR to 100 and stating a resting 90 in the same visit. Judged against the
        // Max HR still on disk, both are accepted here and storage quietly holds 50 — the runner
        // is shown back a number they never typed, which is the failure this screen deletes.
        val maxHr = HrFieldState(stored = 190, parse = ::parseMaxHr)
        maxHr.onTyped("100")
        val resting = HrFieldState(
            stored = RESTING_HR_UNSTATED,
            parse = { parseRestingHr(it, maxHr.valueInForce) },
            blankMeans = RESTING_HR_UNSTATED
        )
        resting.onTyped("90")

        assertFalse(resting.onLeaveAttempt(onCommit))
        assertTrue(resting.refused)
        assertNull(committed)
    }

    @Test
    fun `a max hr with no room above the stated resting hr is refused, not accepted`() {
        // The mirror of the case above, through the other door. Accepting it is what used to
        // rewrite the runner's stated 60 down to 50 without a word.
        val state = HrFieldState(stored = 190, parse = { parseMaxHr(it, restingHr = 60) })
        state.onTyped("100")

        assertFalse(state.onLeaveAttempt(onCommit))
        assertTrue(state.refused)
        assertNull(committed)
    }

    @Test
    fun `a resting hr already blurred still blocks a max hr with no room above it`() {
        // Blur commits, but the commit is asynchronous and waits on a re-tally of the whole
        // history before it publishes — so storage still reads the old number when the maximum is
        // typed a moment later. Judged against disk this pair is accepted, and one of the two
        // numbers is then quietly rewritten. What the field is holding is the honest answer.
        val resting = HrFieldState(
            stored = RESTING_HR_UNSTATED,
            parse = { parseRestingHr(it, maxHr = 190) },
            blankMeans = RESTING_HR_UNSTATED,
            readAlone = ::parseRestingHrAlone
        )
        resting.onTyped("60")
        resting.onCommitAttempt { }

        val maxHr = HrFieldState(stored = 190, parse = { parseMaxHr(it, resting.valueInForce) })
        maxHr.onTyped("100")

        assertEquals(60, resting.valueInForce)
        assertFalse(maxHr.onLeaveAttempt(onCommit))
        assertTrue(maxHr.refused)
        assertNull(committed)
    }

    @Test
    fun `neither field's rule recurses into the other`() {
        // Each names the other, so each must answer "what are you holding" without going back
        // through the rule that asked. Both entries are unusable as a pair; resolving either one
        // must terminate rather than bounce between them.
        val maxHr = HrFieldState(stored = 190, parse = ::parseMaxHr, readAlone = ::parseMaxHr)
        val resting = HrFieldState(
            stored = 60,
            parse = { parseRestingHr(it, maxHr = 190) },
            blankMeans = RESTING_HR_UNSTATED,
            readAlone = ::parseRestingHrAlone
        )
        maxHr.parse = { parseMaxHr(it, resting.valueInForce) }
        resting.parse = { parseRestingHr(it, maxHr.valueInForce) }
        maxHr.onTyped("100")
        resting.onTyped("90")

        assertEquals(100, maxHr.valueInForce)
        assertEquals(90, resting.valueInForce)
        assertFalse(maxHr.onLeaveAttempt(onCommit))
        assertFalse(resting.onLeaveAttempt(onCommit))
    }

    @Test
    fun `the same max hr is accepted once nothing is stated above it`() {
        val state = HrFieldState(stored = 190, parse = { parseMaxHr(it, RESTING_HR_UNSTATED) })
        state.onTyped("100")

        assertTrue(state.onLeaveAttempt(onCommit))
        assertEquals(100, committed)
    }

    // --- The way back to unstated (#172) ---

    @Test
    fun `emptying a field that has an unstated state commits the unstated value`() {
        // Without this the only way out of a stated measurement is another measurement.
        val state = HrFieldState(
            stored = 60,
            parse = { parseRestingHr(it, maxHr = 190) },
            blankMeans = RESTING_HR_UNSTATED
        )
        state.onTyped("")

        assertTrue(state.onLeaveAttempt(onCommit))
        assertEquals(RESTING_HR_UNSTATED, committed)
        assertFalse(state.refused)
    }

    @Test
    fun `emptying a field with no unstated state is still refused`() {
        // There is no such thing as not having a Max HR, so blank stays the same mistake as "abc".
        val state = HrFieldState(stored = 190, parse = ::parseMaxHr)
        state.onTyped("")

        assertFalse(state.onLeaveAttempt(onCommit))
        assertTrue(state.refused)
        assertNull(committed)
    }

    @Test
    fun `declining the clear puts the number still in force back in the field`() {
        // Otherwise the blank stays pending and the screen asks again on every way out.
        val state = HrFieldState(
            stored = 60,
            parse = { parseRestingHr(it, maxHr = 190) },
            blankMeans = RESTING_HR_UNSTATED
        )
        state.onTyped("")
        state.onLeaveAttempt(onCommit)
        committed = null

        state.restore()

        assertEquals("60", state.typed)
        assertTrue(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }

    @Test
    fun `blank is only unstated when it is blank, not whenever parsing fails`() {
        // A half-typed or nonsense entry is a mistake to refuse, not a withdrawal to act on.
        val state = HrFieldState(
            stored = 60,
            parse = { parseRestingHr(it, maxHr = 190) },
            blankMeans = RESTING_HR_UNSTATED
        )
        state.onTyped("6")

        assertFalse(state.onLeaveAttempt(onCommit))
        assertTrue(state.refused)
        assertNull(committed)
    }
}
