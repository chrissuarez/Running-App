package com.example.runningapp.data

import com.example.runningapp.routes.FakeRouteDao
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The course a live Run's map draws beside its trail (#56), in the order the Run is running it (#57).
 *
 * Asked of the repository rather than of the map, because the map is Mapbox and the decision here is
 * not: which row to read, when to read it, and what to hand back when the course has gone.
 */
class RunRouteLineTest {

    private val line = listOf(
        RoutePoint(51.5000000, -0.1000000, elevationMeters = null),
        RoutePoint(51.5010000, -0.1010000, elevationMeters = null),
    )

    private val routeDao = FakeRouteDao()
    private val sessionDao: SessionDao = mock()

    private suspend fun keepRoute(): Long = routeDao.insertRoute(
        Route(
            name = "Regent's Park loop",
            distanceMeters = 5200.0,
            polyline = RoutePolyline.encode(line),
            createdAtMillis = 1_700_000_000_000L,
            source = RouteSource.IMPORTED,
        )
    )

    private fun repositoryWatching(sessions: MutableStateFlow<RunnerSession?>): SessionRepository {
        whenever(sessionDao.getSessionByIdFlow(1L)).thenReturn(sessions)
        return SessionRepository(sessionDao = sessionDao, routeDao = routeDao)
    }

    private fun run(routeId: Long?, reversed: Boolean = false) = RunnerSession(
        id = 1L,
        startTime = 1_700_000_000_000L,
        runMode = "outdoor",
        ranAlongRouteId = routeId,
        ranAlongRouteReversed = reversed,
    )

    @Test
    fun `a run started on a course hands back that course's line`() = runTest {
        val routeId = keepRoute()
        val repository = repositoryWatching(MutableStateFlow(run(routeId)))

        assertEquals(line, repository.routeLineForRunFlow(1L).first())
    }

    /**
     * The one place the runner's word about direction is read.
     *
     * The same ground in the same places, so the drawn line is unchanged — a line's points may be
     * given in either order and it draws the same. What turning it round is *for* is everything that
     * counts from the start: how far is left is measured from the end the runner set off at.
     */
    @Test
    fun `a course run backwards hands back its line the other way round`() = runTest {
        val routeId = keepRoute()
        val repository = repositoryWatching(MutableStateFlow(run(routeId, reversed = true)))

        assertEquals(line.reversed(), repository.routeLineForRunFlow(1L).first())
    }

    @Test
    fun `a run following no course has no line to draw`() = runTest {
        keepRoute()
        val repository = repositoryWatching(MutableStateFlow(run(routeId = null)))

        assertTrue(repository.routeLineForRunFlow(1L).first().isEmpty())
    }

    /**
     * The row is inserted on another thread while the map is already up, so a reader that asked once
     * would draw no course for the whole of a routed Run.
     */
    @Test
    fun `the course arrives when the run's row does`() = runTest {
        val routeId = keepRoute()
        val sessions = MutableStateFlow<RunnerSession?>(null)
        val repository = repositoryWatching(sessions)

        assertTrue(repository.routeLineForRunFlow(1L).first().isEmpty())
        sessions.value = run(routeId)

        assertEquals(line, repository.routeLineForRunFlow(1L).first())
    }

    /** Throwing a Route away costs a Run nothing (ADR 0014) — including this Run, mid-Run. */
    @Test
    fun `a course deleted before the map looks is not drawn`() = runTest {
        val routeId = keepRoute()
        val repository = repositoryWatching(MutableStateFlow(run(routeId)))
        routeDao.deleteRoute(routeId)

        assertTrue(repository.routeLineForRunFlow(1L).first().isEmpty())
    }

    /**
     * The half that matters, and the half a one-shot read cannot do.
     *
     * The library stays the runner's to edit while they are out on the course, so the delete lands
     * with the map already drawing. Nothing on the Run's own row moves when it does — the Run still
     * names the course it set out on, as it always will — so unless the Route itself is being
     * watched, nothing asks again and the map goes on drawing a course the library no longer holds.
     */
    @Test
    fun `a course deleted while the map is drawing stops being drawn`() = runTest {
        val routeId = keepRoute()
        val repository = repositoryWatching(MutableStateFlow(run(routeId)))

        val drawn = mutableListOf<List<RoutePoint>>()
        val watching = launch { repository.routeLineForRunFlow(1L).collect { drawn += it } }
        runCurrent()
        assertEquals(line, drawn.last())

        routeDao.deleteRoute(routeId)
        runCurrent()

        assertTrue("the blue line should have gone", drawn.last().isEmpty())
        watching.cancel()
    }
}
