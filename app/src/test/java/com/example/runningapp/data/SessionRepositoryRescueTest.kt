package com.example.runningapp.data

import com.example.runningapp.HrProfile
import com.example.runningapp.SettingsRepository
import com.example.runningapp.UserSettings
import com.example.runningapp.tallyZoneSeconds
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The launch pass that finishes a Run a previous process left interrupted (#192).
 *
 * [InterruptedRunTest] covers what the totals come out as; this covers which Runs the pass touches
 * and what it does when one of them cannot be rebuilt.
 */
class SessionRepositoryRescueTest {

    private val processStartedAt = 1_700_000_100_000L
    private val startedAt = 1_700_000_000_000L

    private val sessionDao: SessionDao = mock()
    private val sampleDao: SampleDao = mock()
    private val trackPointDao: TrackPointDao = mock()
    private val intervalStatDao: RunWalkIntervalStatDao = mock()
    private val runPauseDao: RunPauseDao = mock()
    // Wired, because scoring is a no-op without a book to write to — and this pass has to be shown
    // both marking a Run it scored and leaving one it could not (#210).
    private val achievementDao: AchievementDao = mock()
    private val settingsRepository: SettingsRepository = mock {
        on { userSettingsFlow }.thenReturn(flowOf(UserSettings(maxHr = 185)))
    }

    private var backupsRefreshed = 0

    // What the production wiring hands to WorkManager. Recorded rather than counted, because the
    // point of the durable handoff is that it names the Run that has just been stamped.
    private val runsBooked = mutableListOf<Long>()

    private val repository = SessionRepository(
        sessionDao = sessionDao,
        sampleDao = sampleDao,
        trackPointDao = trackPointDao,
        intervalStatDao = intervalStatDao,
        runPauseDao = runPauseDao,
        achievementDao = achievementDao,
        settingsRepository = settingsRepository,
        refreshHistoryBackup = { backupsRefreshed++ },
        bookAfterRunWork = { runsBooked += it },
    )

    private fun interruptedRun(id: Long) = RunnerSession(id = id, startTime = startedAt, runMode = "outdoor")

    private fun samples(sessionId: Long, count: Int) = (1..count).map { second ->
        HrSample(
            sessionId = sessionId,
            elapsedSeconds = second.toLong(),
            rawBpm = 130,
            smoothedBpm = 130,
            connectionState = "Connected",
            timestampMillis = startedAt + second * 1_000L,
        )
    }

    @Test
    fun `an interrupted run is finished from the seconds it wrote`() = runTest {
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(listOf(67L))
        whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 292))
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        val saved = argumentCaptor<RunnerSession>()
        verify(sessionDao).updateSession(saved.capture())
        assertEquals(292, saved.firstValue.durationSeconds)
        assertEquals(startedAt + 292_000, saved.firstValue.endTime)
    }

    @Test
    fun `a run that recorded nothing is left interrupted rather than put into history`() = runTest {
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(listOf(67L))
        whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(emptyList())
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        verify(sessionDao, never()).updateSession(any())
        assertEquals(0, backupsRefreshed)
    }

    @Test
    fun `a run that cannot be rebuilt costs the others nothing`() = runTest {
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(listOf(66L, 67L))
        whenever(sessionDao.getSessionById(66L)).thenThrow(IllegalStateException("corrupt page"))
        whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 60))
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        val saved = argumentCaptor<RunnerSession>()
        verify(sessionDao).updateSession(saved.capture())
        assertEquals(67L, saved.firstValue.id)
    }

    @Test
    fun `the history snapshot is refreshed once, and only when something was rescued`() = runTest {
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(listOf(66L, 67L))
        listOf(66L, 67L).forEach { id ->
            whenever(sessionDao.getSessionById(id)).thenReturn(interruptedRun(id))
            whenever(sampleDao.getSamplesForSessionOnce(id)).thenReturn(samples(id, 60))
            whenever(trackPointDao.getTrackPointsForSessionOnce(id)).thenReturn(emptyList())
        }

        repository.rescueInterruptedRuns(processStartedAt)

        verify(sessionDao, times(2)).updateSession(any())
        assertEquals(1, backupsRefreshed)
        // And no durable booking per Run. That handoff belongs to the teardown, whose process is
        // dying; this pass runs at launch on a process that is not, and a booking each would mean a
        // copy of the whole database for every Run in the list.
        assertTrue(runsBooked.isEmpty())
    }

    @Test
    fun `a run whose moving time cannot be measured is still rescued`() = runTest {
        // The row is finished by the time moving time is worked out, so this pass will never see
        // the Run again. Failing here must not cost it its place in history or the snapshot: the
        // one number it is missing is left null, which is what the next launch's backfill looks for.
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(listOf(67L))
        whenever(sessionDao.getSessionById(67L))
            .thenReturn(interruptedRun(67L))
            .thenThrow(IllegalStateException("corrupt page"))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 60))
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        verify(sessionDao).updateSession(any())
        assertEquals(1, backupsRefreshed)
    }

    @Test
    fun `a rescued run is marked as measured against the record book`() = runTest {
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(listOf(67L))
        whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 60))
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        verify(sessionDao).setRecordsScored(67L)
    }

    @Test
    fun `a rescued run whose scoring fails is left owing one for the launch pass`() = runTest {
        // The same corrupt page that costs the Run its moving time above costs it its scoring, and
        // the row is finished by then — so nothing would ever offer it to the book again if the
        // rescue marked it anyway (#210).
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(listOf(67L))
        whenever(sessionDao.getSessionById(67L))
            .thenReturn(interruptedRun(67L))
            .thenThrow(IllegalStateException("corrupt page"))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 60))
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        verify(sessionDao).updateSession(any())
        verify(sessionDao, never()).setRecordsScored(any())
    }

    @Test
    fun `a rescued run is banded on the maximum the rest of history is banded on`() = runTest {
        // A runner who set 181 and later corrected to 195 has history on 181 — the correction is
        // future-only (#112). A rescue banding this Run on 195 would land the one Run in history
        // that nobody else's zones agree with.
        whenever(settingsRepository.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 195, historyMaxHr = 181)))
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(listOf(67L))
        whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 60))
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        val saved = argumentCaptor<RunnerSession>()
        verify(sessionDao).updateSession(saved.capture())
        val onHistoryProfile = tallyZoneSeconds(List(60) { 130 }, HrProfile(maxHr = 181))
        assertEquals(onHistoryProfile.zone2, saved.firstValue.zone2Seconds)
        assertEquals(onHistoryProfile.zone3, saved.firstValue.zone3Seconds)
    }

    @Test
    fun `a rescued run that wrote its own heart rates is banded on those`() = runTest {
        // The Run was recorded under 195 after the correction, and history is still on 181. Neither
        // global is the answer for it (#228): its own pair is, and it wrote one down at START.
        //
        // 130 is the beat that tells them apart. Against 195 it is Zone 2 (that zone runs 117-136);
        // against 181 it is Zone 3, which starts at 127.
        whenever(settingsRepository.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 195, historyMaxHr = 181)))
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(listOf(67L))
        whenever(sessionDao.getSessionById(67L))
            .thenReturn(interruptedRun(67L).copy(bandedOnMaxHr = 195, bandedOnRestingHr = 0))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 60))
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        val saved = argumentCaptor<RunnerSession>()
        verify(sessionDao).updateSession(saved.capture())
        val onItsOwnProfile = tallyZoneSeconds(List(60) { 130 }, HrProfile(maxHr = 195))
        assertEquals(onItsOwnProfile.zone2, saved.firstValue.zone2Seconds)
        assertEquals(onItsOwnProfile.zone3, saved.firstValue.zone3Seconds)
        // And the run keeps saying what it was banded on, rather than being rewritten to the global.
        assertEquals(195, saved.firstValue.bandedOnMaxHr)
    }

    @Test
    fun `a rescued run that banked an interval comes back as a run walk run`() = runTest {
        val intervalStatDao: RunWalkIntervalStatDao = mock {
            onBlocking { getIntervalStatsForSession(67L) }.thenReturn(
                listOf(
                    RunWalkIntervalStat(
                        sessionId = 67L,
                        intervalIndex = 0,
                        plannedDurationSeconds = 120,
                        actualRunningDurationBeforeHrTriggerSeconds = 120,
                        hrTriggerEvents = 0,
                        totalTimeSpentWalkingDuringRunIntervalSeconds = 0,
                    )
                )
            )
        }
        val repository = SessionRepository(
            sessionDao = sessionDao,
            sampleDao = sampleDao,
            trackPointDao = trackPointDao,
            intervalStatDao = intervalStatDao,
            settingsRepository = settingsRepository,
        )
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(listOf(67L))
        whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 60))
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        val saved = argumentCaptor<RunnerSession>()
        verify(sessionDao).updateSession(saved.capture())
        assertTrue(saved.firstValue.isRunWalkMode)
    }

    @Test
    fun `a launch with nothing interrupted asks the database for nothing else`() = runTest {
        whenever(sessionDao.getInterruptedSessionIds(processStartedAt)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        verify(sessionDao, never()).getSessionById(any())
        verify(sessionDao, never()).updateSession(any())
        assertEquals(0, backupsRefreshed)
    }

    // ------------------------------------------------------------------------------------------
    // The Run a service teardown left recording (#309). The launch pass above cannot have it: it
    // began after this process did, so it is outside the pass's query for as long as the process
    // lives — which in #309 was hours.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `the Run a teardown was holding is finished without waiting for a launch`() = runTest {
        whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 22))
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        assertTrue(repository.rescueRunLostToTeardown(67L))

        val saved = argumentCaptor<RunnerSession>()
        verify(sessionDao).updateSession(saved.capture())
        assertEquals(22, saved.firstValue.durationSeconds)
        // Named rather than searched for: no list is asked for, so nothing else in the database can
        // be caught up in a teardown's rescue.
        verify(sessionDao, never()).getInterruptedSessionIds(any())
    }

    @Test
    fun `the snapshot for a teardown's Run is booked to outlive the process, not taken on it`() =
        runTest {
            // Whatever took the service down can take the process next, and it can do it after the
            // row is stamped. A copy taken here might never finish or never start, and the Run is
            // out of the launch pass's reach the moment it is stamped — so the Downloads snapshot
            // would sit one Run behind until something else happened to refresh it.
            whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
            whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 22))
            whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

            assertTrue(repository.rescueRunLostToTeardown(67L))

            assertEquals(listOf(67L), runsBooked)
            assertEquals(0, backupsRefreshed)
        }

    @Test
    fun `the booking is made the instant the row is stamped, before anything hangs off it`() =
        runTest {
            // Everything after the stamp — measuring the moving time, scoring the record book — is
            // real work on a real database, and all of it is window in which the process can go.
            // Booked first, that window costs the Run nothing.
            whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
            whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 22))
            whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())
            val order = mutableListOf<String>()
            val repository = SessionRepository(
                sessionDao = sessionDao,
                sampleDao = sampleDao,
                trackPointDao = trackPointDao,
                intervalStatDao = intervalStatDao,
                runPauseDao = runPauseDao,
                achievementDao = achievementDao,
                settingsRepository = settingsRepository,
                bookAfterRunWork = { order += "booked" },
            )
            whenever(sessionDao.setRecordsScored(67L)).then { order += "scored"; Unit }

            repository.rescueRunLostToTeardown(67L)

            assertEquals(listOf("booked", "scored"), order)
        }

    @Test
    fun `a booking that throws still leaves the Run finished, and the snapshot is taken here`() =
        runTest {
            // A durable handoff that never happened leaves the rescued Run with no snapshot behind
            // it at all, which is the Run a Clear storage loses. The in-process copy is the
            // second-best answer and is owed whenever the booking did not come back.
            var refreshed = 0
            val repository = SessionRepository(
                sessionDao = sessionDao,
                sampleDao = sampleDao,
                trackPointDao = trackPointDao,
                intervalStatDao = intervalStatDao,
                runPauseDao = runPauseDao,
                achievementDao = achievementDao,
                settingsRepository = settingsRepository,
                refreshHistoryBackup = { refreshed++ },
                bookAfterRunWork = { throw IllegalStateException("WorkManager not initialised") },
            )
            whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
            whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 22))
            whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

            assertTrue(repository.rescueRunLostToTeardown(67L))

            verify(sessionDao).updateSession(any())
            assertEquals(1, refreshed)
        }

    @Test
    fun `a Run that had already been finished is left exactly as it was finished`() = runTest {
        // The race the teardown opens and the launch pass never could: a stop's finalize landing
        // between the teardown and this rescue. The Run's own totals are the ones it banked as it
        // ran; totals rebuilt from the record are the second-best answer, and must not overwrite
        // the best one.
        whenever(sessionDao.getSessionById(67L))
            .thenReturn(interruptedRun(67L).copy(endTime = startedAt + 22_000, durationSeconds = 22))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 22))

        assertEquals(false, repository.rescueRunLostToTeardown(67L))

        verify(sessionDao, never()).updateSession(any())
        assertEquals(0, backupsRefreshed)
        assertTrue(runsBooked.isEmpty())
    }

    @Test
    fun `a teardown that lost a Run with nothing recorded costs nothing`() = runTest {
        whenever(sessionDao.getSessionById(67L)).thenReturn(interruptedRun(67L))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(emptyList())
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        assertEquals(false, repository.rescueRunLostToTeardown(67L))

        verify(sessionDao, never()).updateSession(any())
        assertEquals(0, backupsRefreshed)
        assertTrue(runsBooked.isEmpty())
    }

    @Test
    fun `a Run that cannot be rebuilt at the teardown is left for the next launch`() = runTest {
        whenever(sessionDao.getSessionById(67L)).thenThrow(IllegalStateException("corrupt page"))

        assertEquals(false, repository.rescueRunLostToTeardown(67L))

        verify(sessionDao, never()).updateSession(any())
    }

    @Test
    fun `the cut-off is the caller's, so a run of this process is never in the list`() = runTest {
        // The pass only ever asks about Runs older than the process asking, which is what keeps a
        // live recording out of it without a flag or a look at the recorder.
        whenever(sessionDao.getInterruptedSessionIds(any())).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        verify(sessionDao).getInterruptedSessionIds(eq(processStartedAt))
    }

    @Test
    fun `the empty row of a Run that never recorded a second is taken away again`() = runTest {
        // #314: the insert landed after the teardown had gone, so what is on disk is a row with a
        // start time and nothing else. Left alone it is an interrupted Run no launch pass can ever
        // rebuild, so it is not left alone.
        whenever(sessionDao.deleteSessionIfItRecordedNothing(67L)).thenReturn(1)

        assertTrue(repository.discardRunThatRecordedNothing(67L))
    }

    @Test
    fun `a Run the statement would not take is one this reports it did not take`() = runTest {
        // Which Runs the statement refuses — finished, or with a sample or a fix against it — is
        // proved where it can be: against a real engine, in [DiscardEmptyRunQueryTest]. What is
        // this side of the seam is that a refusal is carried back rather than reported as a
        // deletion, because the Run Journal writes its line from this answer.
        whenever(sessionDao.deleteSessionIfItRecordedNothing(67L)).thenReturn(0)

        assertFalse(repository.discardRunThatRecordedNothing(67L))
    }

    @Test
    fun `the row is never taken away by a delete that asks nothing first`() = runTest {
        // The conditions and the deletion are one statement, which is the whole of the fix: the
        // teardown's waits for the Run's writers are bounded, so a sample can still land between a
        // question asked separately and a delete acting on the answer.
        whenever(sessionDao.deleteSessionIfItRecordedNothing(67L)).thenReturn(1)

        repository.discardRunThatRecordedNothing(67L)

        verify(sessionDao, never()).deleteSessionById(any())
        verify(sessionDao, never()).getSessionById(any())
    }

    @Test
    fun `a database that throws leaves the row where it is`() = runTest {
        whenever(sessionDao.deleteSessionIfItRecordedNothing(67L)).thenThrow(RuntimeException("no disk"))

        assertFalse(repository.discardRunThatRecordedNothing(67L))
    }
}
