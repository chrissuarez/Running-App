package com.example.runningapp.routes

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a Run is watched against, and what happens to it when the library moves (#58).
 *
 * The line itself is [CourseLineTest]'s and [OffCourseTest]'s subject, and what a course has to say
 * is [CourseAlertsTest]'s; what is asserted here is which line arrives, and whether one arrives at
 * all.
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

    /** [straightKilometre] as the library hands it back — kept to five decimal places. */
    private val straightKilometreAsKept = RoutePolyline.decode(RoutePolyline.encode(straightKilometre))

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
        val course = courseToWatchFlow(FakeRouteDao(), routeId = null, reversed = false).first()

        assertEquals(emptyList<RoutePoint>(), course)
    }

    @Test
    fun `a routed Run is watched against the course it set out on`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)

        val course = courseToWatchFlow(dao, routeId, reversed = false).first()

        assertEquals(straightKilometreAsKept, course)
    }

    @Test
    fun `a Route deleted mid-Run leaves nothing to be off`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)

        val courses = mutableListOf<List<RoutePoint>>()
        backgroundScope.launch { courseToWatchFlow(dao, routeId, reversed = false).toList(courses) }
        runCurrent()
        dao.deleteRoute(routeId)
        runCurrent()

        assertEquals(listOf(straightKilometreAsKept, emptyList()), courses)
    }

    /**
     * A write anywhere in the routes table hands this query its row again — Room watches the table,
     * not the row. Renaming some other Route is not this course changing shape, and the watch has to
     * live through it: a runner already told they were off course, whose library is touched while
     * they are out there, still has to be told when they get back. One course handed over is one
     * watch ([CourseAlerts.follow]).
     */
    @Test
    fun `a write elsewhere in the library does not end the watch`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)
        val someOtherRoute = dao.keep(straightKilometre.take(3))

        val courses = mutableListOf<List<RoutePoint>>()
        backgroundScope.launch { courseToWatchFlow(dao, routeId, reversed = false).toList(courses) }
        runCurrent()

        dao.renameRoute(someOtherRoute, "Somewhere else entirely")
        runCurrent()

        assertEquals(1, courses.size)
    }

    /**
     * Nor does a write to this very row that leaves the line where it was — a Route is remeasured in
     * place when it is imported a second time, and the ground it covers has not moved.
     */
    @Test
    fun `remeasuring the Route does not end the watch`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)

        val courses = mutableListOf<List<RoutePoint>>()
        backgroundScope.launch { courseToWatchFlow(dao, routeId, reversed = false).toList(courses) }
        runCurrent()

        dao.remeasureRoute(routeId, distanceMeters = 1_234.0, elevationGainMeters = 5.0)
        runCurrent()

        assertEquals(1, courses.size)
    }

    /**
     * The same ground either way, handed over in the order the Run is running it: nothing to how far
     * off the line a runner is, and everything to which way the course bends ([courseTurnsOf]).
     */
    @Test
    fun `which way round the runner set off turns the line round and nothing else`() = runTest {
        val dao = FakeRouteDao()
        val routeId = dao.keep(straightKilometre)

        val forwards = courseToWatchFlow(dao, routeId, reversed = false).first()
        val backwards = courseToWatchFlow(dao, routeId, reversed = true).first()

        assertEquals(straightKilometreAsKept, forwards)
        assertEquals(forwards.reversed(), backwards)
    }
}
