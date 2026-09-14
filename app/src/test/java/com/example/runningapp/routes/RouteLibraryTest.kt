package com.example.runningapp.routes

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteLastRunRow
import com.example.runningapp.data.RouteRunRow
import com.example.runningapp.data.RouteShapeCandidate
import com.example.runningapp.data.RouteSource
import com.example.runningapp.data.ShapedRunRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Route library's rules, with no screen in front of them (#480): who a course's siblings are,
 * which length a family's page lands on, what a family name, a rename, a flip and a delete write,
 * and how each course's drawing is fetched.
 *
 * The screen state built on top of these is `RoutesViewModelTest`'s; the pure folds under them are
 * `RouteFamilyTest`'s. What is worth a test here is that the table and the rules are joined up.
 */
class RouteLibraryTest {

    private val dispatcher = StandardTestDispatcher()

    private val dao = FakeRouteDao()

    /** When each course was last run, as [RouteLibrary.landingSibling] asks it. */
    private var lastRuns = emptyList<RouteLastRunRow>()

    /** Every Run remembered on a course, as a course's page asks it. */
    private val remembered = MutableStateFlow<List<RouteRunRow>>(emptyList())

    private fun library(
        courseShape: (Long) -> Flow<RouteShapeCandidate?> = { flowOf(null) },
        shaped: List<ShapedRunRow> = emptyList(),
    ) = RouteLibrary(
        dao,
        runsAlongRoute = { remembered },
        lastRunOnRoutes = { ids -> lastRuns.filter { it.routeId in ids } },
        courseShape = courseShape,
        shapedRuns = flowOf(shaped),
        measuring = dispatcher,
    )

    private val aLine = RoutePolyline.encode(
        listOf(
            RoutePoint(51.5, -0.1, elevationMeters = null),
            RoutePoint(51.51, -0.1, elevationMeters = null),
        )
    )

    private suspend fun givenACourse(
        name: String,
        distanceMeters: Double = 5_000.0,
        family: String? = null,
        polyline: String = aLine + name,
    ): Long = dao.insertRoute(
        Route(
            name = name,
            distanceMeters = distanceMeters,
            elevationGainMeters = null,
            polyline = polyline,
            createdAtMillis = 1_700_000_000_000L,
            source = RouteSource.IMPORTED,
            family = family,
        )
    )

    /** Course shapes read off the shapes table as it stands when asked — before a debt is paid, none. */
    private val shapesFromTheTable: (Long) -> Flow<RouteShapeCandidate?> = { routeId ->
        flowOf(
            dao.shapes[routeId]?.let { row ->
                row.shape?.let {
                    RouteShapeCandidate(
                        routeId = routeId,
                        name = "Course $routeId",
                        shape = it,
                        distanceMeters = row.distanceMeters,
                    )
                }
            }
        )
    }

    // --- Families (#421) ---

    @Test
    fun `the chips are every length of the family, shortest first`() = runTest(dispatcher) {
        val fiveK = givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
        val twelveK = givenACourse("Cuckoo 12k", 12_000.0, family = "Cuckoo Trail")
        val eightK = givenACourse("Cuckoo 8k", 8_000.0, family = "Cuckoo Trail")

        assertEquals(listOf(fiveK, eightK, twelveK), library().siblings(twelveK).first().map { it.id })
    }

    @Test
    fun `a course in no family has itself and no other chip`() = runTest(dispatcher) {
        val lone = givenACourse("Park loop", 4_000.0)
        givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")

        assertEquals(listOf(lone), library().siblings(lone).first().map { it.id })
    }

    @Test
    fun `the box offers the family names the library already holds`() = runTest(dispatcher) {
        givenACourse("Cuckoo 5k", family = "Cuckoo Trail")
        givenACourse("Cuckoo 8k", family = "Cuckoo Trail")
        givenACourse("Downs 10k", family = "Downs")
        givenACourse("Park loop")

        assertEquals(listOf("Cuckoo Trail", "Downs"), library().familyNames.first())
    }

