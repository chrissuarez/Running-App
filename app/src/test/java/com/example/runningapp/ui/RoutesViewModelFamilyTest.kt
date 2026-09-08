package com.example.runningapp.ui

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteLastRunRow
import com.example.runningapp.data.RouteShapeCandidate
import com.example.runningapp.data.RouteRunRow
import com.example.runningapp.data.RouteSource
import com.example.runningapp.data.ShapedRunRow
import com.example.runningapp.data.runShapeRowOf
import com.example.runningapp.routes.FakeRouteDao
import com.example.runningapp.routes.routeShapeOf
import com.example.runningapp.routes.RouteImporter
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.segments.RunShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * One route, many lengths, through the view model (#421): the folded library, the chips, the
 * landing, and the writing of a family name.
 *
 * The folding and the landing rules themselves are pinned by `RouteFamiliesTest`. What is worth a
 * test here is that the table and those rules are joined up: a family name written on this screen
 * has to reach the library row above it and the chips beside it, from the one table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutesViewModelFamilyTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val dao = FakeRouteDao()

    /** When each course was last run, as [RoutesViewModel.landingSibling] asks it. */
    private var lastRuns = emptyList<RouteLastRunRow>()

    private fun viewModel() = RoutesViewModel(
        dao,
        RouteImporter(mock(), dao, now = { 1_700_000_000_000L }),
        runsAlongRoute = { flowOf(emptyList()) },
        lastRunOnRoutes = { ids -> lastRuns.filter { it.routeId in ids } },
        courseShape = { flowOf(null) },
        shapedRuns = flowOf(emptyList()),
        io = dispatcher,
        courseDispatcher = dispatcher,
    )

    private val aLine = RoutePolyline.encode(
        listOf(
            RoutePoint(51.5, -0.1, elevationMeters = null),
            RoutePoint(51.51, -0.1, elevationMeters = null),
        )
    )

    private suspend fun givenACourse(
        name: String,
        distanceMeters: Double,
        family: String? = null,
        createdAtMillis: Long = 1_700_000_000_000L,
    ): Long = dao.insertRoute(
        Route(
            name = name,
            distanceMeters = distanceMeters,
            elevationGainMeters = null,
            polyline = aLine + name,
            createdAtMillis = createdAtMillis,
            source = RouteSource.IMPORTED,
            family = family,
        )
    )

    @Test
    fun `three courses given the same family name become one library row`() = runTest(dispatcher) {
        givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
        givenACourse("Cuckoo 8k", 8_000.0, family = "Cuckoo Trail")
        givenACourse("Cuckoo 12k", 12_000.0, family = "Cuckoo Trail")
        val viewModel = viewModel()

        advanceUntilIdle()

        val row = viewModel.libraryRows.value.single()
        assertEquals("Cuckoo Trail", row.title)
        assertEquals(3, row.lengthCount)
    }

    @Test
    fun `a family name typed on the page reaches the library row`() = runTest(dispatcher) {
        val fiveK = givenACourse("Cuckoo 5k", 5_000.0)
        givenACourse("Cuckoo 8k", 8_000.0, family = "Cuckoo Trail")
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(2, viewModel.libraryRows.value.size)

        viewModel.setFamily(viewModel.route(fiveK).first()!!, "Cuckoo Trail")
        advanceUntilIdle()

        assertEquals("Cuckoo Trail", viewModel.libraryRows.value.single().title)
        assertEquals(2, viewModel.libraryRows.value.single().lengthCount)
    }

    @Test
    fun `clearing the box takes the course back out of the family`() = runTest(dispatcher) {
        val fiveK = givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
        givenACourse("Cuckoo 8k", 8_000.0, family = "Cuckoo Trail")
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.setFamily(viewModel.route(fiveK).first()!!, "   ")
        advanceUntilIdle()

        assertNull(dao.stored.first { it.id == fiveK }.family)
        assertEquals(2, viewModel.libraryRows.value.size)
    }

    @Test
    fun `the chips are every length of the family, shortest first`() = runTest(dispatcher) {
        val fiveK = givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
        val twelveK = givenACourse("Cuckoo 12k", 12_000.0, family = "Cuckoo Trail")
        val eightK = givenACourse("Cuckoo 8k", 8_000.0, family = "Cuckoo Trail")
        val viewModel = viewModel()

        val chips = viewModel.siblings(twelveK).first()

        assertEquals(listOf(fiveK, eightK, twelveK), chips.map { it.id })
    }

    @Test
    fun `a course in no family has itself and no other chip`() = runTest(dispatcher) {
        val lone = givenACourse("Park loop", 4_000.0)
        givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
        val viewModel = viewModel()

        assertEquals(listOf(lone), viewModel.siblings(lone).first().map { it.id })
    }

    @Test
    fun `a family whose last run was the 8k opens on the 8k`() = runTest(dispatcher) {
        val fiveK = givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
        val eightK = givenACourse("Cuckoo 8k", 8_000.0, family = "Cuckoo Trail")
        givenACourse("Cuckoo 12k", 12_000.0, family = "Cuckoo Trail")
        lastRuns = listOf(RouteLastRunRow(fiveK, 1_000), RouteLastRunRow(eightK, 9_000))
        val viewModel = viewModel()

        assertEquals(eightK, viewModel.landingSibling(fiveK))
    }

    @Test
    fun `a family nobody has run opens on its shortest length`() = runTest(dispatcher) {
        val fiveK = givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
        val twelveK = givenACourse("Cuckoo 12k", 12_000.0, family = "Cuckoo Trail")
        val viewModel = viewModel()

        assertEquals(fiveK, viewModel.landingSibling(twelveK))
    }

    // --- The landing counts a length's recognised Runs too (#436) ---

    /** Ground far enough from [aLine] that the two are different courses. */
    private fun shapeAt(at: Double): RunShape = routeShapeOf(
        RoutePolyline.decode(
            RoutePolyline.encode(
                listOf(
                    RoutePoint(at, -0.1, elevationMeters = null),
                    RoutePoint(at + 0.02, -0.1, elevationMeters = null),
                )
            )
        )
    )!!

    private fun runOver(shape: RunShape, sessionId: Long, startTime: Long) = ShapedRunRow(
        run = RouteRunRow(
            sessionId = sessionId,
            startTime = startTime,
            ranAtUtcOffsetSeconds = 0,
            durationSeconds = 300,
            movingTimeSeconds = 300,
            distanceKm = shape.distanceMeters / 1_000.0,
        ),
        shape = runShapeRowOf(sessionId, shape).shape!!,
        shapeDistanceMeters = shape.distanceMeters,
    )

    private fun viewModelSeeing(
        shapes: Map<Long, RunShape>,
        shaped: List<ShapedRunRow>,
    ) = RoutesViewModel(
        dao,
        RouteImporter(mock(), dao, now = { 1_700_000_000_000L }),
        runsAlongRoute = { flowOf(emptyList()) },
        lastRunOnRoutes = { ids -> lastRuns.filter { it.routeId in ids } },
        courseShape = { routeId ->
            flowOf(
                shapes[routeId]?.let {
                    RouteShapeCandidate(
                        routeId = routeId,
                        name = "Course $routeId",
                        shape = runShapeRowOf(routeId, it).shape!!,
                        distanceMeters = it.distanceMeters,
                    )
                }
            )
        },
        shapedRuns = flowOf(shaped),
        io = dispatcher,
        courseDispatcher = dispatcher,
    )

    /**
     * The whole of #436: a family the runner ran before they imported it, so nothing was ever
     * written down on any of its lengths. It must still open on the one they ran last.
     */
    @Test
    fun `a family whose lengths were only ever recognised opens on the one run last`() =
        runTest(dispatcher) {
            val fiveK = givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
            val eightK = givenACourse("Cuckoo 8k", 8_000.0, family = "Cuckoo Trail")
            val theEightKsGround = shapeAt(52.5)

            val landing = viewModelSeeing(
                shapes = mapOf(fiveK to shapeAt(51.5), eightK to theEightKsGround),
                shaped = listOf(runOver(theEightKsGround, sessionId = 7, startTime = 9_000)),
            ).landingSibling(fiveK)

            assertEquals(eightK, landing)
        }

    // --- The landing pays this family's own shape debt first (#440) ---

    /** The very line [shapeAt] measures, so a course kept along it is shaped to that same answer. */
    private fun lineAt(at: Double): String = RoutePolyline.encode(
        listOf(
            RoutePoint(at, -0.1, elevationMeters = null),
            RoutePoint(at + 0.02, -0.1, elevationMeters = null),
        )
    )

    /** A course kept along real ground, so its shape can be measured from its own stored line. */
    private suspend fun givenACourseAlong(name: String, family: String, at: Double): Long =
        dao.insertRoute(
            Route(
                name = name,
                distanceMeters = 5_000.0,
                elevationGainMeters = null,
                polyline = lineAt(at),
                createdAtMillis = 1_700_000_000_000L,
                source = RouteSource.IMPORTED,
                family = family,
            )
        )

    /**
     * A view model whose course shapes come from the shapes table rather than from a map the test
     * holds — which is what lets this ask whether the landing measured anything itself.
     *
     * The flow is built when the landing asks for it, so what it carries is the table as it stands
     * at that moment: before the debt is paid, nothing.
     */
    private fun viewModelReadingTheShapesTable(shaped: List<ShapedRunRow>) = RoutesViewModel(
        dao,
        RouteImporter(mock(), dao, now = { 1_700_000_000_000L }),
        runsAlongRoute = { flowOf(emptyList()) },
        lastRunOnRoutes = { ids -> lastRuns.filter { it.routeId in ids } },
        courseShape = { routeId ->
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
        },
        shapedRuns = flowOf(shaped),
        io = dispatcher,
        courseDispatcher = dispatcher,
    )

    /**
     * The whole of #440: the family is opened on the launch that is still measuring the library, so
     * neither length has a shape yet and the 8k's only claim to having been run is a Run it would be
     * *recognised* on. Unpaid, both lengths recognise nothing and the family lands on the shortest —
     * the "nobody has run this" answer. The landing measures its own family first, so it does not.
     */
    @Test
    fun `a family opened while its own shapes are still owed lands on the length run last`() =
        runTest(dispatcher) {
            val fiveK = givenACourseAlong("Cuckoo 5k", family = "Cuckoo Trail", at = 51.5)
            val eightK = givenACourseAlong("Cuckoo 8k", family = "Cuckoo Trail", at = 52.5)
            // Nothing has measured either course: the launch pass has not reached them.
            assertEquals(listOf(fiveK, eightK), dao.coursesOwedShapes())
            val theEightKsRun = runOver(shapeAt(52.5), sessionId = 7, startTime = 9_000)

            val landing = viewModelReadingTheShapesTable(listOf(theEightKsRun)).landingSibling(fiveK)

            assertEquals(eightK, landing)
            // And the debt is paid rather than worked around: the shapes are on the table afterwards.
            assertEquals(emptyList<Long>(), dao.coursesOwedShapes())
        }

    /**
     * Only this family's debt, not the library's. A page opening must not measure every course the
     * runner keeps, which on the first launch after an upgrade is the whole library and is what the
     * launch pass is for.
     */
    @Test
    fun `the landing measures its own family and leaves the rest of the library owed`() =
        runTest(dispatcher) {
            val fiveK = givenACourseAlong("Cuckoo 5k", family = "Cuckoo Trail", at = 51.5)
            givenACourseAlong("Cuckoo 8k", family = "Cuckoo Trail", at = 52.5)
            val elsewhere = givenACourseAlong("Park loop", family = "Park", at = 53.5)

            viewModelReadingTheShapesTable(emptyList()).landingSibling(fiveK)

            assertEquals(listOf(elsewhere), dao.coursesOwedShapes())
        }

    /** A Run written down beats an older Run recognised, and the reverse: the later one wins. */
    @Test
    fun `a length remembered more recently than another was recognised still wins`() =
        runTest(dispatcher) {
            val fiveK = givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
            val eightK = givenACourse("Cuckoo 8k", 8_000.0, family = "Cuckoo Trail")
            val theEightKsGround = shapeAt(52.5)
            lastRuns = listOf(RouteLastRunRow(fiveK, 9_000))

            val landing = viewModelSeeing(
                shapes = mapOf(fiveK to shapeAt(51.5), eightK to theEightKsGround),
                shaped = listOf(runOver(theEightKsGround, sessionId = 7, startTime = 1_000)),
            ).landingSibling(eightK)

            assertEquals(fiveK, landing)
        }

    @Test
    fun `a course in no family opens on itself, whatever else has been run`() = runTest(dispatcher) {
        val lone = givenACourse("Park loop", 4_000.0)
        lastRuns = listOf(RouteLastRunRow(routeId = 99, 9_000))
        val viewModel = viewModel()

        assertEquals(lone, viewModel.landingSibling(lone))
    }

    @Test
    fun `a course deleted before its page opened lands nowhere`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertNull(viewModel.landingSibling(404L))
    }

    @Test
    fun `the box offers the family names the library already holds`() = runTest(dispatcher) {
        givenACourse("Cuckoo 5k", 5_000.0, family = "Cuckoo Trail")
        givenACourse("Cuckoo 8k", 8_000.0, family = "Cuckoo Trail")
        givenACourse("Downs 10k", 10_000.0, family = "Downs")
        givenACourse("Park loop", 4_000.0)
        val viewModel = viewModel()

        assertEquals(listOf("Cuckoo Trail", "Downs"), viewModel.familyNames.first())
    }
}
