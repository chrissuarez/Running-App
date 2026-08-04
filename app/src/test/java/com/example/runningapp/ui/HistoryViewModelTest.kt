package com.example.runningapp.ui

import com.example.runningapp.data.AchievementDao
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionMedalCount
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.data.TrackPointDao
import com.example.runningapp.data.TrackPointSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * What the History list is handed for each run it shows (#51): the run itself, how many medals it
 * holds, and the shape of where it went.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val sessions = MutableStateFlow<List<RunnerSession>>(emptyList())
    private val medals = MutableStateFlow<List<SessionMedalCount>>(emptyList())

    private val sessionDao: SessionDao = mock()
    private val achievementDao: AchievementDao = mock()
    private val trackPointDao: TrackPointDao = mock()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(sessionDao.getLast20Sessions()).thenReturn(sessions)
        whenever(achievementDao.getMedalCountsFlow()).thenReturn(medals)
        // A run nothing else says anything about recorded no track, which is the ordinary case for
        // every test here that is asking about medals rather than about routes.
        trackPointDao.stub { onBlocking { getTrackPointsForSessionOnce(any()) } doReturn emptyList() }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a run that won medals carries the count`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7))
        medals.value = listOf(SessionMedalCount(sessionId = 7, medals = 2))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(2, viewModel.rows.value.single().medals)
    }

    @Test
    fun `a run that won nothing carries no medals`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(0, viewModel.rows.value.single().medals)
    }

    @Test
    fun `medals belong to the run that won them`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7), aRun(id = 8), aRun(id = 9))
        medals.value = listOf(SessionMedalCount(sessionId = 9, medals = 1), SessionMedalCount(sessionId = 7, medals = 3))

        val viewModel = viewModel()
        advanceUntilIdle()

        val rows = viewModel.rows.value
        assertEquals(listOf(3, 0, 1), rows.map { it.medals })
        assertEquals(listOf(7L, 8L, 9L), rows.map { it.session.id })
    }

    @Test
    fun `a medal won while the list is open arrives on it`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7))
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(0, viewModel.rows.value.single().medals)

        medals.value = listOf(SessionMedalCount(sessionId = 7, medals = 1))
        advanceUntilIdle()

        assertEquals(1, viewModel.rows.value.single().medals)
    }

    @Test
    fun `an outdoor run is drawn`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7))
        whenever(trackPointDao.getTrackPointsForSessionOnce(7)).thenReturn(aRoute(sessionId = 7))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNotNull(viewModel.rows.value.single().thumbnail)
    }

    @Test
    fun `a treadmill run is not drawn, and its track is never asked for`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7, runMode = "treadmill"))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.rows.value.single().thumbnail)
        verify(trackPointDao, never()).getTrackPointsForSessionOnce(7)
    }

    @Test
    fun `a run with no route recorded is not drawn`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7))
        whenever(trackPointDao.getTrackPointsForSessionOnce(7)).thenReturn(emptyList())
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.rows.value.single().thumbnail)
    }

    /** Having no route is an answer, and one worth remembering — see the test below it. */
    @Test
    fun `a run found to have no route is not read again either`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7))
        whenever(trackPointDao.getTrackPointsForSessionOnce(7)).thenReturn(emptyList())
        val viewModel = viewModel()
        advanceUntilIdle()

        medals.value = listOf(SessionMedalCount(sessionId = 7, medals = 1))
        advanceUntilIdle()

        assertEquals(1, viewModel.rows.value.single().medals)
        verify(trackPointDao).getTrackPointsForSessionOnce(7)
    }

    /**
     * A run appears in History the moment it starts, with a track of a few seconds. Drawing that
     * would bank the first minute as the shape of the whole run — and nothing here asks twice.
     */
    @Test
    fun `a run still being recorded is drawn once it finishes, not before`() = runTest(dispatcher) {
        val running = aRun(id = 7).copy(endTime = 0, durationSeconds = 0)
        sessions.value = listOf(running)
        whenever(trackPointDao.getTrackPointsForSessionOnce(7)).thenReturn(aRoute(sessionId = 7))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.rows.value.single().thumbnail)
        verify(trackPointDao, never()).getTrackPointsForSessionOnce(7)

        sessions.value = listOf(aRun(id = 7))
        advanceUntilIdle()

        assertNotNull(viewModel.rows.value.single().thumbnail)
    }

    /**
     * Scrolling a list must not re-read the same run's track over and over. The list re-emits
     * whenever anything about it changes — a medal scored, a run deleted — and each emission
     * re-reading every route would put thousands of database rows and an hour of GPS arithmetic
     * between the runner's finger and the screen.
     */
    @Test
    fun `a run already drawn is not read again`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7))
        whenever(trackPointDao.getTrackPointsForSessionOnce(7)).thenReturn(aRoute(sessionId = 7))
        val viewModel = viewModel()
        advanceUntilIdle()

        medals.value = listOf(SessionMedalCount(sessionId = 7, medals = 1))
        sessions.value = listOf(aRun(id = 7), aRun(id = 8, runMode = "treadmill"))
        advanceUntilIdle()

        verify(trackPointDao).getTrackPointsForSessionOnce(7)
    }

    /** The view model as History itself has it: made, and then opened. */
    private fun viewModel() = unopenedViewModel().also { it.drawRoutesWhileHistoryIsOpen() }

    /** The view model as the rest of the app has it — made at launch, with History never opened. */
    private fun unopenedViewModel() = HistoryViewModel(
        SessionRepository(
            sessionDao = sessionDao,
            trackPointDao = trackPointDao,
            achievementDao = achievementDao,
        ),
        routeDispatcher = dispatcher,
    )

    /**
     * This view model belongs to the activity, so it is made at launch whether or not the runner
     * goes anywhere near History. Reading and simplifying twenty tracks then is thousands of stored
     * fixes competing with what the app was actually opened to do — often to start a Run.
     */
    @Test
    fun `no route is read until History is opened`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7))
        whenever(trackPointDao.getTrackPointsForSessionOnce(7)).thenReturn(aRoute(sessionId = 7))

        val viewModel = unopenedViewModel()
        advanceUntilIdle()

        verify(trackPointDao, never()).getTrackPointsForSessionOnce(7)

        viewModel.drawRoutesWhileHistoryIsOpen()
        advanceUntilIdle()

        assertNotNull(viewModel.rows.value.single().thumbnail)
    }

    /** Leaving History and coming back must not set a second pass going over the same runs. */
    @Test
    fun `opening History again does not read the same route twice`() = runTest(dispatcher) {
        sessions.value = listOf(aRun(id = 7))
        whenever(trackPointDao.getTrackPointsForSessionOnce(7)).thenReturn(aRoute(sessionId = 7))

        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.drawRoutesWhileHistoryIsOpen()
        sessions.value = listOf(aRun(id = 7), aRun(id = 8, runMode = "treadmill"))
        advanceUntilIdle()

        verify(trackPointDao).getTrackPointsForSessionOnce(7)
    }

    private fun aRun(id: Long, runMode: String = "outdoor") = RunnerSession(
        id = id,
        startTime = 1_700_000_000_000L + id,
        endTime = 1_700_000_600_000L + id,
        durationSeconds = 600,
        runMode = runMode,
    )

    /** A hundred metres of running, which is a route to draw. */
    private fun aRoute(sessionId: Long) = (0..100).map { second ->
        TrackPoint(
            sessionId = sessionId,
            latitude = 50.79 + second * 0.00001,
            longitude = 0.22,
            horizontalAccuracyMeters = 5f,
            timestampMillis = 1_700_000_000_000L + second * 1_000L,
            source = TrackPointSource.GPS,
        )
    }
}
