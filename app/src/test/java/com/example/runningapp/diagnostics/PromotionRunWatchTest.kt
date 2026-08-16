package com.example.runningapp.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromotionRunWatchTest {

    private val watch = PromotionRunWatch()

    /**
     * The sequence off the phone, in the order the service publishes it: the Promotion is taken
     * before the Run's row lands, the row arrives, the Run runs, and the stop publishes a cleared
     * live Run before the hand-back is decided. That last null is what made every `demoted` on a
     * real Run read `run=-`.
     */
    @Test
    fun `a hand-back after a stop names the run whose stop caused it`() {
        watch.observe(null)
        watch.observe(9140)
        watch.observe(9140)
        watch.observe(null)

        assertEquals(9140L, watch.handBack(null))
    }

    /** The #309 shape: the foreground state goes back with a Run still recording. */
    @Test
    fun `a hand-back with a run live names that run`() {
        watch.observe(9140)

        assertEquals(9140L, watch.handBack(9140))
    }

    /** A pre-run Acquisition's Promotion being unwound belongs to no Run. */
    @Test
    fun `a hand-back with no run at all names none`() {
        watch.observe(null)

        assertNull(watch.handBack(null))
    }

    /**
     * The Run is forgotten with the Promotion it was held for. A later Acquisition unwinding is not
     * the Run before it, however recently that Run finished — a journal that guesses wrong is worse
     * than one with a gap in it.
     */
    @Test
    fun `a later hand-back does not inherit the finished run`() {
        watch.observe(9140)
        watch.observe(null)
        watch.handBack(null)

        watch.observe(null)

        assertNull(watch.handBack(null))
    }

    /**
     * A Run inside the next Promotion is that Promotion's answer, not the one before it — the
     * memory is per Promotion, and the second Run must not be reported as the first.
     */
    @Test
    fun `each promotion answers with its own run`() {
        watch.observe(9140)
        watch.observe(null)
        assertEquals(9140L, watch.handBack(null))

        watch.observe(9141)
        watch.observe(null)
        assertEquals(9141L, watch.handBack(null))
    }
}
