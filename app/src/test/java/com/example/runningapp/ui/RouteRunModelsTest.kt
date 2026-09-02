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
        isWalk: Boolean = false,
    ) = RouteRunRow(
        sessionId = sessionId,
        startTime = firstStart + daysOn * aDay,
        ranAtUtcOffsetSeconds = 3_600,
        durationSeconds = durationSeconds,
        movingTimeSeconds = movingTimeSeconds,
        distanceKm = distanceKm,
        isWalk = isWalk,
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

    /**
     * A Walk holds no best time on a course, and is not in the average (#275, #420).
     *
     * The app's rule for every record over one piece of ground: a Walk contests no record
     * ([com.example.runningapp.data.RunnerSession.isWalk],
     * [com.example.runningapp.segments.mayHoldSegmentEfforts]). A course's best is that same claim,
     * so the quickest lap round it cannot be one the runner walked — even a quick one, and even
     * where its distance is exactly the course's.
     */
    @Test
    fun `a walk holds no best time and is not in the average`() {
        val runs = routeRunsUi(
            rows = listOf(
                run(sessionId = 1, daysOn = 0, distanceKm = 5.0, durationSeconds = 1_800),
                // Quicker than the Run above and dead on the course's length — and still not the
                // best, because the runner called it a walk.
                run(sessionId = 2, daysOn = 1, distanceKm = 5.0, durationSeconds = 600, isWalk = true),
            ),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        val walk = runs.single { it.sessionId == 2L }
        assertFalse("a walk was allowed to hold the best time", walk.isBest)
        assertFalse(walk.countsForBest)
        assertEquals(ROUTE_RUN_WALK_NOTE, walk.notCountedNote)

        val ran = runs.single { it.sessionId == 1L }
        assertTrue(ran.isBest)
        assertNull(ran.notCountedNote)

        // The average is the Run's own time alone, not the mean of the two.
        assertEquals("30:00", routeAverageTimeLabel(runs))
        assertEquals(1L, routeBestOf(runs)?.sessionId)
    }

    /**
     * A walk still keeps its row on the page.
     *
     * Never dropped: a Run that vanished from its own course's list with no explanation reads as
     * lost data, so it is printed with its time, its distance and the reason it takes no part.
     */
    @Test
    fun `a walk keeps its row, with its time and its distance`() {
        val runs = routeRunsUi(
            rows = listOf(run(sessionId = 2, daysOn = 1, distanceKm = 5.0, durationSeconds = 600, isWalk = true)),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        val walk = runs.single()
        assertEquals("10:00", walk.timeLabel)
        assertEquals(ROUTE_RUN_WALK_NOTE, walk.notCountedNote)
        // Nothing to compare it against, and the page says so rather than showing a best.
        assertNull(routeBestOf(runs))
        assertNull(routeAverageTimeLabel(runs))
    }

    /**
     * The two reasons a Run takes no part are told apart in the runner's own words.
     *
     * "Too far off this route's distance" said about a lap somebody walked the whole way round would
     * simply be untrue, and the two are not the same news: one is about the outing, the other about
     * a mark the runner made and can unmake on the Run's own page.
     */
    @Test
    fun `the reason a run takes no part names the reason`() {
        val runs = routeRunsUi(
            rows = listOf(
                run(sessionId = 1, daysOn = 0, distanceKm = 2.0, durationSeconds = 900),
                run(sessionId = 2, daysOn = 1, distanceKm = 5.0, durationSeconds = 600, isWalk = true),
            ),
            routeDistanceMeters = 5_000.0,
            zone = london,
        )

        assertEquals(ROUTE_RUN_NOT_COUNTED_NOTE, runs.single { it.sessionId == 1L }.notCountedNote)
        assertEquals(ROUTE_RUN_WALK_NOTE, runs.single { it.sessionId == 2L }.notCountedNote)
    }

}