    @Test
    fun `a family name is written onto the course`() = runTest(dispatcher) {
        val fiveK = givenACourse("Cuckoo 5k")

        library().setFamily(fiveK, "Cuckoo Trail")

        assertEquals("Cuckoo Trail", dao.stored.single().family)
    }

    @Test
    fun `an emptied box takes the course back out of its family`() = runTest(dispatcher) {
        val fiveK = givenACourse("Cuckoo 5k", family = "Cuckoo Trail")

        library().setFamily(fiveK, "   ")

        assertNull(dao.stored.single().family)
    }

    // --- Where a family's page lands (#421, #436, #440) ---

    @Test
    fun `a family whose last run was the 8k opens on the 8k`() = runTest(dispatcher) {
        val fiveK = givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
        val eightK = givenACourse("Cuckoo 8k", 8_000.0, family = "Cuckoo Trail")
        givenACourse("Cuckoo 12k", 12_000.0, family = "Cuckoo Trail")
        lastRuns = listOf(RouteLastRunRow(fiveK, 1_000), RouteLastRunRow(eightK, 9_000))

        assertEquals(eightK, library().landingSibling(fiveK))
    }

    @Test
    fun `a family nobody has run opens on its shortest length`() = runTest(dispatcher) {
        val fiveK = givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
        val twelveK = givenACourse("Cuckoo 12k", 12_000.0, family = "Cuckoo Trail")

        assertEquals(fiveK, library().landingSibling(twelveK))
    }

    /** #436: run before it was imported, so nothing was written down on any length. */
    @Test
    fun `a family whose lengths were only ever recognised opens on the one run last`() =
        runTest(dispatcher) {
            val fiveK = givenACourse("Cuckoo 5k", family = "Cuckoo Trail", polyline = lineAt(51.5))
            val eightK = givenACourse("Cuckoo 8k", family = "Cuckoo Trail", polyline = lineAt(52.5))
            dao.takeTheShapesStillOwed()

            val landing = library(
                courseShape = shapesFromTheTable,
                shaped = listOf(runOver(shapeAt(52.5), sessionId = 7, startTime = 9_000)),
            ).landingSibling(fiveK)

            assertEquals(eightK, landing)
        }

    /**
     * #440: opened on the launch still measuring the library, so neither length has a shape yet.
     * The landing pays its own family's debt first, and only that family's.
     */
    @Test
    fun `a family opened while its own shapes are still owed pays them and lands on the length run last`() =
        runTest(dispatcher) {
            val fiveK = givenACourse("Cuckoo 5k", family = "Cuckoo Trail", polyline = lineAt(51.5))
            val eightK = givenACourse("Cuckoo 8k", family = "Cuckoo Trail", polyline = lineAt(52.5))
            val elsewhere = givenACourse("Park loop", family = "Park", polyline = lineAt(53.5))
            assertEquals(listOf(fiveK, eightK, elsewhere), dao.coursesOwedShapes())

            val landing = library(
                courseShape = shapesFromTheTable,
                shaped = listOf(runOver(shapeAt(52.5), sessionId = 7, startTime = 9_000)),
            ).landingSibling(fiveK)

            assertEquals(eightK, landing)
            assertEquals(listOf(elsewhere), dao.coursesOwedShapes())
        }

    @Test
    fun `a course in no family opens on itself, whatever else has been run`() = runTest(dispatcher) {
        val lone = givenACourse("Park loop", 4_000.0)
        lastRuns = listOf(RouteLastRunRow(routeId = 99, 9_000))

        assertEquals(lone, library().landingSibling(lone))
    }

    @Test
    fun `a course deleted before its page opened lands nowhere`() = runTest(dispatcher) {
        assertNull(library().landingSibling(404L))
    }

    // --- Rename, flip, delete ---

