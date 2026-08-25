package com.example.runningapp.ui

import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.SampleDao
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointDao
import com.example.runningapp.data.TrackPointSource
import com.example.runningapp.routes.FakeRouteDao
import com.example.runningapp.routes.RunRouteSaver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import kotlin.coroutines.CoroutineContext

/** What a Run's page tells the runner when they keep its ground as a course (#55). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelSaveAsRouteTest {

    private val startTime = 1_753_500_000_000L // 2025-07-26T03:20:00Z
    private val dispatcher = StandardTestDispatcher()
    private val routeDao = FakeRouteDao()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun session() = RunnerSession(
        id = 7L,
        startTime = startTime,
        endTime = startTime + 600_000,
        durationSeconds = 600,
    )

    /** A lap of ground, well beyond anything the accuracy of a fix could account for. */
    private fun aLap(): List<TrackPoint> = listOf(
        0.0 to 0.0,
        400.0 to 0.0,
        400.0 to 400.0,
        0.0 to 400.0,
    ).mapIndexed { i, (north, east) ->
        TrackPoint(
            sessionId = 7L,
            latitude = 51.5 + north / 111_320.0,
            longitude = -0.1278 + east / (111_320.0 * 0.6225),
            horizontalAccuracyMeters = 5f,
            timestampMillis = startTime + i * 60_000L,
            source = TrackPointSource.GPS,
        )
    }

    /**
     * Stands in for the dispatcher the course is worked out on, and counts the times it is handed
     * work. It passes everything straight to the test's own clock, so the saving still happens on
     * this test's terms — all it adds is a record of having been asked.
     */
    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        var dispatches = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    private fun viewModel(
        session: RunnerSession? = session(),
        trackPoints: List<TrackPoint> = aLap(),
        assembly: CoroutineDispatcher = dispatcher,
    ) = SessionDetailViewModel(
        SessionRepository(
            sessionDao = mock<SessionDao> { onBlocking { getSessionById(7L) } doReturn session },
            sampleDao = mock<SampleDao>(),
            trackPointDao = mock<TrackPointDao> {
                onBlocking { getTrackPointsForSessionOnce(7L) } doReturn trackPoints
            },
        ),
        assemblyDispatcher = assembly,
        runRouteSaver = RunRouteSaver(routeDao),
    )

    @Test
    fun `keeping a run's ground puts a course in the library and says so`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.saveAsRoute(7L)
        advanceUntilIdle()

        val route = routeDao.stored.single()
        assertEquals(
            SaveAsRouteMessage(7L, runSavedAsRouteMessage(route.name)),
            viewModel.saveAsRouteMessage.value,
        )
    }

    @Test
    fun `asking twice adds nothing and names the course already kept`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.saveAsRoute(7L)
        advanceUntilIdle()
        viewModel.saveAsRouteMessageShown()
        viewModel.saveAsRoute(7L)
        advanceUntilIdle()

        assertEquals(1, routeDao.stored.size)
        assertEquals(
            SaveAsRouteMessage(7L, routeAlreadySavedMessage(routeDao.stored.single().name)),
            viewModel.saveAsRouteMessage.value,
        )
    }

    /**
     * The fixes the page draws its map from are a watched read that begins empty, so the track is
     * read here rather than handed in — and a Run whose recording holds no ground is refused in
     * words rather than silently keeping nothing.
     */
    @Test
    fun `a run with no recorded ground is refused in words`() = runTest(dispatcher) {
        val viewModel = viewModel(trackPoints = emptyList())

        viewModel.saveAsRoute(7L)
        advanceUntilIdle()

        assertTrue(routeDao.stored.isEmpty())
        assertEquals(
            SaveAsRouteMessage(7L, runHasNoRouteToSaveMessage()),
            viewModel.saveAsRouteMessage.value,
        )
    }

    @Test
    fun `a run still being recorded is told to come back`() = runTest(dispatcher) {
        val viewModel = viewModel(session = session().copy(endTime = 0L))

        viewModel.saveAsRoute(7L)
        advanceUntilIdle()

        assertTrue(routeDao.stored.isEmpty())
        assertEquals(
            SaveAsRouteMessage(7L, runStillRunningMessage()),
            viewModel.saveAsRouteMessage.value,
        )
    }

    @Test
    fun `a run that is no longer there says so`() = runTest(dispatcher) {
        val viewModel = viewModel(session = null)

        viewModel.saveAsRoute(7L)
        advanceUntilIdle()

        assertTrue(routeDao.stored.isEmpty())
        assertEquals(
            SaveAsRouteMessage(7L, runCouldNotBeReadMessage()),
            viewModel.saveAsRouteMessage.value,
        )
    }

    @Test
    fun `the words go away once the runner has been shown them`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.saveAsRoute(7L)
        advanceUntilIdle()
        viewModel.saveAsRouteMessageShown()

        assertNull(viewModel.saveAsRouteMessage.value)
    }

    /**
     * The runner is on the main thread when they tap the button, and turning a Run into a course is
     * arithmetic over every fix it recorded — sorting them, thinning them to their shape, and
     * walking them again for distance and hills. An hour's run is thousands of them, so none of that
     * may be done where the screen is drawn.
     */
    @Test
    fun `turning a run into a course is worked out off the main thread`() = runTest(dispatcher) {
        val assembly = RecordingDispatcher(dispatcher)
        val viewModel = viewModel(assembly = assembly)

        viewModel.saveAsRoute(7L)
        advanceUntilIdle()

        // The course was kept, and the work of making it went to the dispatcher meant for it.
        assertEquals(1, routeDao.stored.size)
        assertTrue(
            "The course was assembled on the main dispatcher",
            assembly.dispatches > 0,
        )
    }

    /** Nothing is wired to keep a course with, so nothing is kept and nothing is said. */
    @Test
    fun `a build with no library keeps nothing`() = runTest(dispatcher) {
        val viewModel = SessionDetailViewModel(
            SessionRepository(
                sessionDao = mock<SessionDao> { onBlocking { getSessionById(7L) } doReturn session() },
                sampleDao = mock<SampleDao>(),
                trackPointDao = mock<TrackPointDao> {
                    onBlocking { getTrackPointsForSessionOnce(7L) } doReturn aLap()
                },
            )
        )

        viewModel.saveAsRoute(7L)
        advanceUntilIdle()

        assertTrue(routeDao.stored.isEmpty())
        assertNull(viewModel.saveAsRouteMessage.value)
    }
}
