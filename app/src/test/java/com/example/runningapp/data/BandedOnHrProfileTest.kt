package com.example.runningapp.data

import com.example.runningapp.HrProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Reserve a Run was recorded under, asked of the Run itself (#228).
 *
 * Everything that re-reads a finished Run's beats — its route colours, the rescue pass — asks
 * through here, so what a half-written pair means is settled in one place rather than at each of
 * them.
 */
class BandedOnHrProfileTest {

    private val run = RunnerSession(id = 1, startTime = 1_700_000_000_000L)

    @Test
    fun `a run that wrote both numbers down is read on those`() {
        val recorded = run.copy(bandedOnMaxHr = 195, bandedOnRestingHr = 48).bandedOnHrProfile()

        assertEquals(HrProfile(maxHr = 195, restingHr = 48), recorded)
    }

    @Test
    fun `a run recorded before the pair existed has none, and the caller falls back`() {
        assertNull(run.bandedOnHrProfile())
    }

    @Test
    fun `half a pair is nobody's Reserve, so it is not read as one`() {
        // A Max HR read against somebody else's resting heart rate would be a Reserve no Run was
        // ever recorded under — worse than falling back, because it looks like an answer.
        assertNull(run.copy(bandedOnMaxHr = 195).bandedOnHrProfile())
        assertNull(run.copy(bandedOnRestingHr = 48).bandedOnHrProfile())
    }

    @Test
    fun `an unstated resting heart rate is a value, not a missing one`() {
        // Zero is what "no resting heart rate stated" is stored as, and a Run recorded that way was
        // banded on the whole of its Max HR. Reading it as a gap would send the Run to the global.
        val recorded = run.copy(bandedOnMaxHr = 190, bandedOnRestingHr = 0).bandedOnHrProfile()

        assertEquals(HrProfile(maxHr = 190, restingHr = 0), recorded)
    }
}
