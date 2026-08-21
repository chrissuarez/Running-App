package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.RecordEffortRow
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Records section says (#75), pinned where it is decided rather than read off a phone. */
class RecordsModelsTest {

    private val zone: ZoneId = ZoneId.of("Europe/London")

    private fun at(date: String, hour: Int = 9): Long =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hour, 0), zone).toInstant().toEpochMilli()

    private fun row(
        sessionId: Long,
        type: RecordType,
        value: Double,
        date: String,
        offsetSeconds: Int? = null,
    ) = RecordEffortRow(
        sessionId = sessionId,
        type = type,
        value = value,
        startTime = at(date),
        ranAtUtcOffsetSeconds = offsetSeconds,
    )

    // --- The grid ---

    @Test
    fun `the grid holds every record, in the enum's own order`() {
        val slots = recordSlots(emptyList(), zone)

        assertEquals(RecordType.entries, slots.map { it.type })
    }

    @Test
    fun `a record nobody has run stands empty`() {
        val slots = recordSlots(listOf(row(1, RecordType.FASTEST_5K, 1500.0, "2026-01-05")), zone)

        assertNull(slots.single { it.type == RecordType.FASTEST_10K }.best)
    }

    @Test
    fun `the grid shows the quickest time at a distance`() {
        val slots = recordSlots(
            listOf(
                row(1, RecordType.FASTEST_5K, 1500.0, "2026-01-05"),
                row(2, RecordType.FASTEST_5K, 1442.0, "2026-02-05"),
                row(3, RecordType.FASTEST_5K, 1610.0, "2026-03-05"),
            ),
            zone,
        )

        val fiveK = slots.single { it.type == RecordType.FASTEST_5K }
        assertEquals(2L, fiveK.best?.sessionId)
        assertEquals("24:02", fiveK.best?.valueLabel)
    }

    @Test
    fun `the grid shows the longest run as a distance, not a time`() {
        val slots = recordSlots(
            listOf(
                row(1, RecordType.LONGEST_DISTANCE, 12_400.0, "2026-01-05"),
                row(2, RecordType.LONGEST_DISTANCE, 21_500.0, "2026-02-05"),
            ),
            zone,
        )

        val longest = slots.single { it.type == RecordType.LONGEST_DISTANCE }
        assertEquals(2L, longest.best?.sessionId)
        assertEquals("21.50 km", longest.best?.valueLabel)
    }

    // --- The top ten ---

    @Test
    fun `the top ten is best first and cut at ten`() {
        val rows = (1L..14L).map { row(it, RecordType.FASTEST_1K, 300.0 + it, "2026-01-0" + 1) }

        val top = recordTopEfforts(rows, RecordType.FASTEST_1K, zone)

        assertEquals(RECORD_TOP_COUNT, top.size)
        assertEquals(listOf(1L, 2L, 3L), top.take(3).map { it.effort.sessionId })
        assertEquals(listOf(1, 2, 3), top.take(3).map { it.place })
    }

    @Test
    fun `the top three carry medals and nothing below them does`() {
        val rows = (1L..5L).map { row(it, RecordType.FASTEST_1K, 300.0 + it, "2026-01-01") }

        val top = recordTopEfforts(rows, RecordType.FASTEST_1K, zone)

        assertEquals(listOf(Medal.GOLD, Medal.SILVER, Medal.BRONZE), top.take(3).map { it.medal })
        assertTrue(top.drop(3).all { it.medal == null })
    }

    @Test
    fun `matching a time does not take the place from the run that set it`() {
        val rows = listOf(
            row(7, RecordType.FASTEST_1K, 300.0, "2026-01-05"),
            row(3, RecordType.FASTEST_1K, 300.0, "2026-02-05"),
        )

        val top = recordTopEfforts(rows, RecordType.FASTEST_1K, zone)

        // The lower session id is the earlier Run, which is the rule the record book itself keeps.
        assertEquals(listOf(3L, 7L), top.map { it.effort.sessionId })
    }

    @Test
    fun `the longest run ranks the biggest number first`() {
        val rows = listOf(
            row(1, RecordType.LONGEST_DISTANCE, 8_000.0, "2026-01-05"),
            row(2, RecordType.LONGEST_DISTANCE, 15_000.0, "2026-02-05"),
        )

        val top = recordTopEfforts(rows, RecordType.LONGEST_DISTANCE, zone)

        assertEquals(listOf(2L, 1L), top.map { it.effort.sessionId })
    }

    @Test
    fun `a distance run against the clock carries a pace, and the totals do not`() {
        val fiveK = recordTopEfforts(
            listOf(row(1, RecordType.FASTEST_5K, 1500.0, "2026-01-05")),
            RecordType.FASTEST_5K,
            zone,
        ).single().effort
        val longest = recordTopEfforts(
            listOf(row(2, RecordType.LONGEST_DURATION, 5_400.0, "2026-01-05")),
            RecordType.LONGEST_DURATION,
            zone,
        ).single().effort

        assertEquals("5:00 /km", fiveK.paceLabel)
        assertNull(longest.paceLabel)
    }

    @Test
    fun `a run's date is the runner's own day, not the phone's`() {
        // 00:30 on the 6th in Sydney is still the 5th in London, and the Run was in Sydney.
        val sydney = 11 * 3600
        val startedAt = ZonedDateTime
            .of(LocalDate.parse("2026-01-06").atTime(0, 30), ZoneId.of("Australia/Sydney"))
            .toInstant()
            .toEpochMilli()
        val rows = listOf(
            RecordEffortRow(1L, RecordType.FASTEST_5K, 1500.0, startedAt, sydney)
        )

        val effort = recordTopEfforts(rows, RecordType.FASTEST_5K, zone).single().effort

        assertEquals(LocalDate.parse("2026-01-06"), effort.date)
    }

    @Test
    fun `the top list only ever holds one record's efforts`() {
        val rows = listOf(
            row(1, RecordType.FASTEST_5K, 1500.0, "2026-01-05"),
            row(1, RecordType.LONGEST_DURATION, 3_000.0, "2026-01-05"),
        )

        val top = recordTopEfforts(rows, RecordType.FASTEST_5K, zone)

        assertEquals(1, top.size)
        assertEquals(RecordType.FASTEST_5K, top.single().effort.type)
    }

    @Test
    fun `the list names what it is leaving out, and says so only when it is`() {
        assertEquals("Every effort, best first", recordTopTitle(RECORD_TOP_COUNT))
        assertEquals("Top 10 of 14 efforts", recordTopTitle(14))
    }

    // --- The trend ---

    @Test
    fun `the trend keeps one point a day, at that day's best`() {
        val rows = listOf(
            row(1, RecordType.FASTEST_5K, 1500.0, "2026-01-05", offsetSeconds = 0),
            row(2, RecordType.FASTEST_5K, 1460.0, "2026-01-05", offsetSeconds = 0),
            row(3, RecordType.FASTEST_5K, 1480.0, "2026-01-12", offsetSeconds = 0),
        )

        val trend = recordTrendPoints(rows, RecordType.FASTEST_5K, zone)

        assertEquals(2, trend.size)
        assertEquals(listOf(2L, 3L), trend.map { it.sessionId })
        // Placed by the calendar: a week apart is seven days apart on the axis.
        assertEquals(listOf(0, 7), trend.map { it.dayOffset })
    }

    @Test
    fun `one day of running is no trend`() {
        val rows = listOf(
            row(1, RecordType.FASTEST_5K, 1500.0, "2026-01-05", offsetSeconds = 0),
            row(2, RecordType.FASTEST_5K, 1460.0, "2026-01-05", offsetSeconds = 0),
        )

        assertTrue(recordTrendPoints(rows, RecordType.FASTEST_5K, zone).isEmpty())
    }

    @Test
    fun `the trend says out loud what its two ends are`() {
        val rows = listOf(
            row(1, RecordType.FASTEST_5K, 1500.0, "2026-01-05", offsetSeconds = 0),
            row(2, RecordType.FASTEST_5K, 1460.0, "2026-02-05", offsetSeconds = 0),
        )

        val spoken = recordTrendDescription(
            RecordType.FASTEST_5K,
            recordTrendPoints(rows, RecordType.FASTEST_5K, zone),
        )

        assertEquals(
            "Your Fastest 5 km from 5 Jan 2026 to 5 Feb 2026: 25:00 on the first day, 24:20 on the latest.",
            spoken,
        )
    }
}
