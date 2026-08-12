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
    fun `a treadmill run's stated distance contests the longest distance`() {
        // The winter the record book could not see (#231, ADR 0008): a stated distance is a
        // distance, so the longest Run of an indoor season is the longest Run.
        val run = aRun(runMode = "treadmill").copy(distanceKm = 42.0)

        val efforts = bestEffortsOf(run, track = emptyList())

        assertEquals(42_000.0, efforts.valueOf(RecordType.LONGEST_DISTANCE)!!, 0.001)
        assertEquals(600.0, efforts.valueOf(RecordType.LONGEST_DURATION)!!, 0.001)
    }

    @Test
    fun `a treadmill run told nothing contests none of the fastest five, however far it went`() {
        // Not a matter of trust: a Best Effort is a stretch found inside the Run, and there is no
        // track to find one in. Nothing derives one from the average pace to get around that.
        val run = aRun(runMode = "treadmill").copy(distanceKm = 42.0)

        val efforts = bestEffortsOf(run, track = emptyList())

        assertEquals(
            listOf(RecordType.LONGEST_DISTANCE, RecordType.LONGEST_DURATION),
            efforts.map { it.type },
        )
    }

    // --- What a treadmill Run is told it holds (#282, ADR 0015) ------------------------------

    @Test
    fun `a treadmill run contests the distances it was told, and only those`() {
        // The console shows lap times, so one Run can honestly report a 1 km and a 5 km. They are
        // two claims about two stretches and neither says anything about the other — including
        // anything about the 10 km nobody mentioned.
        val run = aRun(runMode = "treadmill").copy(distanceKm = 12.0)

        val efforts = bestEffortsOf(
            run,
            track = emptyList(),
            stated = mapOf(RecordType.FASTEST_1K to 280.0, RecordType.FASTEST_5K to 1_500.0),
        )

        assertEquals(280.0, efforts.valueOf(RecordType.FASTEST_1K)!!, 0.001)
        assertEquals(1_500.0, efforts.valueOf(RecordType.FASTEST_5K)!!, 0.001)
        assertNull(efforts.valueOf(RecordType.FASTEST_10K))
        assertNull(efforts.valueOf(RecordType.FASTEST_MILE))
    }

    @Test
    fun `a stated best effort is ranked exactly as a measured one is`() {
        // The whole of what "placed in the record book like a measured one" means: the book cannot
        // tell them apart, so a stated 24:00 beats a measured 25:00 and loses to a measured 23:00.
        val stated = bestEffortsOf(
            aRun(runMode = "treadmill").copy(distanceKm = 6.0),
            track = emptyList(),
            stated = mapOf(RecordType.FASTEST_5K to 1_440.0),
        )

        val book = listOf(
            Achievement(sessionId = 1, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_380.0),
            Achievement(sessionId = 2, type = RecordType.FASTEST_5K, medal = Medal.SILVER, value = 1_500.0),
        )
        val after = standingsAfter(
            book,
            sessionId = 3,
            // Only the record under test: this Run contests the two totals as well, and their
            // standings say nothing about how a stated time is ranked.
            efforts = stated.filter { it.type == RecordType.FASTEST_5K },
        )

        assertEquals(
            listOf(1L to Medal.GOLD, 3L to Medal.SILVER, 2L to Medal.BRONZE),
            after.map { it.sessionId to it.medal },
        )
    }

    @Test
    fun `a whole-run distance and duration claim no best effort at any shorter distance`() {
        // The derivation ADR 0008 rejects and ADR 0015 is built on refusing: 6 km in 30:00 is not a
        // 5 km in 25:00, and the only way this Run ever holds a 5 km is to be told one.
        val run = aRun(runMode = "treadmill").copy(distanceKm = 6.0, durationSeconds = 1_800)

        val efforts = bestEffortsOf(run, track = emptyList())

        assertNull(efforts.valueOf(RecordType.FASTEST_5K))
        assertNull(efforts.valueOf(RecordType.FASTEST_1K))
        assertEquals(6_000.0, efforts.valueOf(RecordType.LONGEST_DISTANCE)!!, 0.001)
    }

    @Test
    fun `an outdoor run's efforts are measured, and a statement cannot reach them`() {
        // Nothing offers an outdoor Run a statement, and if something did the measuring would still
        // be the answer: no Run ever holds a measured effort and a stated one at the same record.
        // Longer than the kilometre being measured, so the rolling window has a whole one to find.
        val track = script { running(speedMps = 5.0, seconds = 250) } // 1250 m at 3:20/km

        val efforts = bestEffortsOf(
            anOutdoorRun(distanceKm = 1.25),
            track,
            stated = mapOf(RecordType.FASTEST_1K to 100.0),
        )

        assertEquals(200.0, efforts.valueOf(RecordType.FASTEST_1K)!!, 2.0)
    }

    @Test
    fun `a run still being recorded is worth nothing, whatever it has been told`() {
        val unfinished = aRun(runMode = "treadmill").copy(distanceKm = 6.0, endTime = 0)

        val efforts = bestEffortsOf(
            unfinished,
            track = emptyList(),
            stated = mapOf(RecordType.FASTEST_5K to 1_440.0),
        )

        assertTrue(efforts.isEmpty())
    }

    @Test
    fun `a treadmill run nobody stated a distance for contests the longest time only`() {
        // Zero is what a Run with no stated distance stores, and it is an absence rather than a Run
        // of no length — so it enters nothing.
        val efforts = bestEffortsOf(aRun(runMode = "treadmill"), track = emptyList())

        assertEquals(listOf(RecordType.LONGEST_DURATION), efforts.map { it.type })
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
    fun `a stretch may be joined across a gap in the recording`() {
        // Half a kilometre, the signal lost over the next half, then half a kilometre more. Neither
        // side is a kilometre on its own, but the runner covered one: the straight line across the
        // gap and every second it took, both leaning the effort slower rather than faster (#204).
        //
        // 4 m/s either side and 4 m/s across the gap, so the fastest kilometre is the 250s one that
        // fits inside any of the three — the ground it crosses is measured, not assumed.
        val track = script {
            running(speedMps = 4.0, seconds = 125)
            gap(meters = 500.0, seconds = 125)
            running(speedMps = 4.0, seconds = 125)
        }

        val efforts = bestEffortsOf(anOutdoorRun(distanceKm = 1.5), track)

        assertEquals(250.0, efforts.valueOf(RecordType.FASTEST_1K)!!, 5.0)
    }

    @Test
    fun `a gap the runner walked is not read as a sprint`() {
        // The same shape, but the signal was lost for five minutes over only fifty metres — the
        // runner was barely moving. The kilometre that spans it is charged all three hundred of
        // those seconds, so it reads as the slog it was rather than as a fast kilometre: 1050 m in
        // 550s, of which the fastest kilometre is all but the opening fifty metres.
        val track = script {
            running(speedMps = 4.0, seconds = 125)
            gap(meters = 50.0, seconds = 300)
            running(speedMps = 4.0, seconds = 125)
        }

        val efforts = bestEffortsOf(anOutdoorRun(distanceKm = 1.05), track)

        assertEquals(537.5, efforts.valueOf(RecordType.FASTEST_1K)!!, 10.0)
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

    @Test
    fun `asking for one record measures that record and no other`() {
        val track = script { running(speedMps = 4.0, seconds = 1_500) } // 6 km at 4:10/km

        val efforts = bestEffortsOf(
            anOutdoorRun(distanceKm = 6.0),
            track,
            types = listOf(RecordType.FASTEST_MILE),
        )

        // Every fixed distance is its own rolling window over the whole track, so a repair rebuilding
        // one record must not pay for the four it was not asked about, once per run in history.
        assertEquals(listOf(RecordType.FASTEST_MILE), efforts.map { it.type })
        assertEquals(402.0, efforts.valueOf(RecordType.FASTEST_MILE)!!, 4.0)
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
        // else, so a Run that contested one record cannot disturb the six it did not.
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

    // --- The book built from the whole history (#50) -------------------------------------------

    @Test
    fun `the book keeps the three best efforts of a whole history, in order`() {
        val book = recordBookOf(
            listOf(
                RunEfforts(sessionId = 1, efforts = listOf(anEffort(320.0))),
                RunEfforts(sessionId = 2, efforts = listOf(anEffort(300.0))),
                RunEfforts(sessionId = 3, efforts = listOf(anEffort(400.0))),
                RunEfforts(sessionId = 4, efforts = listOf(anEffort(310.0))),
            )
        )

        assertEquals(
            listOf(2L to Medal.GOLD, 4L to Medal.SILVER, 1L to Medal.BRONZE),
            book.map { it.sessionId to it.medal },
        )
    }

    @Test
    fun `an earlier run keeps a record a later one only matches`() {
        val book = recordBookOf(
            listOf(
                RunEfforts(sessionId = 1, efforts = listOf(anEffort(300.0))),
                RunEfforts(sessionId = 2, efforts = listOf(anEffort(300.0))),
            )
        )

        assertEquals(listOf(1L to Medal.GOLD, 2L to Medal.SILVER), book.map { it.sessionId to it.medal })
    }

    @Test
    fun `each record is ranked among only the runs that contested it`() {
        val book = recordBookOf(
            listOf(
                RunEfforts(sessionId = 1, efforts = listOf(BestEffort(RecordType.LONGEST_DURATION, 3_600.0))),
                RunEfforts(sessionId = 2, efforts = listOf(anEffort(300.0), BestEffort(RecordType.LONGEST_DURATION, 1_800.0))),
                RunEfforts(sessionId = 3, efforts = emptyList()),
            )
        )

        assertEquals(
            listOf(2L to Medal.GOLD),
            book.filter { it.type == RecordType.FASTEST_1K }.map { it.sessionId to it.medal },
        )
        // The longest time is won by the largest number, and the run that contested nothing is
        // nowhere in the book at all.
        assertEquals(
            listOf(1L to Medal.GOLD, 2L to Medal.SILVER),
            book.filter { it.type == RecordType.LONGEST_DURATION }.map { it.sessionId to it.medal },
        )
        assertTrue(book.none { it.sessionId == 3L })
    }

    @Test
    fun `a history with nothing worth recording makes an empty book`() {
        assertEquals(emptyList<Achievement>(), recordBookOf(listOf(RunEfforts(sessionId = 1, efforts = emptyList()))))
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
