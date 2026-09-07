package com.example.runningapp.training

import com.example.runningapp.BestEffortRequirement
import com.example.runningapp.analysis.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `the day named is the Run's own, not the one the phone is in now`() {
        // #304: 23:30 on 14 June 2026 in London. Read in Sydney the same moment is the fifteenth,
        // and the card would name the runner a day they did not run.
        val lateOnJune14 = LocalDate.of(2026, 6, 14).atTime(23, 30)
            .atZone(ZONE).toInstant().toEpochMilli()
        val best = HistoryBestEffort(1700.0, lateOnJune14, ranAtUtcOffsetSeconds = 3600)

        val line = alreadyBeatenLine(SUB_30, best, today = TODAY, zone = ZoneId.of("Australia/Sydney"))

        assertTrue(line!!, line.contains("14 June"))
    }

    @Test
    fun `a Run that wrote down no offset is still named in the phone's zone`() {
        val lateOnJune14 = LocalDate.of(2026, 6, 14).atTime(23, 30)
            .atZone(ZONE).toInstant().toEpochMilli()
        val best = HistoryBestEffort(1700.0, lateOnJune14, ranAtUtcOffsetSeconds = null)

        val line = alreadyBeatenLine(SUB_30, best, today = TODAY, zone = ZoneId.of("Australia/Sydney"))

        assertTrue(line!!, line.contains("15 June"))
    }
}

/**
 * What the card says about a timed bar the runner has *not* beaten (#446) — the gap, in the same
 * register as the beaten line and off the same record book.
 */
class BarShortfallLineTest {

    @Test
    fun `names the best effort and the gap to the bar`() {
        // 31:40 against a bar of 29:59 is 1:41.
        val best = HistoryBestEffort(seconds = 1900.0, runStartedAtMillis = JUNE_14_2026)

        assertEquals(
            "Your best 5 km is 31:40 — 1:41 off the bar.",
            barShortfallLine(SUB_30, BarStanding.Ranked(best))
        )
    }

    @Test
    fun `says nothing where the bar is already beaten, so the congratulation stands alone`() {
        val best = HistoryBestEffort(seconds = 1661.0, runStartedAtMillis = JUNE_14_2026)

        assertNull(barShortfallLine(SUB_30, BarStanding.Ranked(best)))
    }

    @Test
    fun `the slowest time that still passes leaves no gap`() {
        // Inclusive: 29:59 clears 1799, so there is nothing to be short of.
        val best = HistoryBestEffort(seconds = 1799.0, runStartedAtMillis = JUNE_14_2026)

        assertNull(barShortfallLine(SUB_30, BarStanding.Ranked(best)))
    }

    @Test
    fun `one second the wrong side of the bar is a gap of one second`() {
        val best = HistoryBestEffort(seconds = 1800.0, runStartedAtMillis = JUNE_14_2026)

        assertEquals(
            "Your best 5 km is 30:00 — 0:01 off the bar.",
            barShortfallLine(SUB_30, BarStanding.Ranked(best))
        )
    }

    @Test
    fun `a distance nothing has ever been ranked at is said in words`() {
        // Not a gap of any size, and not a zero: nothing has ever been ranked here.
        assertEquals(
            "No 5 km in your record book yet.",
            barShortfallLine(SUB_30, BarStanding.Unranked)
        )
    }

    @Test
    fun `exactly one of the two lines is ever on the card`() {
        val efforts = listOf(null, 1000.0, 1499.0, 1798.9, 1799.0, 1799.1, 1800.0, 3000.0)
        val bars = listOf(SUB_30, BestEffortRequirement(RecordType.FASTEST_5K, 1499))

        bars.forEach { bar ->
            efforts.forEach { seconds ->
                val best = seconds?.let {
                    HistoryBestEffort(seconds = it, runStartedAtMillis = JUNE_14_2026)
                }
                val beaten = alreadyBeatenLine(bar, best, TODAY, ZONE)
                val shortfall = barShortfallLine(
                    bar,
                    best?.let { BarStanding.Ranked(it) } ?: BarStanding.Unranked
                )

                // One comparison, two branches: a card that both congratulated and measured, or
                // one that said neither, would be the two lines disagreeing about one time.
                assertEquals(
                    "bar ${bar.withinSeconds}, best $seconds",
                    1,
                    listOfNotNull(beaten, shortfall).size
                )
            }
        }
    }

    @Test
    fun `the gap printed is the difference between the two times printed`() {
        // Rounded off one double against a whole-second bar, so the clock the runner reads and the
        // gap under it can never be a second apart.
        listOf(1800.4, 1800.5, 1800.6, 1861.2, 2400.49).forEach { seconds ->
            val line = barShortfallLine(
                SUB_30,
                BarStanding.Ranked(
                    HistoryBestEffort(seconds = seconds, runStartedAtMillis = JUNE_14_2026)
                )
            )!!
            val printed = line.substringAfter("is ").substringBefore(" —")
            val gap = line.substringAfter("— ").substringBefore(" off")

            assertEquals(seconds.toString(), asClock(asSeconds(printed) - 1799.0), gap)
        }
    }

    @Test
    fun `a book the app is holding back says nothing, not that the book is empty`() {
        // Testing mode silences the record book. "Nothing may be said" must never print as
        // "you have never run one".
        assertNull(barShortfallLine(SUB_30, BarStanding.Silent))
    }

    @Test
    fun `it offers nothing`() {
        val best = HistoryBestEffort(seconds = 1900.0, runStartedAtMillis = JUNE_14_2026)
        val line = barShortfallLine(SUB_30, BarStanding.Ranked(best))!!.lowercase()

        listOf("graduate", "unlock", "counts", "%").forEach {
            assertFalse(it, line.contains(it))
        }
    }
}

/** "30:00" back to 1800.0, so a test can do arithmetic on what the line printed. */
private fun asSeconds(clock: String): Double {
    val (minutes, seconds) = clock.split(":")
    return minutes.toDouble() * 60 + seconds.toDouble()
}
