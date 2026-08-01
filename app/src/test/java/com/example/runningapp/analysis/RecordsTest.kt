package com.example.runningapp.analysis

import com.example.runningapp.data.Achievement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a finished Run is worth against the record book (#49).
 *
 * Scripted runs throughout, the same way the splits table and the chart are tested: a run is laid
 * out as the things that happened to it, and the module is asked what it makes of it.
 */
class RecordsTest {

    // --- What a run's best efforts are -------------------------------------------------------

    @Test
    fun `the fastest kilometre is the fastest stretch anywhere in the run, not the fastest split`() {
        // Slow, then a fast kilometre straddling the first kilometre marker, then slow again. A
        // table cut at whole kilometres would never show this stretch; the record book must.
        val track = script {
            running(speedMps = 2.0, seconds = 250)  // 500 m at 8:20/km
            running(speedMps = 5.0, seconds = 200)  // 1000 m at 3:20/km
            running(speedMps = 2.0, seconds = 250)  // 500 m
        }

        val efforts = bestEffortsOf(anOutdoorRun(distanceKm = 2.0), track)

        assertEquals(200.0, efforts.valueOf(RecordType.FASTEST_1K)!!, 2.0)
    }

    @Test
    fun `a walk break inside the fastest stretch is counted against it`() {
        // A kilometre run at 4:00/km with a minute of walking in the middle of it: the effort is
        // measured on the clock, so the walk is part of the time it took.
        val track = script {
            running(speedMps = 4.0, seconds = 125)  // 500 m
            running(speedMps = 1.0, seconds = 60)   // 60 m of walking
            running(speedMps = 4.0, seconds = 120)  // 480 m
        }

        val efforts = bestEffortsOf(anOutdoorRun(distanceKm = 1.04), track)

        // Every kilometre of this run holds the walk, so the best of them is the last: 1000 m in
        // 295 s, not the 250 s the running seconds on their own would give.
        assertEquals(295.0, efforts.valueOf(RecordType.FASTEST_1K)!!, 3.0)
    }

    @Test
    fun `a run that never reaches a distance sets no record at it`() {
        val track = script { running(speedMps = 3.0, seconds = 200) } // 600 m

        val efforts = bestEffortsOf(anOutdoorRun(distanceKm = 0.6), track)

        assertNull(efforts.valueOf(RecordType.FASTEST_1K))
        assertNull(efforts.valueOf(RecordType.FASTEST_5K))
        // The volume records are still contested: a short run is still a run of some length.
        assertEquals(600.0, efforts.valueOf(RecordType.LONGEST_DISTANCE)!!, 0.001)
        assertEquals(600.0, efforts.valueOf(RecordType.LONGEST_DURATION)!!, 0.001)
    }

    @Test
    fun `a treadmill run contests the longest time and nothing else`() {
        // A treadmill run reports a distance of its own, and it was never measured against ground.
        val run = aRun(runMode = "treadmill").copy(distanceKm = 42.0)

        val efforts = bestEffortsOf(run, track = emptyList())

        assertEquals(listOf(RecordType.LONGEST_DURATION), efforts.map { it.type })
        assertEquals(600.0, efforts.valueOf(RecordType.LONGEST_DURATION)!!, 0.001)
    }

    @Test
    fun `an outdoor run with no usable track contests the longest time only`() {
        // A distance is stored and nothing is left of the route it was measured off: history from
        // before the app kept a track, or a run whose every fix was too vague to be trusted. The
        // distance record has to come from ground somebody can still see.
        val efforts = bestEffortsOf(anOutdoorRun(distanceKm = 12.0), track = emptyList())

        assertEquals(listOf(RecordType.LONGEST_DURATION), efforts.map { it.type })
    }

    @Test
    fun `a run recorded from sparse breadcrumbs still sets its records`() {
        // Backfilled history: one fix every fifteen seconds, fifty metres apart — 2 km at 5:00/km.
        val track = script { sparse(meters = 50.0, seconds = 15, fixes = 40) }

        val efforts = bestEffortsOf(anOutdoorRun(distanceKm = 2.0), track)

        assertEquals(300.0, efforts.valueOf(RecordType.FASTEST_1K)!!, 5.0)
    }

    @Test
    fun `a stretch cannot be joined across a gap in the recording`() {
        // Half a kilometre, the signal lost over the next half, then half a kilometre more. Neither
        // side is a kilometre on its own, and the middle was never witnessed.
        val track = script {
            running(speedMps = 4.0, seconds = 125)
            gap(meters = 500.0, seconds = 125)
            running(speedMps = 4.0, seconds = 125)
        }

        val efforts = bestEffortsOf(anOutdoorRun(distanceKm = 1.5), track)

        assertNull(efforts.valueOf(RecordType.FASTEST_1K))
    }

    @Test
    fun `a run still being recorded is worth nothing until it finishes`() {
        val track = script { running(speedMps = 4.0, seconds = 300) }
        val stillRunning = anOutdoorRun(distanceKm = 1.2).copy(endTime = 0)

        assertTrue(bestEffortsOf(stillRunning, track).isEmpty())
    }

    @Test
    fun `a stretch covering a longer distance also counts as the shorter ones`() {
        val track = script { running(speedMps = 4.0, seconds = 1_500) } // 6 km at 4:10/km

        val efforts = bestEffortsOf(anOutdoorRun(distanceKm = 6.0), track)

        assertEquals(250.0, efforts.valueOf(RecordType.FASTEST_1K)!!, 3.0)
        assertEquals(402.0, efforts.valueOf(RecordType.FASTEST_MILE)!!, 4.0)
        assertEquals(1_250.0, efforts.valueOf(RecordType.FASTEST_5K)!!, 6.0)
        assertNull(efforts.valueOf(RecordType.FASTEST_10K))
    }

    // --- Where an effort stands in the book ---------------------------------------------------

    @Test
    fun `a first effort at a distance takes the gold`() {
        val awarded = standingsAfter(book = emptyList(), sessionId = 7, efforts = listOf(anEffort(300.0)))

        assertEquals(listOf(7L to Medal.GOLD), awarded.map { it.sessionId to it.medal })
        assertEquals(300.0, awarded.single().value, 0.001)
    }

    @Test
    fun `a faster effort takes the gold and pushes the others down a place`() {
        val book = listOf(
            anAchievement(sessionId = 1, medal = Medal.GOLD, value = 300.0),
            anAchievement(sessionId = 2, medal = Medal.SILVER, value = 310.0),
            anAchievement(sessionId = 3, medal = Medal.BRONZE, value = 320.0),
        )

        val awarded = standingsAfter(book, sessionId = 4, efforts = listOf(anEffort(290.0)))

        assertEquals(
            listOf(4L to Medal.GOLD, 1L to Medal.SILVER, 2L to Medal.BRONZE),
            awarded.map { it.sessionId to it.medal },
        )
    }

    @Test
    fun `an effort slower than the bronze wins nothing and disturbs nothing`() {
        val book = listOf(
            anAchievement(sessionId = 1, medal = Medal.GOLD, value = 300.0),
            anAchievement(sessionId = 2, medal = Medal.SILVER, value = 310.0),
            anAchievement(sessionId = 3, medal = Medal.BRONZE, value = 320.0),
        )

        val awarded = standingsAfter(book, sessionId = 4, efforts = listOf(anEffort(400.0)))

        assertEquals(
            listOf(1L to Medal.GOLD, 2L to Medal.SILVER, 3L to Medal.BRONZE),
            awarded.map { it.sessionId to it.medal },
        )
    }

    @Test
    fun `matching a standing record does not take it`() {
        val book = listOf(anAchievement(sessionId = 1, medal = Medal.GOLD, value = 300.0))

        val awarded = standingsAfter(book, sessionId = 2, efforts = listOf(anEffort(300.0)))

        assertEquals(
            listOf(1L to Medal.GOLD, 2L to Medal.SILVER),
            awarded.map { it.sessionId to it.medal },
        )
    }

    @Test
    fun `scoring the same run twice leaves it holding one medal`() {
        val first = standingsAfter(book = emptyList(), sessionId = 7, efforts = listOf(anEffort(300.0)))

        val again = standingsAfter(book = first, sessionId = 7, efforts = listOf(anEffort(300.0)))

        assertEquals(listOf(7L to Medal.GOLD), again.map { it.sessionId to it.medal })
    }

    @Test
    fun `only three places are kept`() {
        val book = listOf(
            anAchievement(sessionId = 1, medal = Medal.GOLD, value = 300.0),
            anAchievement(sessionId = 2, medal = Medal.SILVER, value = 310.0),
            anAchievement(sessionId = 3, medal = Medal.BRONZE, value = 320.0),
        )

        val awarded = standingsAfter(book, sessionId = 4, efforts = listOf(anEffort(315.0)))

        assertEquals(3, awarded.size)
        assertTrue(awarded.none { it.sessionId == 3L })
    }

    @Test
    fun `a volume record is won by the largest number, not the smallest`() {
        val book = listOf(
            Achievement(sessionId = 1, type = RecordType.LONGEST_DISTANCE, medal = Medal.GOLD, value = 5_000.0)
        )

        val awarded = standingsAfter(
            book,
            sessionId = 2,
            efforts = listOf(BestEffort(RecordType.LONGEST_DISTANCE, 10_000.0)),
        )

        assertEquals(
            listOf(2L to Medal.GOLD, 1L to Medal.SILVER),
            awarded.map { it.sessionId to it.medal },
        )
    }

    @Test
    fun `a record the run did not contest is left alone`() {
        val book = listOf(
            anAchievement(sessionId = 1, medal = Medal.GOLD, value = 300.0),
            Achievement(sessionId = 1, type = RecordType.LONGEST_DISTANCE, medal = Medal.GOLD, value = 5_000.0),
        )

        val awarded = standingsAfter(book, sessionId = 2, efforts = listOf(anEffort(290.0)))

        // Only the type the run had an effort at comes back: the caller rewrites those and nothing
        // else, so a treadmill run can never disturb the distance records.
        assertEquals(setOf(RecordType.FASTEST_1K), awarded.map { it.type }.toSet())
    }

    @Test
    fun `a run scoring its efforts is told which of them earned a medal`() {
        val track = script { running(speedMps = 4.0, seconds = 300) } // 1.2 km
        val run = anOutdoorRun(distanceKm = 1.2)

        val awarded = standingsAfter(emptyList(), run.id, bestEffortsOf(run, track))

        assertEquals(
            setOf(RecordType.FASTEST_1K, RecordType.LONGEST_DISTANCE, RecordType.LONGEST_DURATION),
            awarded.map { it.type }.toSet(),
        )
        assertTrue(awarded.all { it.medal == Medal.GOLD && it.sessionId == run.id })
    }

    private fun anOutdoorRun(distanceKm: Double) = aRun().copy(distanceKm = distanceKm)

    private fun anEffort(seconds: Double) = BestEffort(RecordType.FASTEST_1K, seconds)

    private fun anAchievement(sessionId: Long, medal: Medal, value: Double) = Achievement(
        sessionId = sessionId,
        type = RecordType.FASTEST_1K,
        medal = medal,
        value = value,
    )

    private fun List<BestEffort>.valueOf(type: RecordType): Double? =
        firstOrNull { it.type == type }?.value
}
