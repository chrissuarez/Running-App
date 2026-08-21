package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import com.example.runningapp.data.SegmentEffortRow
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Segment's page says once it is the full trophy view (#72): the all-time top ten, and the
 * trend of the times behind it.
 */
class SegmentTrophyTest {

    private val london = ZoneId.of("Europe/London")

    /** Ten past eight on the morning of 3 June 2024, in London. */
    private val aMorning = 1_717_398_600_000L

    private fun row(id: Long, day: Long, elapsedMillis: Long, hourOfDay: Long = 0) =
        SegmentEffortRow(
            effortId = id,
            sessionId = 100 + id,
            startedAtMillis = aMorning + day * 86_400_000L + hourOfDay * 3_600_000L,
            elapsedMillis = elapsedMillis,
            ranAtUtcOffsetSeconds = 3_600,
        )

    private fun shown(vararg rows: SegmentEffortRow) =
        segmentEffortsUi(rows.toList(), distanceMeters = 400.0, zone = london)

    // --- The all-time top ten ---

    @Test
    fun `the top of the list is the quickest ever run, not the latest`() {
        val top = segmentTopEfforts(
            shown(
                row(1, day = 0, elapsedMillis = 100_000),
                row(2, day = 1, elapsedMillis = 92_000),
                row(3, day = 2, elapsedMillis = 95_000),
            )
        )

        assertEquals(listOf(1, 2, 3), top.map { it.place })
        assertEquals(listOf(2L, 3L, 1L), top.map { it.effort.effortId })
    }

    @Test
    fun `the quickest three wear the same three metals the record book hands out`() {
        val top = segmentTopEfforts(
            shown(
                row(1, day = 0, elapsedMillis = 100_000),
                row(2, day = 1, elapsedMillis = 92_000),
                row(3, day = 2, elapsedMillis = 95_000),
                row(4, day = 3, elapsedMillis = 110_000),
            )
        )

        assertEquals(
            listOf(Medal.GOLD, Medal.SILVER, Medal.BRONZE, null),
            top.map { it.medal },
        )
    }

    @Test
    fun `matching a time does not take the place off whoever ran it first`() {
        // The rule the record book and the PR card both keep: a place is the runner's until
        // somebody actually beats it.
        val top = segmentTopEfforts(
            shown(
                row(1, day = 0, elapsedMillis = 92_000),
                row(2, day = 1, elapsedMillis = 92_000),
            )
        )

        assertEquals(listOf(1L, 2L), top.map { it.effort.effortId })
    }

    @Test
    fun `two efforts run at the very same instant still settle on one order`() {
        val sameInstant = listOf(
            row(2, day = 0, elapsedMillis = 92_000),
            row(1, day = 0, elapsedMillis = 92_000),
        )

        val forwards = segmentTopEfforts(segmentEffortsUi(sameInstant, 400.0, london))
        val backwards = segmentTopEfforts(segmentEffortsUi(sameInstant.reversed(), 400.0, london))

        assertEquals(listOf(1L, 2L), forwards.map { it.effort.effortId })
        assertEquals(forwards.map { it.effort.effortId }, backwards.map { it.effort.effortId })
    }

    @Test
    fun `the list stops at ten however many have been run`() {
        val twelve = (1..12).map { row(it.toLong(), day = it.toLong(), elapsedMillis = 90_000L + it * 1_000L) }

        val top = segmentTopEfforts(segmentEffortsUi(twelve, 400.0, london))

        assertEquals(10, top.size)
        assertEquals(10, top.last().place)
    }

    @Test
    fun `a list holding every effort does not call itself a top ten`() {
        // Nine efforts shown out of nine is not a top ten, and saying so would be the page telling
        // the runner something was left out when nothing was.
        assertEquals("Every effort, quickest first", segmentTopTitle(total = 9))
        assertEquals("Every effort, quickest first", segmentTopTitle(total = 10))
    }

    @Test
    fun `a list that leaves efforts out says how many there were`() {
        assertEquals("Top 10 of 23 efforts", segmentTopTitle(total = 23))
    }

    // --- The trend of the times ---

