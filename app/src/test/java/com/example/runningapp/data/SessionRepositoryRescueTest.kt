package com.example.runningapp.data

import com.example.runningapp.HrProfile
import com.example.runningapp.SettingsRepository
import com.example.runningapp.UserSettings
import com.example.runningapp.tallyZoneSeconds
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    // Wired, because scoring is a no-op without a book to write to — and this pass has to be shown
    // both marking a Run it scored and leaving one it could not (#210).
    private val achievementDao: AchievementDao = mock()
    private val settingsRepository: SettingsRepository = mock {
        on { userSettingsFlow }.thenReturn(flowOf(UserSettings(maxHr = 185)))
    }

    private var backupsRefreshed = 0

    private val repository = SessionRepository(
        sessionDao = sessionDao,
        sampleDao = sampleDao,
        trackPointDao = trackPointDao,
        achievementDao = achievementDao,
        settingsRepository = settingsRepository,
        refreshHistoryBackup = { backupsRefreshed++ },
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
            .thenReturn(interruptedRun(67L).copy(maxHrAtRun = 195, restingHrAtRun = 0))
        whenever(sampleDao.getSamplesForSessionOnce(67L)).thenReturn(samples(67L, 60))
        whenever(trackPointDao.getTrackPointsForSessionOnce(67L)).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        val saved = argumentCaptor<RunnerSession>()
        verify(sessionDao).updateSession(saved.capture())
        val onItsOwnProfile = tallyZoneSeconds(List(60) { 130 }, HrProfile(maxHr = 195))
        assertEquals(onItsOwnProfile.zone2, saved.firstValue.zone2Seconds)
        assertEquals(onItsOwnProfile.zone3, saved.firstValue.zone3Seconds)
        // And the run keeps saying what it was banded on, rather than being rewritten to the global.
        assertEquals(195, saved.firstValue.maxHrAtRun)
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

    @Test
    fun `the cut-off is the caller's, so a run of this process is never in the list`() = runTest {
        // The pass only ever asks about Runs older than the process asking, which is what keeps a
        // live recording out of it without a flag or a look at the recorder.
        whenever(sessionDao.getInterruptedSessionIds(any())).thenReturn(emptyList())

        repository.rescueInterruptedRuns(processStartedAt)

        verify(sessionDao).getInterruptedSessionIds(eq(processStartedAt))
    }
}
