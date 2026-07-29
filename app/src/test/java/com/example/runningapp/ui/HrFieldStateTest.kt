package com.example.runningapp.ui

import com.example.runningapp.RESTING_HR_UNSTATED
import com.example.runningapp.parseMaxHr
import com.example.runningapp.parseMaxHrAlone
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
        val state = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)
        state.onTyped("181")

        assertTrue(state.onLeaveAttempt(onCommit))
        assertEquals(181, committed)
    }

    @Test
    fun `leaving with an unusable entry is refused once, visibly`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)
        state.onTyped("250")

        assertFalse(state.onLeaveAttempt(onCommit))
        assertTrue(state.refused)
        // Keeps what was typed rather than reverting to the stored number and saying nothing.
        assertEquals("250", state.typed)
        assertNull(committed)
    }

    @Test
    fun `a second attempt to leave goes through rather than trapping the runner`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)
        state.onTyped("250")
        state.onLeaveAttempt(onCommit)

        assertTrue(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }

    @Test
    fun `fixing the entry after a refusal lets the first Back through again`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)
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
        val state = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)

        assertTrue(state.onLeaveAttempt(onCommit))
        assertNull(committed)
    }

    @Test
    fun `blur refuses an unusable entry without clearing what was typed`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)
        state.onTyped("99")

        state.onCommitAttempt(onCommit)

        assertTrue(state.refused)
        assertEquals("99", state.typed)
        assertNull(committed)
    }

    @Test
    fun `typing again clears the error rather than leaving it stale`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)
        state.onTyped("99")
        state.onCommitAttempt(onCommit)

        state.onTyped("991")

        assertFalse(state.refused)
    }

    @Test
    fun `retyping the number it already holds still counts as a deliberate set`() {
        // That statement is exactly what the one-shot flag records.
        val state = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)
        state.onTyped("190")

        state.onCommitAttempt(onCommit)

        assertEquals(190, committed)
    }

    @Test
    fun `a committed value is not committed a second time on the way out`() {
        val state = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)
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
        val maxHr = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)
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

    // --- A commit publishing while the field is being used again (#172) ---

    @Test
    fun `a commit landing while the runner is retyping does not replace what they typed`() {
        // A resting-HR commit re-tallies the whole history before DataStore publishes, which is a
        // long time to sit there — and exactly when someone refocuses to correct the number they
        // just entered. Showing the just-stored value now would drop the newer entry, which is the
        // silent discard this screen exists to delete arriving from the other direction.
        val state = HrFieldState(stored = 0, parse = { parseRestingHr(it, maxHr = 190) })
        state.onTyped("60")
        state.onCommitAttempt(onCommit)
        assertEquals(60, committed)
        state.onTyped("55")

        state.onStoredChanged(60)

        assertEquals("55", state.typed)
    }

    @Test
    fun `a commit landing with nothing pending is shown`() {
        // The ordinary case, and how an outside change reaches the field at all.
        val state = HrFieldState(stored = 0, parse = { parseRestingHr(it, maxHr = 190) })

        state.onStoredChanged(60)

        assertEquals("60", state.typed)
    }

    @Test
    fun `an outside change to unstated empties the field rather than showing a zero`() {
        val state = HrFieldState(
            stored = 60,
            parse = { parseRestingHr(it, maxHr = 190) },
            blankMeans = RESTING_HR_UNSTATED
        )

        state.onStoredChanged(RESTING_HR_UNSTATED)

        assertEquals("", state.typed)
    }

    // --- The pair judged against each other (#172) ---
    //
    // What each field puts *in force* for the other, and the fallback when an entry is unusable,
    // live on the screen — see `hrInForce` and SettingsScreenTest. What belongs here is that the
    // field applies whatever pair rule it is given.

    @Test
    fun `a resting hr the pending max cannot hold is refused, not accepted and clamped`() {
        // Lowering Max HR to 100 and stating a resting 90 in the same visit. Judged against the
        // Max HR still on disk, both are accepted here and storage quietly holds 50 — the runner
        // is shown back a number they never typed, which is the failure this screen deletes.
        val resting = HrFieldState(
            stored = RESTING_HR_UNSTATED,
            parse = { parseRestingHr(it, maxHr = 100) },
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
        val state = HrFieldState(stored = 190, parse = ::parseMaxHrAlone)
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
