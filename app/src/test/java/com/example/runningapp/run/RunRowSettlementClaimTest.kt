package com.example.runningapp.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exactly one settler writes a Run's row — and exactly one, not at most one (#382).
 *
 * Both halves are tested because both have been got wrong in this file's history. Two writers is
 * #315's harm: the row's totals decided by whichever landed last. Zero writers is what refusing the
 * Run's own finalize during a teardown actually produced: an `endTime = 0` row invisible to history,
 * the export and the coach until some later launch's pass found it.
 */
class RunRowSettlementClaimTest {

    @Test
    fun `the first settler to ask writes the row`() {
        val claim = RunRowSettlementClaim()
        assertTrue(claim.takenHere())
    }

    @Test
    fun `the second settler stands down`() {
        // The finalize and the teardown's rescue, in whichever order they arrive. The loser must not
        // write: its answer would go over an answer already on disk.
        val claim = RunRowSettlementClaim()
        assertTrue(claim.takenHere())
        assertFalse(claim.takenHere())
    }

    @Test
    fun `exactly one settler wins when they race`() {
        // The two settlers observe each other in no way at all — the finalize runs from the session
        // thread's dispatch of a STOP, the rescue from onDestroy on main — so the only thing
        // deciding this is the compare-and-set.
        repeat(50) {
            val claim = RunRowSettlementClaim()
            val settlers = 8
            val goTogether = CountDownLatch(1)
            val winners = AtomicInteger()
            val threads = (1..settlers).map {
                Thread {
                    goTogether.await(5, TimeUnit.SECONDS)
                    if (claim.takenHere()) winners.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            goTogether.countDown()
            threads.forEach { it.join(5_000) }

            assertEquals("the row was settled by ${winners.get()} writers", 1, winners.get())
        }
    }

    @Test
    fun `a new run's row is nobody's yet`() {
        // The reset, and the reason it is the reset rather than a release at the end of a Run: a
        // claim left standing from the last Run would have this Run's row settled by neither its own
        // finalize nor a teardown's rescue, both standing down for the other. That is the lost Run
        // this class exists to end, arriving one Run later.
        val claim = RunRowSettlementClaim()
        assertTrue(claim.takenHere())

        claim.releaseForANewRun()

        assertTrue("the new run's row was refused by its own settler", claim.takenHere())
        assertFalse(claim.takenHere())
    }
}
