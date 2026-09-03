package com.example.runningapp.data

import com.example.runningapp.SettingsRepository
import com.example.runningapp.UserSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever

/**
 * The read the pre-run route picker's suggestion is worked out from, taken after a Run ends (#422).
 *
 * [com.example.runningapp.ui.RouteSuggestionTest] covers what the recent Runs are turned *into*;
 * this covers the one thing the record screen cannot decide for itself — **when** those Runs may be
 * read. A Run stopped with the record screen still in front of the runner publishes STOPPED before
 * `finalizeRun` writes the row's totals, and the query the suggestion uses asks for a finished Run.
 * So the moment the session reads idle is precisely the moment the just-finished Run is still
 * invisible to it, and a read taken then would miss the Run it was re-taken for with nothing left to
 * fire again. The rule lives in the repository rather than in the composable so it can be pinned
 * here, without a phone.
 */
class SessionRepositoryRouteSuggestionReadTest {

    private val startedAt = 1_700_000_000_000L
    private val since = startedAt - 30L * 24 * 60 * 60 * 1000

    /** The just-finished Run as its row stands right now — the seam the wait watches. */
    private val row = MutableStateFlow<RunnerSession?>(liveRun())

    private val sessionDao: SessionDao = mock()
    private val settingsRepository: SettingsRepository = mock {
        on { userSettingsFlow }.thenReturn(flowOf(UserSettings()))
    }

    private val repository = SessionRepository(
        sessionDao = sessionDao,
        settingsRepository = settingsRepository,
    )

    private fun liveRun() = RunnerSession(id = 67L, startTime = startedAt, runMode = "outdoor")

    private fun finishedRun() = RunnerSession(
        id = 67L,
        startTime = startedAt,
        endTime = startedAt + 1_800_000L,
        durationSeconds = 1_800,
        distanceKm = 5.0,
        runMode = "outdoor",
    )

    private val pace = RunPaceRow(
        ranUnderStageId = null,
        ranUnderWorkoutId = null,
        durationSeconds = 1_800,
        distanceKm = 5.0,
    )

    /**
     * History as the query would really answer it: the Run counts only once its row is finished,
     * which is what makes the wait the difference between seeing it and not.
     */
    private fun historyFollowsTheRow() = sessionDao.stub {
        onBlocking { recentMeasuredRuns(any()) } doAnswer {
            if (row.value?.isFinished() == true) listOf(pace) else emptyList()
        }
    }

    @Test
    fun `the read waits for the just-finished run's row and then counts it`() = runTest {
        whenever(sessionDao.getSessionByIdFlow(67L)).thenReturn(row)
        historyFollowsTheRow()

        val read = async { repository.recentMeasuredRunsOnceSettled(67L, since) }
        runCurrent()

        assertFalse(
            "history was read while the Run's totals were still unwritten, so the Run it was " +
                "re-read for would have been missing from it",
            read.isCompleted
        )

        row.value = finishedRun()

        assertEquals(listOf(pace), read.await())
    }

    @Test
    fun `a run whose row has gone is not waited on`() = runTest {
        // Deleted from history while the screen sat open, or discarded for recording nothing — the
        // id names a row that is never coming. Nothing is left to wait for, and the suggestion must
        // be worked out now rather than at the end of the timeout.
        whenever(sessionDao.getSessionByIdFlow(67L)).thenReturn(MutableStateFlow(null))
        historyFollowsTheRow()

        val runs = repository.recentMeasuredRunsOnceSettled(67L, since)

        assertTrue(runs.isEmpty())
        assertEquals("an absent row was waited on", 0L, testScheduler.currentTime)
    }

    @Test
    fun `a run that never settles costs the runner the newest Run, not the suggestion`() = runTest {
        // A finalize that never lands — the process reclaimed mid-write, a write that threw. The
        // lesser loss is the suggestion the screen would have shown anyway, one Run short.
        whenever(sessionDao.getSessionByIdFlow(67L)).thenReturn(row)
        historyFollowsTheRow()

        val runs = repository.recentMeasuredRunsOnceSettled(67L, since)

        assertTrue(runs.isEmpty())
        assertTrue("the read gave up without waiting at all", testScheduler.currentTime > 0L)
    }

    @Test
    fun `with no Run behind it the read waits for nothing`() = runTest {
        // A fresh launch, or a screen that has not watched a Run end. There is no row to settle.
        historyFollowsTheRow()

        val runs = repository.recentMeasuredRunsOnceSettled(null, since)

        assertTrue(runs.isEmpty())
        assertEquals(0L, testScheduler.currentTime)
    }
}
