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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The read the pre-run route picker's suggestion is worked out from, taken after a Run ends (#422).
 *
 * [com.example.runningapp.ui.RouteSuggestionTest] covers what the recent Runs are turned *into*;
 * this covers the one thing the record screen cannot decide for itself — **when** those Runs may be
 * read: only once that Run's **record is complete** — its row finalized, and the runner's word about
 * it in.
 *
 * Both halves are the same rule and each has its own way of being missed. A Run stopped with the
 * record screen still in front of the runner publishes STOPPED before `finalizeRun` writes the row's
 * totals, and the query the suggestion uses asks for a finished Run — so the moment the session
 * reads idle is precisely the moment the just-finished Run is still invisible to it. And the finish
 * sheet's Walk mark is written *after* that finalize, so a read that ended at the row would count a
 * Walk as a Run, with nothing left to fire again either way. The rule lives in the repository rather
 * than in the composable so it can be pinned here, without a phone — and because the finish sheet
 * is above that composable and cannot be seen from inside it.
 */
class SessionRepositoryRouteSuggestionReadTest {

    private val startedAt = 1_700_000_000_000L
    private val since = startedAt - 30L * 24 * 60 * 60 * 1000

    /** The moment the read is taken — half an hour after the Run started, which is when it ended. */
    private val until = startedAt + 1_800_000L

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
        sessionId = 67L,
        ranUnderStageId = null,
        ranUnderWorkoutId = null,
        durationSeconds = 1_800,
        distanceKm = 5.0,
    )

    private fun walkedRun() = finishedRun().copy(isWalk = true)

    /**
     * History as the query would really answer it: the Run counts only once its row is finished and
     * only while that row does not say Walk — the two columns
     * [SessionDao.recentMeasuredRuns] asks about, which are the two halves of the record. That is
     * what makes the wait the difference between seeing this Run, seeing it wrongly, and not seeing
     * it at all.
     */
    private fun historyFollowsTheRow() = sessionDao.stub {
        onBlocking { recentMeasuredRuns(any(), any()) } doAnswer { invocation ->
            val windowStart = invocation.arguments[0] as Long
            val windowEnd = invocation.arguments[1] as Long
            val session = row.value
            val counts = session?.isFinished() == true &&
                !session.isWalk &&
                session.startTime >= windowStart &&
                session.startTime <= windowEnd
            if (counts) listOf(pace) else emptyList()
        }
        // What the finish sheet's own doors read while they settle the Run: the row as it stands.
        onBlocking { getSessionById(67L) } doAnswer { row.value }
    }

    @Test
    fun `the read waits for the just-finished run's row and then counts it`() = runTest {
        whenever(sessionDao.getSessionByIdFlow(67L)).thenReturn(row)
        historyFollowsTheRow()

        val read = async { repository.recentMeasuredRunsOnceTheRecordIsComplete(67L, since, until) }
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

        val runs = repository.recentMeasuredRunsOnceTheRecordIsComplete(67L, since, until)

        assertTrue(runs.isEmpty())
        assertEquals("an absent row was waited on", 0L, testScheduler.currentTime)
    }

    @Test
    fun `a run that never settles costs the runner the newest Run, not the suggestion`() = runTest {
        // A finalize that never lands — the process reclaimed mid-write, a write that threw. The
        // lesser loss is the suggestion the screen would have shown anyway, one Run short.
        whenever(sessionDao.getSessionByIdFlow(67L)).thenReturn(row)
        historyFollowsTheRow()

        val runs = repository.recentMeasuredRunsOnceTheRecordIsComplete(67L, since, until)

        assertTrue(runs.isEmpty())
        assertTrue("the read gave up without waiting at all", testScheduler.currentTime > 0L)
    }

    @Test
    fun `with no Run behind it the read waits for nothing`() = runTest {
        // A fresh launch, or a screen that has not watched a Run end. There is no row to settle.
        historyFollowsTheRow()

        val runs = repository.recentMeasuredRunsOnceTheRecordIsComplete(null, since, until)

        assertTrue(runs.isEmpty())
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `the read waits for the runner's word about the finished Run, and a Walk stays out of it`() = runTest {
        // The row is finalized the instant the session goes idle, but the finish sheet is still on
        // screen: the Walk mark is written when it is answered, which is after the finalize. A read
        // taken in between would count this Walk as a Run — enough on its own to carry the history
        // over the three-Run threshold the suggestion needs, or to drag its median pace.
        whenever(sessionDao.getSessionByIdFlow(67L)).thenReturn(row)
        historyFollowsTheRow()
        repository.finishSheetOpened(67L)
        row.value = finishedRun()

        val read = async { repository.recentMeasuredRunsOnceTheRecordIsComplete(67L, since, until) }
        runCurrent()

        assertFalse(
            "history was read while the runner was still answering the finish sheet, so a Walk " +
                "would have been counted as a Run",
            read.isCompleted
        )

        // Save: the mark is written and then the sheet closes, which is the order the answer keeps.
        row.value = walkedRun()
        repository.finishSheetClosed(67L, markedAsWalk = true, finalizeWaitStepMillis = 1L)

        assertEquals(emptyList<RunPaceRow>(), read.await())
    }

    @Test
    fun `a dismissed sheet is an answer and releases the read`() = runTest {
        // Swiping the sheet away is the runner saying the Run was what it looks like. The word is in
        // either way, so the wait ends on the close and not on a Save.
        whenever(sessionDao.getSessionByIdFlow(67L)).thenReturn(row)
        historyFollowsTheRow()
        repository.finishSheetOpened(67L)
        row.value = finishedRun()

        val read = async { repository.recentMeasuredRunsOnceTheRecordIsComplete(67L, since, until) }
        runCurrent()
        assertFalse(read.isCompleted)

        repository.finishSheetClosed(67L, finalizeWaitStepMillis = 1L)

        assertEquals(listOf(pace), read.await())
    }

    @Test
    fun `a Run nobody is waiting on is read at once`() = runTest {
        // A record that is already complete: the row is finalized and no sheet is open about it — a
        // Run answered a while ago, or one the screen watched end after its sheet had been and gone.
        // There is nothing coming, so nothing to wait for.
        whenever(sessionDao.getSessionByIdFlow(67L)).thenReturn(row)
        historyFollowsTheRow()
        row.value = finishedRun()

        val runs = repository.recentMeasuredRunsOnceTheRecordIsComplete(67L, since, until)

        assertEquals(listOf(pace), runs)
        assertEquals("a complete record was waited on", 0L, testScheduler.currentTime)
    }

    @Test
    fun `a sheet the runner never answers costs the newest Run, not the suggestion`() = runTest {
        // The other half of the same bound: a runner who walks away from the finish sheet must not
        // hang the picker for ever. The read goes ahead on expiry — and *without* the Run it gave up
        // on, because that Run's record is exactly the one the rule says must not be counted. Its
        // row is finalized and passes the query, but its `isWalk` is still the default no-one has
        // answered yet, so counting it here would be the wait's own expiry delivering the miscount
        // the wait exists to prevent.
        whenever(sessionDao.getSessionByIdFlow(67L)).thenReturn(row)
        historyFollowsTheRow()
        repository.finishSheetOpened(67L)
        row.value = finishedRun()

        val runs = repository.recentMeasuredRunsOnceTheRecordIsComplete(67L, since, until)

        assertEquals(
            "the unanswered Run was counted, so a Walk the runner had not marked yet would have " +
                "gone into the history as a Run",
            emptyList<RunPaceRow>(),
            runs
        )
        assertTrue("the read gave up without waiting at all", testScheduler.currentTime > 0L)
    }

    @Test
    fun `giving up on one Run leaves the rest of the history alone`() = runTest {
        // The expiry drops the Run whose record is incomplete and nothing else: every other Run in
        // the window was answered long ago, and losing them would cost the suggestion the history it
        // is drawn from rather than one row of it.
        val answeredLastWeek = pace.copy(sessionId = 12L, durationSeconds = 2_400, distanceKm = 6.0)
        whenever(sessionDao.getSessionByIdFlow(67L)).thenReturn(row)
        sessionDao.stub {
            onBlocking { recentMeasuredRuns(any(), any()) } doReturn listOf(pace, answeredLastWeek)
        }
        repository.finishSheetOpened(67L)
        row.value = finishedRun()

        val runs = repository.recentMeasuredRunsOnceTheRecordIsComplete(67L, since, until)

        assertEquals(listOf(answeredLastWeek), runs)
    }

    /**
     * The window's far end, which is the moment of the read (#422). A clock corrected backwards
     * after a Run leaves that Run stamped later than now — the same fact the plan's evaluation and
     * the Progress curves already work around — and with only a lower bound the row would sort as
     * the newest Run there is and hold one of the counted places until wall time caught up with it.
     */
    @Test
    fun `a Run stamped after the moment of the read is outside the window`() = runTest {
        historyFollowsTheRow()
        // Finished, outdoor, half an hour long, not a Walk: it fails nothing except having happened
        // after the clock the read was taken by.
        row.value = finishedRun().copy(startTime = until + 1)

        val runs = repository.recentMeasuredRunsOnceTheRecordIsComplete(null, since, until)

        assertEquals(
            "a Run stamped in the future was counted, so its pace would bend today's suggestion " +
                "until wall time caught up with it",
            emptyList<RunPaceRow>(),
            runs
        )
    }

    /** And a Run stamped in the read's own millisecond is not in the future: the end is inclusive. */
    @Test
    fun `a Run stamped in the very millisecond of the read still counts`() = runTest {
        historyFollowsTheRow()
        row.value = finishedRun().copy(startTime = until)

        assertEquals(
            listOf(pace),
            repository.recentMeasuredRunsOnceTheRecordIsComplete(null, since, until)
        )
    }

    /**
     * Both ends are the caller's and reach the query unaltered — the repository is a passthrough,
     * and a window it re-cut from a clock of its own would be a window the caller never sized.
     */
    @Test
    fun `both ends of the window are handed to the query as given`() = runTest {
        historyFollowsTheRow()
        row.value = finishedRun()

        repository.recentMeasuredRunsOnceTheRecordIsComplete(null, since, until)

        verify(sessionDao).recentMeasuredRuns(since, until)
    }
}
