package com.example.runningapp.routes

import com.example.runningapp.data.RouteSource
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Keeping the ground a Run went over as a course to run again (#55). */
class RunRouteSaverTest {

    private val dao = FakeRouteDao()
    private val saver = RunRouteSaver(dao, now = { 1_700_000_500_000L })
    private val london = ZoneId.of("Europe/London")

    private val aRun = RunnerSession(
        id = 7,
        startTime = 1_700_000_000_000L,
        endTime = 1_700_000_600_000L,
        durationSeconds = 600,
        runMode = "outdoor",
    )

    /** A square kilometre-ish lap, with a hill in the middle of it. */
    private fun aLap(): List<TrackPoint> = listOf(
        0.0 to 0.0,
        500.0 to 0.0,
        500.0 to 500.0,
        0.0 to 500.0,
    ).mapIndexed { i, (north, east) ->
        TrackPoint(
            sessionId = 7,
            latitude = 51.5 + north / 111_320.0,
            longitude = -0.1 + east / (111_320.0 * 0.6225),
            altitudeMeters = 10.0 + i * 20.0,
            timestampMillis = 1_700_000_000_000L + i * 60_000L,
            source = TrackPointSource.GPS,
        )
    }

    @Test
    fun `the run's ground becomes a course in the library`() = runTest {
        val outcome = saver.save(aRun, aLap(), london)

        val route = dao.stored.single()
        assertEquals(RunRouteOutcome.Saved(route.id, route.name), outcome)
        // Named for the Run it was taken from, in the same words the Run is exported under.
        assertEquals("Run 14 Nov 2023, 22:13", route.name)
        assertTrue(route.distanceMeters > 1_400.0)
        assertNotNull(route.elevationGainMeters)
        assertEquals(1_700_000_500_000L, route.createdAtMillis)
        assertEquals(RouteSource.FROM_RUN, route.source)
        assertEquals(4, RoutePolyline.decode(route.polyline).size)
    }

    @Test
    fun `keeping the same run twice keeps one course`() = runTest {
        val first = saver.save(aRun, aLap(), london)
        val again = saver.save(aRun, aLap(), london)

        assertTrue(first is RunRouteOutcome.Saved)
        assertEquals(RunRouteOutcome.AlreadySaved(dao.stored.single().name), again)
        assertEquals(1, dao.stored.size)
    }

    /**
     * The runner taps the button twice before the first tap has finished — an impatient double-tap,
     * which is all it takes, since each tap saves on a scope of its own.
     *
     * The library must still hold one course afterwards. Asked whether it already has this line and
     * only then told to keep it, both taps would have been told "no" before either wrote, and the
     * runner would be left with the same course on two rows that nothing in the table can tell apart.
     */
    @Test
    fun `two taps at once keep one course`() = runTest {
        // Long enough for the second tap to arrive while the first is still deciding.
        dao.findDelayMillis = 50

        val outcomes = listOf(
            async { saver.save(aRun, aLap(), london) },
            async { saver.save(aRun, aLap(), london) },
        ).awaitAll()

        val kept = dao.stored.single()
        // One kept it, the other was sent back to the row that keeping it made.
        assertEquals(
            setOf(RunRouteOutcome.Saved(kept.id, kept.name), RunRouteOutcome.AlreadySaved(kept.name)),
            outcomes.toSet(),
        )
    }

    /** The runner renamed it, and the row they already have is the one to send them back to. */
    @Test
    fun `a course already kept is named as the runner named it`() = runTest {
        saver.save(aRun, aLap(), london)
        dao.renameRoute(dao.stored.single().id, "Park lap")

        assertEquals(RunRouteOutcome.AlreadySaved("Park lap"), saver.save(aRun, aLap(), london))
    }

    @Test
    fun `a run that recorded no ground is no course`() = runTest {
        assertEquals(RunRouteOutcome.NoGround, saver.save(aRun, emptyList(), london))
        assertTrue(dao.stored.isEmpty())
    }

    /**
     * A Run that never left the spot: two hundred fixes wandering inside the error of the gate that
     * accepted them. The line they make is hundreds of metres long and goes nowhere, which is why
     * how far a course *reaches* is what decides it.
     */
    @Test
    fun `a run that never left the spot is no course`() = runTest {
        val scattered = (0..199).map { step ->
            TrackPoint(
                sessionId = 7,
                latitude = 51.5 + (if (step % 2 == 0) 8.0 else -8.0) / 111_320.0,
                longitude = -0.1 + (if (step % 4 < 2) 6.0 else -6.0) / (111_320.0 * 0.6225),
                timestampMillis = 1_700_000_000_000L + step * 1_000L,
                source = TrackPointSource.GPS,
            )
        }

        assertEquals(RunRouteOutcome.NoGround, saver.save(aRun, scattered, london))
        assertTrue(dao.stored.isEmpty())
    }

    /**
     * The climb is read off what the Run recorded rather than off the thinned line, because a road
     * straight up a hill and down the other side has no bend in it to keep the crest for.
     */
    @Test
    fun `the climb of a straight hill survives being kept`() = runTest {
        val overAHill = (0..40).map { step ->
            TrackPoint(
                sessionId = 7,
                latitude = 51.5 + step * 25.0 / 111_320.0,
                longitude = -0.1,
                altitudeMeters = 10.0 + if (step <= 20) step * 5.0 else (40 - step) * 5.0,
                timestampMillis = 1_700_000_000_000L + step * 10_000L,
                source = TrackPointSource.GPS,
            )
        }

        saver.save(aRun, overAHill, london)

        // A climb rather than the nought the two-point line it was thinned to would report; the
        // smoothing shaves its shoulders, which is RouteShape's own bargain.
        assertTrue(dao.stored.single().elevationGainMeters!! > 70.0)
    }

    /**
     * History lists a Run from the moment it starts, so its page can be opened with the runner still
     * on it — and a Route's numbers are banked once and never re-measured (ADR 0014).
     */
    @Test
    fun `a run still being run is not a course yet`() = runTest {
        val stillRunning = aRun.copy(endTime = 0L)

        assertEquals(RunRouteOutcome.StillRunning, saver.save(stillRunning, aLap(), london))
        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `a course with no heights in it banks none`() = runTest {
        val flat = aLap().map { it.copy(altitudeMeters = null) }

        saver.save(aRun, flat, london)

        assertNull(dao.stored.single().elevationGainMeters)
    }
}
