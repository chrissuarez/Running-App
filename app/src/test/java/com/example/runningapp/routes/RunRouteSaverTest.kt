package com.example.runningapp.routes

import com.example.runningapp.data.RouteSource
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointSource
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
     * A Run that never left the spot: a few fixes scattered inside the error of the fix that
     * accepted them. There is a line to draw and no course in it.
     */
    @Test
    fun `a run that never left the spot is no course`() = runTest {
        val scattered = listOf(0.0 to 0.0, 12.0 to 4.0, 3.0 to 9.0).mapIndexed { i, (north, east) ->
            TrackPoint(
                sessionId = 7,
                latitude = 51.5 + north / 111_320.0,
                longitude = -0.1 + east / (111_320.0 * 0.6225),
                timestampMillis = 1_700_000_000_000L + i * 60_000L,
                source = TrackPointSource.GPS,
            )
        }

        assertEquals(RunRouteOutcome.NoGround, saver.save(aRun, scattered, london))
        assertTrue(dao.stored.isEmpty())
    }

    @Test
    fun `a course with no heights in it banks none`() = runTest {
        val flat = aLap().map { it.copy(altitudeMeters = null) }

        saver.save(aRun, flat, london)

        assertNull(dao.stored.single().elevationGainMeters)
    }
}
