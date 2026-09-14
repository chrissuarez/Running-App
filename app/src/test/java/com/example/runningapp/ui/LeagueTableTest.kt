package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one league table the Records, Segment and matched-run pages all build (#481), pinned against
 * an entry of its own so what is tested is the table and not any one page's rows.
 *
 * Each page's own test still pins what that page ranks and how it words it; this pins what every
 * page gets for free — the places, the metals, the cut at ten, the count, the trend and what the
 * trend says out loud.
 */
class LeagueTableTest {

    /** One lap round a track: which one, the day it was run, and its time — or none, never timed. */
    private data class Lap(val id: Long, val day: Int, val seconds: Double?)

    private val firstDay = LocalDate.of(2026, 1, 5)

    private val laps = LeagueTable<Lap>(
        bestFirst = compareBy<Lap> { it.seconds ?: Double.POSITIVE_INFINITY }.thenBy { it.id },
        day = { firstDay.plusDays(it.day.toLong()) },
        plotted = { it.seconds },
        valueLabel = { "${it.toLong()}s" },
        orderWord = "quickest",
        trendSubject = "Your quickest lap",
    )

    private fun lap(id: Long, day: Int = 0, seconds: Double? = 60.0) = Lap(id, day, seconds)

    // --- The ranked list ---

    @Test
    fun `the best is first, whatever order the entries came in`() {
        val top = laps.top(listOf(lap(1, seconds = 70.0), lap(2, seconds = 62.0), lap(3, seconds = 65.0)))

        assertEquals(listOf(2L, 3L, 1L), top.map { it.entry.id })
        assertEquals(listOf(1, 2, 3), top.map { it.place })
    }

    @Test
    fun `the top three wear the three metals and nothing below them does`() {
        val top = laps.top((1L..5L).map { lap(it, seconds = 60.0 + it) })

        assertEquals(
            listOf(Medal.GOLD, Medal.SILVER, Medal.BRONZE, null, null),
            top.map { it.medal },
        )
    }

    @Test
    fun `the list stops at ten however many there are`() {
        val top = laps.top((1L..14L).map { lap(it, seconds = 60.0 + it) })

        assertEquals(LEAGUE_TOP_COUNT, top.size)
        assertEquals(10, top.last().place)
        assertEquals(10L, top.last().entry.id)
    }

    @Test
    fun `a tie is settled by the page's own order, the same way on every read`() {
        val tied = listOf(lap(2, seconds = 60.0), lap(1, seconds = 60.0))

        assertEquals(listOf(1L, 2L), laps.top(tied).map { it.entry.id })
        assertEquals(listOf(1L, 2L), laps.top(tied.reversed()).map { it.entry.id })
        assertEquals(1L, laps.best(tied)?.id)
    }

    @Test
    fun `nothing run has no best and no list`() {
        assertNull(laps.best(emptyList()))
        assertTrue(laps.top(emptyList()).isEmpty())
    }

    @Test
    fun `a list holding every entry does not call itself a top ten`() {
        assertEquals("Every effort, quickest first", laps.topTitle(total = 9))
        assertEquals("Every effort, quickest first", laps.topTitle(total = LEAGUE_TOP_COUNT))
    }

    @Test
    fun `a list that leaves entries out says how many there were`() {
        assertEquals("Top 10 of 23 efforts", laps.topTitle(total = 23))
    }

    @Test
    fun `the count is singular for one`() {
        assertEquals("1 effort", laps.countLabel(1))
        assertEquals("14 efforts", laps.countLabel(14))
    }

    // --- A row said out loud ---

    @Test
    fun `a placed row names its metal, or the number it came in at`() {
        assertEquals(
            "Gold, 5 Jan 2026, 01:00, 5:00 /km",
            placedRowSpoken(place = 1, medal = Medal.GOLD, primary = "5 Jan 2026", secondary = "5:00 /km", trailing = "01:00"),
        )
        assertEquals(
            "Number 4, 5 Jan 2026, 1:00:00",
            placedRowSpoken(place = 4, medal = null, primary = "5 Jan 2026", secondary = null, trailing = "1:00:00"),
        )
    }

    // --- The trend ---

    @Test
    fun `the trend runs oldest first, placed by the calendar`() {
        val points = laps.trend(listOf(lap(3, day = 400, seconds = 58.0), lap(1, day = 0), lap(2, day = 3)))

        assertEquals(listOf(1L, 2L, 3L), points.map { it.entry.id })
        assertEquals(listOf(0, 3, 400), points.map { it.dayOffset })
        assertEquals(listOf(firstDay, firstDay.plusDays(3), firstDay.plusDays(400)), points.map { it.date })
    }

    @Test
    fun `a day run twice is drawn once, at that day's best`() {
        val points = laps.trend(listOf(lap(1, day = 0, seconds = 70.0), lap(2, day = 0, seconds = 64.0), lap(3, day = 5)))

        assertEquals(listOf(2L, 3L), points.map { it.entry.id })
        assertEquals(listOf(64.0, 60.0), points.map { it.value })
    }

    @Test
    fun `fewer than two days is no trend`() {
        assertTrue(laps.trend(emptyList()).isEmpty())
        assertTrue(laps.trend(listOf(lap(1))).isEmpty())
        assertTrue(laps.trend(listOf(lap(1, seconds = 70.0), lap(2, seconds = 64.0))).isEmpty())
    }

    @Test
    fun `an entry with nothing to plot is left off the trend`() {
        // Not a slow lap: a lap nobody timed. Drawn as a zero it would be a cliff never run.
        val points = laps.trend(listOf(lap(1, day = 0, seconds = null), lap(2, day = 7), lap(3, day = 14)))

        assertEquals(listOf(2L, 3L), points.map { it.entry.id })
    }

    @Test
    fun `an untimed entry never beats a timed one on its own day`() {
        val points = laps.trend(listOf(lap(1, day = 0, seconds = null), lap(2, day = 0, seconds = 90.0), lap(3, day = 7)))

        assertEquals(listOf(2L, 3L), points.map { it.entry.id })
    }

    @Test
    fun `each point carries its day and value in words`() {
        val points = laps.trend(listOf(lap(1, day = 0, seconds = 70.0), lap(2, day = 31, seconds = 64.0)))

        assertEquals(listOf("5 Jan 2026", "5 Feb 2026"), points.map { it.dateLabel })
        assertEquals(listOf("70s", "64s"), points.map { it.valueLabel })
    }

    @Test
    fun `the trend says out loud what its two ends are`() {
        val points = laps.trend(listOf(lap(1, day = 0, seconds = 70.0), lap(2, day = 31, seconds = 64.0)))

        assertEquals(
            "Your quickest lap from 5 Jan 2026 to 5 Feb 2026: 70s on the first day, 64s on the latest.",
            laps.trendDescription(points),
        )
    }

    @Test
    fun `a trend nobody is drawing has nothing to say`() {
        assertNull(laps.trendDescription(emptyList()))
    }
}
