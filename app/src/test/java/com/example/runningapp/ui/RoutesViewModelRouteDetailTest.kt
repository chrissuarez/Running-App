package com.example.runningapp.ui

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteRunRow
import com.example.runningapp.data.RouteSource
import com.example.runningapp.routes.FakeRouteDao
import com.example.runningapp.routes.RouteImporter
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * What one course's own page is handed (#420): the row, the line, and the Runs remembered on it.
 *
 * The band the best time is drawn inside is measured against *this course's* distance, so the row
 * and the Runs have to arrive from one read. That is the thing worth a test here — the arithmetic
 * itself is pinned by `RouteRunModelsTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutesViewModelRouteDetailTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val dao = FakeRouteDao()
    private val runs = MutableStateFlow<List<RouteRunRow>>(emptyList())

    private fun viewModel() = RoutesViewModel(
        dao,
        RouteImporter(mock(), dao, now = { 1_700_000_000_000L }),
        runsAlongRoute = { runs },
        io = dispatcher,
        courseDispatcher = dispatcher,
    )

    private val aLine = RoutePolyline.encode(
        listOf(
            RoutePoint(51.5, -0.1, elevationMeters = null),
            RoutePoint(51.51, -0.1, elevationMeters = null),
        )
    )

    private suspend fun givenACourse(distanceMeters: Double): Long = dao.insertRoute(
        Route(
            name = "Park loop",
            distanceMeters = distanceMeters,
            elevationGainMeters = 12.0,
            polyline = aLine,
            createdAtMillis = 1_700_000_000_000L,
            source = RouteSource.IMPORTED,
        )
    )

    private fun aRun(sessionId: Long, distanceKm: Double, durationSeconds: Long) = RouteRunRow(
        sessionId = sessionId,
        startTime = 1_717_232_400_000L + sessionId * 86_400_000L,
        ranAtUtcOffsetSeconds = 0,
        durationSeconds = durationSeconds,
        movingTimeSeconds = null,
        distanceKm = distanceKm,
    )

    @Test
    fun `the course's own row comes back without its line, and the line on its own`() = runTest(dispatcher) {
        val routeId = givenACourse(5_000.0)
        val viewModel = viewModel()

        assertEquals("Park loop", viewModel.route(routeId).first()?.name)
        assertEquals(2, viewModel.line(routeId).size)
    }

    /** A row deleted from the library while its page is open has no line and no course to draw. */
    @Test
    fun `a course that is gone has no row and no line`() = runTest(dispatcher) {
        val viewModel = viewModel()

        assertEquals(null, viewModel.route(404L).first())
        assertTrue(viewModel.line(404L).isEmpty())
    }

    /**
     * The band is this course's, not any other's: the very same Runs are counted against a 5 km
     * course and left out of a 12 km one.
     */
    @Test
    fun `the runs are measured against this course's own distance`() = runTest(dispatcher) {
        val fiveK = givenACourse(5_000.0)
        val twelveK = givenACourse(12_000.0)
        runs.value = listOf(aRun(1, distanceKm = 5.05, durationSeconds = 1_500))
        val viewModel = viewModel()

        assertTrue(viewModel.runsOnRoute(fiveK).first().single().countsForBest)
        assertFalse(viewModel.runsOnRoute(twelveK).first().single().countsForBest)
    }

    /** A Run on ground the library no longer keeps is nothing this page can rank. */
    @Test
    fun `no runs come back for a course that is gone`() = runTest(dispatcher) {
        runs.value = listOf(aRun(1, distanceKm = 5.0, durationSeconds = 1_500))
        val viewModel = viewModel()

        assertTrue(viewModel.runsOnRoute(404L).first().isEmpty())
    }
}
