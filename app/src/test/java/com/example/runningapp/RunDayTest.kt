package com.example.runningapp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The acceptance criteria for #304: a Run's day is the day the runner ran it. */
class RunDayTest {

    private val london: ZoneId = ZoneId.of("Europe/London")
    private val sydney: ZoneId = ZoneId.of("Australia/Sydney")

    /** 23:30 on 14 August 2026 in London — BST, so one hour east of UTC. */
    private val lateOnTheFourteenth: Long =
        LocalDate.of(2026, 8, 14).atTime(23, 30).atZone(london).toInstant().toEpochMilli()

    private val bstOffsetSeconds = 3600

    @Test
    fun `a Run carrying its own offset keeps its day wherever the phone is now`() {
        assertEquals(
            LocalDate.of(2026, 8, 14),
            ranOn(lateOnTheFourteenth, bstOffsetSeconds, fallbackZone = sydney),
        )
    }

    @Test
    fun `a Run carrying its own offset keeps its time of day too`() {
        assertEquals(23, ranAt(lateOnTheFourteenth, bstOffsetSeconds, fallbackZone = sydney).hour)
    }

    @Test
    fun `a Run carrying no offset is read in the zone the phone is in`() {
        // What every Run recorded before v32 gets, and the behaviour this fix leaves untouched:
        // Sydney is ten hours ahead, so the same moment is the morning of the fifteenth there.
        assertEquals(
            LocalDate.of(2026, 8, 15),
            ranOn(lateOnTheFourteenth, ranAtUtcOffsetSeconds = null, fallbackZone = sydney),
        )
        assertEquals(
            LocalDate.of(2026, 8, 14),
            ranOn(lateOnTheFourteenth, ranAtUtcOffsetSeconds = null, fallbackZone = london),
        )
    }

    @Test
    fun `an offset no zone on earth has is read as no offset at all`() {
        // A stored number is only ever as good as what wrote it, and this one is out of range for
        // ZoneOffset entirely. Falling back beats throwing on the History list.
        assertEquals(
            LocalDate.of(2026, 8, 15),
            ranOn(lateOnTheFourteenth, ranAtUtcOffsetSeconds = 99 * 3600, fallbackZone = sydney),
        )
    }

    @Test
    fun `an offset west of UTC moves the day back`() {
        // 23:30 BST is 18:30 the same evening in New York, and 15:30 in Los Angeles.
        val newYork = -4 * 3600
        assertEquals(LocalDate.of(2026, 8, 14), ranOn(lateOnTheFourteenth, newYork, london))
        assertEquals(18, ranAt(lateOnTheFourteenth, newYork, london).hour)
    }

    @Test
    fun `an offset that is not a whole hour is kept to the minute`() {
        // Half-hour and three-quarter-hour zones exist, so the stored number is seconds.
        val kathmandu = 5 * 3600 + 45 * 60
        assertEquals(LocalDate.of(2026, 8, 15), ranOn(lateOnTheFourteenth, kathmandu, london))
        assertEquals(4, ranAt(lateOnTheFourteenth, kathmandu, london).hour)
        assertEquals(15, ranAt(lateOnTheFourteenth, kathmandu, london).minute)
    }

    @Test
    fun `the offset a Run is stamped with is the one in force at that moment`() {
        // Not the zone's standard offset: a Run in London in August is on BST, and reading it back
        // through the zone's winter offset would put a late-evening Run on the day before.
        assertEquals(bstOffsetSeconds, utcOffsetSecondsAt(lateOnTheFourteenth, london))
        val midwinter =
            LocalDate.of(2026, 1, 14).atTime(23, 30).atZone(london).toInstant().toEpochMilli()
        assertEquals(0, utcOffsetSecondsAt(midwinter, london))
    }

    @Test
    fun `a Run a day ahead of the phone is a runner who flew, not a broken clock`() {
        val today = LocalDate.of(2026, 8, 14)
        assertEquals(false, today.plusDays(1).isBeyondAnyonesToday(today))
        assertEquals(true, today.plusDays(2).isBeyondAnyonesToday(today))
        assertEquals(false, today.isBeyondAnyonesToday(today))
        assertEquals(false, today.minusDays(400).isBeyondAnyonesToday(today))
    }
}
