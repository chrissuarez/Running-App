package com.example.runningapp.routes

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteSource
import com.example.runningapp.recording.LocationFix
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

    @Test
    fun `which way round the runner set off does not change the line`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)

        val forwards = courseToWatchFlow(dao, routeId, reversed = false).first()!!
        val backwards = courseToWatchFlow(dao, routeId, reversed = true).first()!!

        // The same ground in the same places, so the same distance from it either way: a fix 60 m
        // east of the middle of the course is 60 m off the line whichever end it was started from.
        val offTheLine = LocationFix(
            latitude = at(500.0).latitude,
            longitude = originLongitude + 60.0 / 69_300.0,
            accuracyMeters = 5f,
            speedMps = 3f,
            timestampMs = 0L,
        )
        val onTheLine = LocationFix(
            latitude = at(500.0).latitude,
            longitude = originLongitude,
            accuracyMeters = 5f,
            speedMps = 3f,
            timestampMs = 0L,
        )
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
