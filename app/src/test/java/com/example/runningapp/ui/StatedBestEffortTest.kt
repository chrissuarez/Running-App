package com.example.runningapp.ui

import com.example.runningapp.analysis.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a lap time off a treadmill console into the app (#282).
 *
 * Two rules, both applied before the repository ever sees the claim: this is a time, or it is
 * nothing; and this Run could hold a claim at that distance, or it is never offered one.
 */
class StatedBestEffortTest {

    @Test
    fun `a typed time is minutes and seconds`() {
        assertEquals(1_530, statedEffortSecondsOf("25:30"))
        assertEquals(280, statedEffortSecondsOf("4:40"))
        assertEquals(1_500, statedEffortSecondsOf(" 25:00 "))
    }

    @Test
    fun `three parts are hours, minutes and seconds`() {
        // A half marathon is over an hour for most runners, so the format has to reach it.
        assertEquals(5_130, statedEffortSecondsOf("1:25:30"))
        assertEquals(7_200, statedEffortSecondsOf("2:00:00"))
    }

    @Test
    fun `a bare number is refused rather than guessed at`() {
        // A lone 4 against a 1 km is four minutes to one runner and four seconds to another, and a
        // Best Effort is not a number to guess at.
        assertNull(statedEffortSecondsOf("2530"))
        assertNull(statedEffortSecondsOf("4"))
    }

    @Test
    fun `a place in a bigger unit is two digits and never reaches sixty`() {
        assertNull(statedEffortSecondsOf("25:75"))
        assertNull(statedEffortSecondsOf("25:5"))
        assertNull(statedEffortSecondsOf("1:75:00"))
    }

    @Test
    fun `what is not a time at all is not a time`() {
        assertNull(statedEffortSecondsOf(""))
        assertNull(statedEffortSecondsOf("25:"))
        assertNull(statedEffortSecondsOf(":30"))
        assertNull(statedEffortSecondsOf("25:30:15:10"))
        assertNull(statedEffortSecondsOf("twenty five"))
        assertNull(statedEffortSecondsOf("25.30"))
        // Zero is not a time anybody's console showed.
        assertNull(statedEffortSecondsOf("0:00"))
    }

    @Test
    fun `nothing typed is not a rejection`() {
        // A runner who has typed nothing has said nothing, which is allowed everywhere the field
        // appears. It is the half-typed entry that has to stop a Save.
        assertFalse(statedEffortIsRejected(""))
        assertFalse(statedEffortIsRejected("   "))
        assertTrue(statedEffortIsRejected("25:"))
        assertFalse(statedEffortIsRejected("25:30"))
    }

    @Test
    fun `a time is written back in the format it is typed in`() {
        // What is typed here and what the record book shows are one format, so a correction opens
        // on the number the runner already recognises.
        assertEquals("25:30", formatDuration(statedEffortSecondsOf("25:30")!!.toLong()))
        assertEquals("1:25:30", formatDuration(statedEffortSecondsOf("1:25:30")!!.toLong()))
    }

    @Test
    fun `only distances the Run is long enough to hold are offered`() {
        // The screen's copy of the repository's refusal, so a distance the Run cannot contain is
        // never offered rather than being refused after a Save.
        assertEquals(
            listOf(RecordType.FASTEST_1K, RecordType.FASTEST_MILE, RecordType.FASTEST_5K),
            recordDistancesWithin(statedDistanceKm = 6.0),
        )
        assertEquals(emptyList<RecordType>(), recordDistancesWithin(statedDistanceKm = 0.6))
    }

    @Test
    fun `a Run nobody stated a distance for is offered all five`() {
        // The two statements are independent: a runner who noted only the 5 km split has still said
        // something true.
        assertEquals(RecordType.bestEfforts, recordDistancesWithin(statedDistanceKm = 0.0))
    }

    @Test
    fun `a half marathon survives the rounding a distance is typed at`() {
        // 21.09 km is how a genuine half reads in a field that takes two decimal places, against a
        // record of 21 097.5 m. The shortfall is the format's, not the runner's.
        assertTrue(RecordType.FASTEST_HALF in recordDistancesWithin(statedDistanceKm = 21.09))
        assertFalse(RecordType.FASTEST_HALF in recordDistancesWithin(statedDistanceKm = 21.0))
    }
}
