package com.example.runningapp.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which stored fix is the far side of a Pause (#195).
 *
 * The cases that matter are all about a Run's *opening* fix: it used to be told nothing about it,
 * because the recorder is reused between Runs and every Run ends by tearing it down.
 */
class PauseMarkTest {

    @Test
    fun `a fix taken with nothing behind it is not after a Pause`() {
        val mark = PauseMark()
        mark.runBegan()

        assertFalse(mark.takeForFix())
    }

    @Test
    fun `the fix that resumes a Run is after a Pause`() {
        val mark = PauseMark()
        mark.runBegan()
        mark.takeForFix()

        mark.recordingBroke()

        assertTrue(mark.takeForFix())
    }

    @Test
    fun `a Pause before the Run's first fix is written onto that first fix`() {
        val mark = PauseMark()
        mark.runBegan()
        mark.recordingBroke()

        assertTrue("the wait was not all a satellite lock", mark.takeForFix())
    }

    @Test
    fun `only the first fix after a Pause carries it`() {
        val mark = PauseMark()
        mark.runBegan()
        mark.recordingBroke()
        mark.takeForFix()

        assertFalse(mark.takeForFix())
    }

    @Test
    fun `a Run beginning does not inherit the previous Run's teardown`() {
        val mark = PauseMark()
        mark.runBegan()
        mark.takeForFix()
        // Every Run ends by tearing the recording down, which is indistinguishable from a Pause
        // until the next Run says it is a next Run.
        mark.recordingBroke()

        mark.runBegan()

        assertFalse(mark.takeForFix())
    }

    @Test
    fun `a Run beginning clears a Pause it never had, however many broke it`() {
        val mark = PauseMark()
        mark.recordingBroke()
        mark.recordingBroke()

        mark.runBegan()

        assertFalse(mark.takeForFix())
    }
}
