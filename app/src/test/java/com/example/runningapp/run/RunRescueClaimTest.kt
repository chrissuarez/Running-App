package com.example.runningapp.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exactly one teardown rebuilds a Run and tells its runner it stopped recording (#382).
 *
 * Not who writes the row: that is the row's own question and is answered by the settling write
 * ([com.example.runningapp.data.SETTLE_RUN_ROW_IF_UNSETTLED], proved in
 * [com.example.runningapp.data.SettleRunRowQueryTest]). This claim decides only who pays for a
 * rebuild the Run's own finish would beat, and who says something to the runner that a Run they
 * stopped themselves makes false.
 *
 * The distinction is the whole reason this class still exists, and it was learnt by losing a Run to
 * the other reading: when the claim decided the *write*, a teardown took it before it knew whether
 * it could rebuild anything, rebuilt nothing, and the Run's own finalize had already stood down —
 * an `endTime = 0` row invisible to history, the export and the coach. What a wrong answer here now
 * costs is a notification.
 */
class RunRescueClaimTest {

    @Test
    fun `the first settler to ask is the one that rescues`() {
        val claim = RunRescueClaim()
        assertTrue(claim.takenHere())
    }

    @Test
    fun `the second settler stands down`() {
        // The finalize and the teardown's rescue, in whichever order they arrive. A teardown that
        // loses knows the Run's own finish is under way, so there is nothing here worth rebuilding
        // and nothing true to tell the runner.
        val claim = RunRescueClaim()
        assertTrue(claim.takenHere())
        assertFalse(claim.takenHere())
    }

    @Test
    fun `exactly one settler wins when they race`() {
        // The two settlers observe each other in no way at all — the finalize runs from the session
        // thread's dispatch of a STOP, the rescue from onDestroy on main — so the only thing
        // deciding this is the compare-and-set.
        repeat(50) {
            val claim = RunRescueClaim()
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

            assertEquals("the Run was claimed by ${winners.get()} settlers", 1, winners.get())
        }
    }

    @Test
    fun `a new run is nobody's to rescue yet`() {
        // The reset, and the reason it is the reset rather than a release at the end of a Run: a
        // claim left standing from the last Run would tell this Run's teardown that a finalize was
        // on its way when none is, so a Run genuinely taken from its runner would be neither
        // rebuilt nor spoken about.
        val claim = RunRescueClaim()
        assertTrue(claim.takenHere())

        claim.releaseForANewRun()

        assertTrue("the new run was refused by its own settler", claim.takenHere())
        assertFalse(claim.takenHere())
    }
}