    @Test
    fun `a rename is trimmed`() = runTest(dispatcher) {
        givenACourse("Park loop")
        val library = library()

        library.rename(library.route(1L).first()!!, "  Canal towpath  ")

        assertEquals("Canal towpath", dao.stored.single().name)
    }

    /** An emptied box is a change of mind, not a request for a Route with no name. */
    @Test
    fun `an emptied box leaves the course named what it was`() = runTest(dispatcher) {
        givenACourse("Park loop")
        val library = library()

        library.rename(library.route(1L).first()!!, "   ")

        assertEquals("Park loop", dao.stored.single().name)
    }

    @Test
    fun `flipping a course turns it round and leaves its line alone, and again puts it back`() =
        runTest(dispatcher) {
            val routeId = givenACourse("Park loop", polyline = aLine)
            val library = library()

            library.flip(routeId)
            assertTrue(library.route(routeId).first()!!.flipped)
            assertEquals(aLine, dao.stored.single().polyline)

            library.flip(routeId)
            assertFalse(library.route(routeId).first()!!.flipped)
        }

    @Test
    fun `a deleted course is gone from the library`() = runTest(dispatcher) {
        val routeId = givenACourse("Park loop")

        library().delete(routeId)

        assertTrue(dao.stored.isEmpty())
    }

    // --- One course's line and drawing (#59, #403) ---

    @Test
    fun `a course's line comes back on its own, and a gone course has none`() = runTest(dispatcher) {
        val routeId = givenACourse("Park loop", polyline = aLine)

        assertEquals(2, library().line(routeId).size)
        assertTrue(library().line(404L).isEmpty())
    }

    /** One line at a time and never listed — the rule #403 states — and a gone course draws nothing. */
    @Test
    fun `each course is drawn from its own line, asked for once`() = runTest(dispatcher) {
        val east = givenACourse("East", polyline = "51.5000000,-0.1000000 51.5000000,-0.0980000")
        val dot = givenACourse("Dot", polyline = "51.5000000,-0.1000000")

        val drawn = library().thumbnailsOf(listOf(east, dot, 404L))

        assertNotNull(drawn.getValue(east))
        assertNull(drawn.getValue(dot))
        assertNull(drawn.getValue(404L))
        assertEquals(listOf(east, dot, 404L), dao.lineAsks)
    }

    // --- The Runs on one course (#420, #74) ---

    @Test
    fun `a course's runs are the remembered ones and the ones recognised on its ground`() =
        runTest(dispatcher) {
            val routeId = givenACourse("Park loop", polyline = lineAt(51.5))
            dao.takeTheShapesStillOwed()
            remembered.value = listOf(runOver(shapeAt(51.5), sessionId = 4, startTime = 1_000).run)

            val read = library(
                courseShape = shapesFromTheTable,
                shaped = listOf(
                    runOver(shapeAt(51.5), sessionId = 4, startTime = 1_000),
                    runOver(shapeAt(51.5), sessionId = 7, startTime = 2_000),
                    runOver(shapeAt(53.5), sessionId = 9, startTime = 3_000),
                ),
            ).runsOn(routeId).first()!!

            assertEquals("Park loop", read.course.name)
            assertEquals(listOf(4L, 7L), read.runs.map { it.sessionId })
        }

    @Test
    fun `a course still owed its shape has only its remembered runs`() = runTest(dispatcher) {
        val routeId = givenACourse("Park loop", polyline = lineAt(51.5))
        remembered.value = listOf(runOver(shapeAt(51.5), sessionId = 4, startTime = 1_000).run)

        val read = library(
            shaped = listOf(runOver(shapeAt(51.5), sessionId = 7, startTime = 2_000)),
        ).runsOn(routeId).first()!!

        assertEquals(listOf(4L), read.runs.map { it.sessionId })
    }

    @Test
    fun `a course that is gone has no runs to read`() = runTest(dispatcher) {
        remembered.value = listOf(runOver(shapeAt(51.5), sessionId = 4, startTime = 1_000).run)

        assertNull(library().runsOn(404L).first())
    }
}
