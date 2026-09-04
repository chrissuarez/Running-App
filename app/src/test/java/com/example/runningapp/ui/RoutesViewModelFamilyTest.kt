package com.example.runningapp.ui

import com.example.runningapp.data.Route
import com.example.runningapp.data.RouteLastRunRow
import com.example.runningapp.data.RouteSource
import com.example.runningapp.routes.FakeRouteDao
import com.example.runningapp.routes.RouteImporter
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
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
