package com.example.runningapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The acceptance criteria for #108 expressed as a ladder walk. Times are absolute wall-clock ms,
 * exactly as [CueLadder.onSample] receives them from the service.
 */
class CueLadderTest {

    private val t0 = 1_000_000L
    private fun secs(n: Long) = t0 + n * 1000L

    /** Feed one out-of-target sample per second from [fromSec]..[toSec] and collect what fires. */
    private fun CueLadder.sweepOut(
        fromSec: Long,
        toSec: Long,
        band: ZoneBand = ZoneBand.ABOVE,
        awake: Boolean = true
    ): List<Long> {
        val firedAt = mutableListOf<Long>()
        for (s in fromSec..toSec) {
            if (onSample(secs(s), band, awake) == CueAction.SPEAK) firedAt += s
        }
        return firedAt
    }

    @Test
    fun `silent before 30s, cue at 30s, second at 60s, then every 5 minutes`() {
        val ladder = CueLadder()
        // Out of target continuously for 20 minutes. Rungs are 30s, 60s, then every 5 min after the
        // 60s mark: 360, 660, 960 (the next, 1260, falls outside the 1200s sweep).
        val fired = ladder.sweepOut(0, 20 * 60)
        assertEquals(listOf(30L, 60L, 360L, 660L, 960L), fired)
    }

    @Test
    fun `no cue in the first 29 seconds`() {
        val ladder = CueLadder()
        assertEquals(emptyList<Long>(), ladder.sweepOut(0, 29))
    }

    @Test
    fun `two cues then a five minute gap, not one every 75 seconds`() {
        val ladder = CueLadder()
        // Sweep past the third rung so the five-minute gap (60s -> 360s) is actually asserted:
        // an app nagging every 75s would fire at 135, 210, 285 — this proves it does not.
        val fired = ladder.sweepOut(0, 6 * 60 + 5)
        assertEquals(listOf(30L, 60L, 360L), fired)
    }

    @Test
    fun `the ladder resets to the top on re-entry`() {
        val ladder = CueLadder()
        // Out for 90s: cues at 30 and 60.
        assertEquals(listOf(30L, 60L), ladder.sweepOut(0, 90))
        // Back in target at 91s -> return cue, ladder resets.
        assertEquals(CueAction.RETURN, ladder.onSample(secs(91), ZoneBand.IN, awake = true))
        // Out again from 100s: first cue is 30s LATER (at 130s), not immediately.
        assertEquals(listOf(130L, 160L), ladder.sweepOut(100, 190))
    }

    @Test
    fun `return cue fires only if a cue was actually spoken`() {
        val ladder = CueLadder()
        // Out of target for only 20s (never reaches the 30s rung), then back in.
        ladder.sweepOut(0, 20)
        assertEquals(CueAction.SILENT, ladder.onSample(secs(21), ZoneBand.IN, awake = true))
    }

    @Test
    fun `return cue fires after a spoken cue, in either direction`() {
        val below = CueLadder()
        below.sweepOut(0, 35, band = ZoneBand.BELOW) // cue at 30s
        assertEquals(CueAction.RETURN, below.onSample(secs(40), ZoneBand.IN, awake = true))
    }

    @Test
    fun `not awake is silent and resets the ladder`() {
        val ladder = CueLadder()
        // Out of target but asleep (warm-up / walk / grace) for 10 minutes: never speaks.
        assertEquals(emptyList<Long>(), ladder.sweepOut(0, 10 * 60, awake = false))
        // Waking up starts a fresh 30s clock, not an immediate burst of overdue cues.
        assertEquals(listOf((10 * 60 + 30).toLong(), (10 * 60 + 60).toLong()), ladder.sweepOut(10 * 60, 11 * 60))
    }

    @Test
    fun `a walk step mid-ladder resets it, so the next run step starts silent`() {
        val ladder = CueLadder()
        ladder.sweepOut(0, 60) // cues at 30, 60
        // Walk step: asleep for 30s.
        ladder.sweepOut(61, 90, awake = false)
        // Run step resumes, still out of target: fresh ladder, first cue 30s after resuming (at 121s).
        assertEquals(listOf(121L), ladder.sweepOut(91, 121))
    }

    @Test
    fun `staying in target never speaks`() {
        val ladder = CueLadder()
        for (s in 0..600L) {
            assertEquals(CueAction.SILENT, ladder.onSample(secs(s), ZoneBand.IN, awake = true))
        }
    }
}
