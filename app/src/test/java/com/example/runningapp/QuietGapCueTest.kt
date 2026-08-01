package com.example.runningapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cue that waits for a gap (#208).
 *
 * The clock is a plain number passed in, so every wait here is exact rather than slept through.
 */
class QuietGapCueTest {

    private val gap = QuietGapCue.QUIET_GAP_MILLIS
    private val ceiling = QuietGapCue.CEILING_MILLIS
    private val cue = QuietGapCue()

    @Test
    fun `a cue held into silence goes at once`() {
        cue.hold(TEXT, T)

        assertEquals(TEXT, cue.release(T))
    }

    @Test
    fun `a cue held while the app is talking waits`() {
        cue.speechChanged(speaking = true, nowMillis = T)
        cue.hold(TEXT, T)

        assertNull(cue.release(T))
        assertNull(cue.release(T + gap * 2))
    }

    @Test
    fun `the wait is not over the instant the talking stops`() {
        cue.speechChanged(speaking = true, nowMillis = T)
        cue.hold(TEXT, T)
        cue.speechChanged(speaking = false, nowMillis = T + 2_000)

        assertNull(cue.release(T + 2_000))
        assertNull(cue.release(T + 2_000 + gap - 1))
        assertEquals(TEXT, cue.release(T + 2_000 + gap))
    }

    @Test
    fun `a split announcement counts as the app talking`() {
        cue.hold(TEXT, T)
        // A Split reaches the speaker without passing through the Run, and the wait still sees it.
        cue.speechChanged(speaking = true, nowMillis = T + 500)

        assertNull(cue.release(T + 1_000))
    }

    @Test
    fun `a run that never falls quiet gets its cue at the ceiling`() {
        cue.speechChanged(speaking = true, nowMillis = T)
        cue.hold(TEXT, T)

        assertNull(cue.release(T + ceiling - 1))
        assertEquals(TEXT, cue.release(T + ceiling))
    }

    @Test
    fun `a cue goes exactly once`() {
        cue.hold(TEXT, T)

        assertEquals(TEXT, cue.release(T))
        assertNull(cue.release(T + ceiling * 2))
    }

    @Test
    fun `nothing held is nothing to say`() {
        assertNull(cue.release(T))
    }

    @Test
    fun `a forgotten cue is never said`() {
        cue.hold(TEXT, T)
        cue.forget()

        assertNull(cue.release(T + ceiling * 2))
    }

    private companion object {
        const val T = 1_700_000_000_000L
        const val TEXT = "Halfway. Turn around."
    }
}