    @Test
    fun `the trend runs oldest first, so the line reads left to right as time did`() {
        val points = segmentTrendPoints(
            shown(
                row(1, day = 0, elapsedMillis = 100_000),
                row(2, day = 7, elapsedMillis = 95_000),
                row(3, day = 21, elapsedMillis = 92_000),
            )
        )

        assertEquals(
            listOf(LocalDate.of(2024, 6, 3), LocalDate.of(2024, 6, 10), LocalDate.of(2024, 6, 24)),
            points.map { it.date },
        )
        assertEquals(listOf(100L, 95L, 92L), points.map { it.seconds })
    }

    @Test
    fun `a day is placed by the calendar, so a long gap is drawn as a long gap`() {
        // The whole claim of the chart is whether the runner is getting quicker across months and
        // years. Spacing the efforts evenly would draw a two-year gap as one step and make the
        // claim a lie.
        val points = segmentTrendPoints(
            shown(
                row(1, day = 0, elapsedMillis = 100_000),
                row(2, day = 3, elapsedMillis = 95_000),
                row(3, day = 400, elapsedMillis = 92_000),
            )
        )

        assertEquals(listOf(0, 3, 400), points.map { it.dayOffset })
    }

    @Test
    fun `a day the runner went over it twice is drawn once, at their quickest`() {
        // One point per day, because two points sharing a date have nowhere to sit apart on a
        // calendar axis — and the quickest is the one the runner would quote for that day.
        val points = segmentTrendPoints(
            shown(
                row(1, day = 0, elapsedMillis = 100_000, hourOfDay = 1),
                row(2, day = 0, elapsedMillis = 94_000, hourOfDay = 2),
                row(3, day = 5, elapsedMillis = 96_000),
            )
        )

        assertEquals(listOf(94L, 96L), points.map { it.seconds })
        assertEquals(listOf(2L, 3L), points.map { it.effortId })
    }

    @Test
    fun `one effort draws no chart at all`() {
        assertTrue(segmentTrendPoints(shown(row(1, day = 0, elapsedMillis = 100_000))).isEmpty())
        assertTrue(segmentTrendPoints(emptyList()).isEmpty())
    }

    @Test
    fun `two efforts on one day draw no chart either`() {
        // Two points at one date is a vertical mark, not a trend. The list still shows both.
        val sameDay = shown(
            row(1, day = 0, elapsedMillis = 100_000, hourOfDay = 1),
            row(2, day = 0, elapsedMillis = 94_000, hourOfDay = 2),
        )

        assertTrue(segmentTrendPoints(sameDay).isEmpty())
        assertEquals(2, segmentTopEfforts(sameDay).size)
    }

    @Test
    fun `the chart labels three dates however far apart the efforts are`() {
        // Vico steps the axis in whole units of the smallest gap between two points, so the spacing
        // is counted in those units and not in efforts.
        val fortnightly = segmentTrendPoints(
            shown(
                row(1, day = 0, elapsedMillis = 100_000),
                row(2, day = 14, elapsedMillis = 98_000),
                row(3, day = 28, elapsedMillis = 96_000),
                row(4, day = 42, elapsedMillis = 94_000),
                row(5, day = 56, elapsedMillis = 92_000),
                row(6, day = 70, elapsedMillis = 90_000),
            )
        )

        // Six points a fortnight apart: the axis steps in fortnights, so a label every other one.
        assertEquals(14, segmentTrendStepDays(fortnightly))
        assertEquals(2, segmentTrendLabelSpacing(fortnightly))
    }

    @Test
    fun `an axis with nothing to step by is never asked to divide by it`() {
        assertEquals(1, segmentTrendStepDays(emptyList()))
        assertEquals(1, segmentTrendLabelSpacing(emptyList()))
    }

    @Test
    fun `the chart says out loud what it draws, and over what stretch of the calendar`() {
        val spoken = segmentTrendDescription(
            segmentTrendPoints(
                shown(
                    row(1, day = 0, elapsedMillis = 100_000),
                    row(2, day = 21, elapsedMillis = 92_000),
                )
            )
        )!!

        assertTrue(spoken.contains("3 Jun 2024"))
        assertTrue(spoken.contains("24 Jun 2024"))
        assertTrue(spoken.contains("01:40"))
        assertTrue(spoken.contains("01:32"))
    }

    @Test
    fun `a chart nobody is drawing has nothing to say`() {
        assertNull(segmentTrendDescription(emptyList()))
    }

    @Test
    fun `the times up the side are read as times, not as seconds`() {
        assertEquals("01:32", segmentTrendTimeLabel(92f))
        assertEquals("1:01:32", segmentTrendTimeLabel(3692f))
    }
}
