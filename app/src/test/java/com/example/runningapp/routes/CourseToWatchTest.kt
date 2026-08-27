package com.example.runningapp.routes

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteSource
import com.example.runningapp.recording.LocationFix
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a Run is watched against, and what happens to it when the library moves (#58).
 *
 * The line itself is [CourseLineTest]'s and [OffCourseTest]'s subject; what is asserted here is
 * which line arrives, and whether one arrives at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CourseToWatchTest {

    private val originLatitude = 51.5
    private val originLongitude = -0.1
    private val metersPerDegreeLatitude = 111_132.0

    private fun at(northMeters: Double) = RoutePoint(
        latitude = originLatitude + northMeters / metersPerDegreeLatitude,
        longitude = originLongitude,
        elevationMeters = null,
    )

    private val straightKilometre = (0..10).map { at(it * 100.0) }

    private fun fixAt(eastMeters: Double) = LocationFix(
        latitude = at(500.0).latitude,
        longitude = originLongitude + eastMeters / 69_300.0,
        accuracyMeters = 5f,
        speedMps = 3f,
        timestampMs = 0L,
    )

    /** Halfway along the course, on the line. */
    private val onTheLine = fixAt(0.0)

    /** The same place, sixty metres east of it — past [OFF_COURSE_METERS], so worth a sentence. */
    private val offTheLine = fixAt(60.0)

    private suspend fun FakeRouteDao.keep(points: List<RoutePoint>): Long = insertRoute(
        Route(
            name = "A course",
            polyline = RoutePolyline.encode(points),
            distanceMeters = routeDistanceMeters(points),
            elevationGainMeters = null,
            createdAtMillis = 0L,
            source = RouteSource.IMPORTED,
        )
    )

    @Test
    fun `a Run following no course is watched against nothing`() = runTest {
        val watch = courseToWatchFlow(FakeRouteDao(), routeId = null, reversed = false).first()

        assertNull(watch)
    }

    @Test
    fun `a routed Run is watched against the course it set out on`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)

        val watch = courseToWatchFlow(dao, routeId, reversed = false).first()

        assertNotNull(watch)
    }

    @Test
    fun `a Route deleted mid-Run leaves nothing to be off`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)
        assertNotNull(courseToWatchFlow(dao, routeId, reversed = false).first())

        dao.deleteRoute(routeId)

        assertNull(courseToWatchFlow(dao, routeId, reversed = false).first())
    }

    /**
     * A write anywhere in the routes table hands this query its row again — Room watches the table,
     * not the row. Renaming some other Route is not this course changing shape, and the watch has to
     * live through it: a runner already told they were off course, whose library is touched while
     * they are out there, still has to be told when they get back.
     */
    @Test
    fun `a write elsewhere in the library does not end the watch`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)
        val someOtherRoute = dao.keep(straightKilometre.take(3))

        val watches = mutableListOf<OffCourseWatch?>()
        backgroundScope.launch { courseToWatchFlow(dao, routeId, reversed = false).toList(watches) }
        runCurrent()
        val watch = watches.single()!!
        watch.onFix(onTheLine, 0L, autoPaused = false)
        watch.onFix(offTheLine, 1_000L, autoPaused = false)
        assertEquals(CourseAlert.OFF_COURSE, watch.onFix(offTheLine, 11_000L, autoPaused = false))

        dao.renameRoute(someOtherRoute, "Somewhere else entirely")
        runCurrent()

        // One watch throughout, still holding what it said — so the runner is told they are back.
        assertEquals(1, watches.size)
        assertEquals(
            CourseAlert.BACK_ON_COURSE,
            watches.last()!!.onFix(onTheLine, 12_000L, autoPaused = false),
        )
    }

    /**
     * Nor does a write to this very row that leaves the line where it was — a Route is remeasured in
     * place when it is imported a second time, and the ground it covers has not moved.
     */
    @Test
    fun `remeasuring the Route does not end the watch`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)

        val watches = mutableListOf<OffCourseWatch?>()
        backgroundScope.launch { courseToWatchFlow(dao, routeId, reversed = false).toList(watches) }
        runCurrent()

        dao.remeasureRoute(routeId, distanceMeters = 1_234.0, elevationGainMeters = 5.0)
        runCurrent()

        assertEquals(1, watches.size)
    }

    @Test
    fun `which way round the runner set off does not change the line`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)

        val forwards = courseToWatchFlow(dao, routeId, reversed = false).first()!!
        val backwards = courseToWatchFlow(dao, routeId, reversed = true).first()!!

        // The same ground in the same places, so the same distance from it either way: a fix 60 m
        // east of the middle of the course is 60 m off the line whichever end it was started from.
        for (watch in listOf(forwards, backwards)) {
            watch.onFix(onTheLine, 0L, autoPaused = false)
            watch.onFix(offTheLine, 1_000L, autoPaused = false)
            assertEquals(
                CourseAlert.OFF_COURSE,
                watch.onFix(offTheLine, 11_000L, autoPaused = false),
            )
        }
    }
}
