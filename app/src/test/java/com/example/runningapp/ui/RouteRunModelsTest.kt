package com.example.runningapp.ui

import com.example.runningapp.data.RouteRunRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * What a Route's own page says about the Runs remembered on it (#420).
 *
 * Pure and outside the composable, the bargain every other screen's words make here
 * ([routeRowSubtitle], [segmentEffortsUi]): what the runner reads is pinned by a test rather than
 * by opening the page on a phone.
 */
class RouteRunModelsTest {

    private val london = ZoneId.of("Europe/London")

    /** 2024-06-01 09:00 UTC, and a day later for each step. */
    private val firstStart = 1_717_232_400_000L
    private val aDay = 86_400_000L

    private fun run(
        sessionId: Long,
        daysOn: Long,
        distanceKm: Double,
        durationSeconds: Long,
        movingTimeSeconds: Long? = null,
    ) = RouteRunRow(
        sessionId = sessionId,
        startTime = firstStart + daysOn * aDay,
        ranAtUtcOffsetSeconds = 3_600,
        durationSeconds = durationSeconds,
        movingTimeSeconds = movingTimeSeconds,
        distanceKm = distanceKm,
    )

    @Test
    fun `lists the runs newest first, with the day, the time and the distance actually run`() {
        val runs = routeRunsUi(
            rows = listOf(run(1, 0, 5.02, 1_500), run(2, 3, 4.98, 1_440)),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        assertEquals(listOf(2L, 1L), runs.map { it.sessionId })
        assertEquals("4 Jun 2024", runs[0].dateLabel)
        assertEquals("24:00", runs[0].timeLabel)
        assertEquals("4.98 km", runs[0].distanceLabel)
    }

    /** The clock a Run's own page shows: its moving time where it has one, its elapsed where it does not. */
    @Test
    fun `uses moving time where the run has one`() {
        val runs = routeRunsUi(
            rows = listOf(run(1, 0, 5.0, durationSeconds = 1_800, movingTimeSeconds = 1_500)),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        assertEquals("25:00", runs[0].timeLabel)
    }

    /**
     * The band is the whole of the best-time rule: a Run that went nowhere near this course's length
     * is not a slower attempt at it, and crowning one would hand the runner a record they never ran.
     */
    @Test
    fun `only a run within a tenth of the route's distance can take the best time`() {
        val runs = routeRunsUi(
            rows = listOf(
                run(1, 0, 5.0, 1_500),
                // 4.0 km against a 5 km course: a fifth short, so it is listed and not raced.
                run(2, 1, 4.0, 900),
            ),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        val short = runs.first { it.sessionId == 2L }
        val full = runs.first { it.sessionId == 1L }
        assertFalse(short.countsForBest)
        assertFalse(short.isBest)
        assertTrue(full.countsForBest)
        assertTrue(full.isBest)
    }

    /** Ten per cent exactly is inside the band — the boundary is the side the rule accepts. */
    @Test
    fun `a run exactly a tenth off still counts`() {
        val runs = routeRunsUi(
            rows = listOf(run(1, 0, 4.5, 1_500), run(2, 1, 5.5, 1_600)),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        assertTrue(runs.all { it.countsForBest })
    }

    /** A run outside the band is never hidden: a run that vanishes with no explanation reads as lost data. */
    @Test
    fun `a run outside the band is still listed`() {
        val runs = routeRunsUi(
            rows = listOf(run(1, 0, 1.0, 400)),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        assertEquals(1, runs.size)
        assertFalse(runs[0].countsForBest)
        assertNull(routeBestOf(runs))
    }

    /** A tie leaves the best with the earlier run: matching a time you already ran is not beating it. */
    @Test
    fun `a tie keeps the best with the earlier run`() {
        val runs = routeRunsUi(
            rows = listOf(run(1, 0, 5.0, 1_500), run(2, 5, 5.0, 1_500)),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        assertEquals(1L, routeBestOf(runs)?.sessionId)
    }

    /** A run with no clock at all cannot be raced, so it is listed and left out of the reckoning. */
    @Test
    fun `a run with no time on it never takes the best`() {
        val runs = routeRunsUi(
            rows = listOf(run(1, 0, 5.0, 0)),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        assertFalse(runs[0].countsForBest)
        assertNull(routeBestOf(runs))
    }

    @Test
    fun `averages only the runs that count`() {
        val runs = routeRunsUi(
            rows = listOf(
                run(1, 0, 5.0, 1_500),
                run(2, 1, 5.0, 1_700),
                // Way short: it must not drag the average down.
                run(3, 2, 2.0, 600),
            ),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        assertEquals("26:40", routeAverageTimeLabel(runs))
        assertEquals("25:00", routeBestOf(runs)?.timeLabel)
    }

    @Test
    fun `no average where nothing counts`() {
        val runs = routeRunsUi(
            rows = listOf(run(1, 0, 2.0, 600)),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        assertNull(routeAverageTimeLabel(runs))
    }

    /** A course with no length of its own has no band to measure anything against. */
    @Test
    fun `nothing counts against a route with no distance`() {
        val runs = routeRunsUi(
            rows = listOf(run(1, 0, 5.0, 1_500)),
            routeDistanceMeters = 0.0,
            zone = london,
        )

        assertFalse(runs[0].countsForBest)
    }

    @Test
    fun `counts the runs in the runner's own words`() {
        assertEquals("1 run on this route", routeRunCountLabel(1))
        assertEquals("14 runs on this route", routeRunCountLabel(14))
    }
}
