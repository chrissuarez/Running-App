package com.example.runningapp.routes

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteShapeRow
import com.example.runningapp.data.RouteSource
import com.example.runningapp.data.decoded
import com.example.runningapp.data.RouteShapeCandidate
import com.example.runningapp.segments.RUN_SHAPE_WAYPOINTS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a saved course comes to have a shape (#74) — the pass that pays for the library the runner
 * already had, and the write that measures a course as it is kept.
 *
 * The recognising itself is [RouteRunLinkTest]'s. What is here is the debt: which courses are owed a
 * shape, that paying is one line at a time ([com.example.runningapp.data.Route.polyline]'s first
 * rule), and that a course with no shape to take is written down as having none rather than being
 * read out of the library again at every launch for the rest of its life.
 */
class RouteShapingTest {

    /** A kilometre round the block, as a file would draw it. */
    private val theBlock = listOf(
        RoutePoint(51.5000, -0.1000, null),
        RoutePoint(51.5000, -0.0964, null),
        RoutePoint(51.5022, -0.0964, null),
        RoutePoint(51.5022, -0.1000, null),
        RoutePoint(51.5000, -0.1000, null),
    )

    /** A hundred metres down the road: a line, but not one worth calling a route. */
    private val downTheRoad = listOf(
        RoutePoint(51.5000, -0.1000, null),
        RoutePoint(51.5000, -0.0986, null),
    )

    private class Library : RouteShapeStore {
        val lines = mutableMapOf<Long, String>()
        val shapes = mutableMapOf<Long, RouteShapeRow>()

        /** Every ask for a line, in order — the rule about lines is about what a reader holds. */
        val lineAsks = mutableListOf<Long>()

        override suspend fun coursesMissingShapes() = lines.keys.filter { it !in shapes }.sorted()

        override suspend fun line(routeId: Long): String? {
            lineAsks += routeId
            return lines[routeId]
        }

        override suspend fun putShape(row: RouteShapeRow) {
            shapes[row.routeId] = row
        }
    }

    private fun Library.keep(routeId: Long, points: List<RoutePoint>) {
        lines[routeId] = RoutePolyline.encode(points)
    }

    @Test
    fun `the library the runner already had is measured at the first launch`() = runTest {
        val library = Library()
        library.keep(1L, theBlock)
        library.keep(2L, theBlock)

        RouteShaping(library).payWhatIsOwed()

        assertEquals(setOf(1L, 2L), library.shapes.keys)
        assertEquals(RUN_SHAPE_WAYPOINTS, RoutePolyline.decode(library.shapes.getValue(1L).shape!!).size)
    }

    @Test
    fun `a second launch measures nothing and reads no line at all`() = runTest {
        val library = Library()
        library.keep(1L, theBlock)
        RouteShaping(library).payWhatIsOwed()
        library.lineAsks.clear()

        RouteShaping(library).payWhatIsOwed()

        assertEquals(emptyList<Long>(), library.lineAsks)
    }

    @Test
    fun `a course too short to hold a route is written down as holding no shape`() = runTest {
        val library = Library()
        library.keep(1L, downTheRoad)

        RouteShaping(library).payWhatIsOwed()

        val row = library.shapes.getValue(1L)
        assertNull(row.shape)
        assertEquals(0.0, row.distanceMeters, 0.0)
        // And it is not asked for again, which is the whole reason the empty row is written.
        library.lineAsks.clear()
        RouteShaping(library).payWhatIsOwed()
        assertEquals(emptyList<Long>(), library.lineAsks)
    }

    @Test
    fun `a course that has gone is written as nothing at all`() = runTest {
        val library = Library()

        RouteShaping(library).shapeCourse(routeId = 404L)

        assertTrue(library.shapes.isEmpty())
    }

    @Test
    fun `each line is asked for on its own, one after another`() = runTest {
        val library = Library()
        library.keep(1L, theBlock)
        library.keep(2L, theBlock)
        library.keep(3L, theBlock)

        RouteShaping(library).payWhatIsOwed()

        // One ask per course, in the order the debt was listed: the pass never has two lines to hand
        // ([com.example.runningapp.data.Route.polyline]).
        assertEquals(listOf(1L, 2L, 3L), library.lineAsks)
    }

    @Test
    fun `keeping a course measures it in the same breath`() = runTest {
        val dao = FakeRouteDao()

        val kept = dao.keepRoute(
            Route(
                name = "Round the block",
                distanceMeters = 1000.0,
                elevationGainMeters = null,
                polyline = RoutePolyline.encode(theBlock),
                createdAtMillis = 100L,
                source = RouteSource.IMPORTED,
            ),
            remeasuring = false,
        )

        val row = dao.shapes.getValue(kept.id)
        assertNotNull(row.shape)
        assertEquals(
            RUN_SHAPE_WAYPOINTS,
            RouteShapeCandidate(kept.id, kept.name, row.shape!!, row.distanceMeters)
                .decoded()!!.waypoints.size,
        )
    }

    @Test
    fun `a course the library already held leaves the keeping measured too`() = runTest {
        val dao = FakeRouteDao()
        val course = Route(
            name = "Round the block",
            distanceMeters = 1000.0,
            elevationGainMeters = null,
            polyline = RoutePolyline.encode(theBlock),
            createdAtMillis = 100L,
            source = RouteSource.IMPORTED,
        )
        val first = dao.keepRoute(course, remeasuring = false)
        // A course kept before shapes existed at all: the row is there and its shape is not.
        dao.shapes.clear()

        val again = dao.keepRoute(course, remeasuring = false)

        assertEquals(first.id, again.id)
        assertNotNull(dao.shapes.getValue(again.id).shape)
    }
}
