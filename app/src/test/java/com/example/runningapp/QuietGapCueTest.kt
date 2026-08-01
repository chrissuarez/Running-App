package com.example.runningapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cue that waits for a gap (#208).
 *
 * The clock is a plain number passed in, so every wait here is exact rather than slept through, and
 * the speaker is a list, so what was said is what the test reads.
 */
class QuietGapCueTest {

    private val gap = QuietGapCue.QUIET_GAP_MILLIS
    private val ceiling = QuietGapCue.CEILING_MILLIS
    private val cue = QuietGapCue()
    private val spoken = mutableListOf<String>()

    /** One poll, as the service makes it: try to release, and keep whatever was said. */
    private fun poll(nowMillis: Long): Boolean = cue.releaseTo(nowMillis, spoken::add)

    @Test
    fun `a cue held into silence goes at once`() {
        cue.hold(TEXT, T)

        assertTrue(poll(T))
        assertEquals(listOf(TEXT), spoken)
    }

    @Test
    fun `a cue held while the app is talking waits`() {
        cue.speechChanged(speaking = true, nowMillis = T)
        cue.hold(TEXT, T)

        assertFalse(poll(T))
        assertFalse(poll(T + gap * 2))
        assertEquals(emptyList<String>(), spoken)
    }

    @Test
    fun `the wait is not over the instant the talking stops`() {
        cue.speechChanged(speaking = true, nowMillis = T)
        cue.hold(TEXT, T)
        cue.speechChanged(speaking = false, nowMillis = T + 2_000)

        assertFalse(poll(T + 2_000))
        assertFalse(poll(T + 2_000 + gap - 1))
        assertTrue(poll(T + 2_000 + gap))
        assertEquals(listOf(TEXT), spoken)
    }

    @Test
    fun `a split announcement counts as the app talking`() {
        cue.hold(TEXT, T)
        // A Split reaches the speaker without passing through the Run, and the wait still sees it.
        cue.speechChanged(speaking = true, nowMillis = T + 500)

        assertFalse(poll(T + 1_000))
    }

    @Test
    fun `a run that never falls quiet gets its cue at the ceiling`() {
        cue.speechChanged(speaking = true, nowMillis = T)
        cue.hold(TEXT, T)

        assertFalse(poll(T + ceiling - 1))
        assertTrue(poll(T + ceiling))
        assertEquals(listOf(TEXT), spoken)
    }

    @Test
    fun `a cue goes exactly once`() {
        cue.hold(TEXT, T)

        assertTrue(poll(T))
        assertFalse(poll(T + ceiling * 2))
        assertEquals(listOf(TEXT), spoken)
    }

    @Test
    fun `nothing held is nothing to say`() {
        assertFalse(poll(T))
        assertEquals(emptyList<String>(), spoken)
    }

    @Test
    fun `a forgotten cue is never said`() {
        cue.hold(TEXT, T)
        cue.forget()

        assertFalse(poll(T + ceiling * 2))
        assertEquals(emptyList<String>(), spoken)
    }

    @Test
    fun `a withdrawal cannot land between deciding to speak and speaking`() {
        cue.hold(TEXT, T)

        // The speaker is where the race would be: the poll has committed to this cue, and the Run
        // withdraws it in that instant. Withdrawing from inside the speaker is the only way a test
        // can be in that window at all — and the cue must still be gone rather than re-held.
        cue.releaseTo(T) {
            cue.forget()
            spoken += it
        }

        assertEquals(listOf(TEXT), spoken)
        assertFalse(poll(T + ceiling * 2))
        assertEquals(listOf(TEXT), spoken)
    }

    private companion object {
        const val T = 1_700_000_000_000L
        const val TEXT = "Halfway. Turn around."
    }
}
