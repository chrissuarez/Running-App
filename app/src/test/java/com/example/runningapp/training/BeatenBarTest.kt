package com.example.runningapp.training

import com.example.runningapp.BestEffortRequirement
import com.example.runningapp.analysis.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private val ZONE: ZoneId = ZoneId.of("Europe/London")
private val TODAY: LocalDate = LocalDate.of(2026, 8, 14)

/** Midday on 14 June 2026 in [ZONE] — the day the ticket's example names. */
private const val JUNE_14_2026 = 1_781_434_800_000L

/** Midday on 14 June 2024 in [ZONE] — the same day, two years back. */
private const val JUNE_14_2024 = 1_718_362_800_000L

private val SUB_30 = BestEffortRequirement(RecordType.FASTEST_5K, 1799)

class BeatenBarTest {

    @Test
    fun `says nothing when history holds no effort at the distance`() {
        assertNull(alreadyBeatenLine(SUB_30, best = null, today = TODAY, zone = ZONE))
    }

    @Test
    fun `says nothing when the best effort in history misses the bar`() {
        val best = HistoryBestEffort(seconds = 1800.0, runStartedAtMillis = JUNE_14_2026)
        assertNull(alreadyBeatenLine(SUB_30, best, TODAY, ZONE))
    }

    @Test
    fun `names the run when its effort clears the bar`() {
        val best = HistoryBestEffort(seconds = 1661.0, runStartedAtMillis = JUNE_14_2026)
        assertEquals(
            "Your 5 km on 14 June was 27:41 — fast enough for this stage. " +
                "Run one now and it counts.",
            alreadyBeatenLine(SUB_30, best, TODAY, ZONE)
        )
    }

    @Test
    fun `the slowest time that still passes is beaten`() {
        val best = HistoryBestEffort(seconds = 1799.0, runStartedAtMillis = JUNE_14_2026)
        assertTrue(alreadyBeatenLine(SUB_30, best, TODAY, ZONE)!!.contains("29:59"))
    }

    @Test
    fun `names the year when the run was not this one`() {
        val best = HistoryBestEffort(seconds = 1661.0, runStartedAtMillis = JUNE_14_2024)
        assertTrue(alreadyBeatenLine(SUB_30, best, TODAY, ZONE)!!.contains("14 June 2024"))
    }

    @Test
    fun `the day is the runner's own, not UTC`() {
        // Half past midnight on 15 June in Sydney is still 14 June in London.
        val justAfterMidnightSydney = 1_781_447_400_000L
        val best = HistoryBestEffort(seconds = 1661.0, runStartedAtMillis = justAfterMidnightSydney)
        assertTrue(
            alreadyBeatenLine(SUB_30, best, TODAY, ZoneId.of("Australia/Sydney"))!!
                .contains("15 June")
        )
        assertTrue(alreadyBeatenLine(SUB_30, best, TODAY, ZONE)!!.contains("14 June"))
    }

    @Test
    fun `it states a fact and never offers a graduation`() {
        val best = HistoryBestEffort(seconds = 1661.0, runStartedAtMillis = JUNE_14_2026)
        val line = alreadyBeatenLine(SUB_30, best, TODAY, ZONE)!!
        assertTrue(line.endsWith("Run one now and it counts."))
        listOf("unlock", "complete", "graduat", "will count", "tap", "claim").forEach {
            assertTrue("said \"$it\": $line", !line.lowercase().contains(it))
        }
    }
}
