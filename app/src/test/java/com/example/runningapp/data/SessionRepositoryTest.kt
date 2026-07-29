package com.example.runningapp.data

import com.example.runningapp.CoachPrescription
import com.example.runningapp.CoachPrescriptionRepository
import com.example.runningapp.CoachWriteScope
import com.example.runningapp.MAX_MAX_HR
import com.example.runningapp.SettingsRepository
import com.example.runningapp.StatedHeartRates
import com.example.runningapp.UserSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SessionRepositoryTest {

    private val mockDao: SessionDao = mock()
    private val mockSettingsRepo: SettingsRepository = mock()
    private lateinit var repository: SessionRepository

    @Before
    fun setup() {
        repository = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo
        )
    }

    @Test
    fun `clampAiResponse reduces run duration when exceeding 110 percent of max load`() = runTest {
        // Given: Max load in last 30 days is 2000 seconds.
        // Budget = 2000 * 1.1 = 2200 seconds.
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 5.0, maxDurationSeconds = 2000L)
        )

        // Given: the active workout carries 5 min (300s) warm-up and 3 min (180s) cool-down (#107).
        // Remaining budget for main set = 2200 - 300 - 180 = 1720 seconds.
        // Given: AI suggests 10 repeats of 3 min (180s) run + 1 min (60s) walk.
        // Total proposed = 300 + (180 + 60) * 10 + 180 = 480 + 2400 = 2880 seconds.
        // This exceeds the 2200 budget.
        val aiResponse = AiCoachResponse(
            nextRunDurationSeconds = 180,
            nextWalkDurationSeconds = 60,
            nextRepeats = 10,
            graduatedToNextStage = false,
            coachMessage = "Great job!"
        )

        // When: We clamp the response
        val clamped = repository.clampAiResponseByRecentLoad(
            aiResponse,
            warmUpSeconds = 300,
            coolDownSeconds = 180
        )

        // Then:
        // Main budget = 1720s.
        // Walk total = 60s * 10 repeats = 600s.
        // Run budget = 1720 - 600 = 1120s.
        // Clamped run seconds per repeat = 1120 / 10 = 112s.
        assertEquals(112, clamped.nextRunDurationSeconds)
        assertEquals(60, clamped.nextWalkDurationSeconds)
        assertEquals(10, clamped.nextRepeats)
    }

    @Test
    fun `clampAiResponse does not change values if within budget`() = runTest {
        // Budget = 3000 * 1.1 = 3300s
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 5.0, maxDurationSeconds = 3000L)
        )

        // Proposed = 300 + (60 + 30) * 5 + 180 = 480 + 450 = 930s (Well within 3300s)
        val aiResponse = AiCoachResponse(
            nextRunDurationSeconds = 60,
            nextWalkDurationSeconds = 30,
            nextRepeats = 5,
            graduatedToNextStage = false,
            coachMessage = "Keep it up!"
        )

        val clamped = repository.clampAiResponseByRecentLoad(
            aiResponse,
            warmUpSeconds = 300,
            coolDownSeconds = 180
        )

        assertEquals(60, clamped.nextRunDurationSeconds)
        assertEquals(30, clamped.nextWalkDurationSeconds)
        assertEquals(5, clamped.nextRepeats)
    }

    @Test
    fun `saveFeelFeedback updates the row when the session is already finalized`() = runTest {
        val sessionId = 42L
        val finalizedSession = RunnerSession(startTime = 1_000L, endTime = 2_000L)
        whenever(mockDao.getSessionById(sessionId)).thenReturn(finalizedSession)

        repository.saveFeelFeedback(sessionId, effort = 7, note = "  Felt good  ")

        verify(mockDao).updateFeelFeedback(sessionId, 7, "Felt good")
    }

    @Test
    fun `saveFeelFeedback refreshes the history backup after writing feedback`() = runTest {
        var refreshCount = 0
        val finalizedSession = RunnerSession(startTime = 1_000L, endTime = 2_000L)
        whenever(mockDao.getSessionById(42L)).thenReturn(finalizedSession)
        val repositoryWithBackup = SessionRepository(
            sessionDao = mockDao,
            refreshHistoryBackup = { refreshCount++ }
        )

        repositoryWithBackup.saveFeelFeedback(sessionId = 42L, effort = 7, note = "Felt good")

        verify(mockDao).updateFeelFeedback(42L, 7, "Felt good")
        assertEquals(1, refreshCount)
    }

    @Test
    fun `saveFeelFeedback does not touch the backup when there is nothing to save`() = runTest {
        var refreshCount = 0
        val repositoryWithBackup = SessionRepository(
            sessionDao = mockDao,
            refreshHistoryBackup = { refreshCount++ }
        )

        repositoryWithBackup.saveFeelFeedback(sessionId = 42L, effort = null, note = "   ")

        verify(mockDao, never()).updateFeelFeedback(any(), anyOrNull(), anyOrNull())
        assertEquals(0, refreshCount)
    }

    @Test
    fun `saveFeelFeedback waits until the session is finalized before updating`() = runTest {
        val sessionId = 42L
        val unfinalizedSession = RunnerSession(startTime = 1_000L, endTime = 0L)
        val finalizedSession = RunnerSession(startTime = 1_000L, endTime = 2_000L)
        whenever(mockDao.getSessionById(sessionId)).thenReturn(unfinalizedSession, finalizedSession)

        repository.saveFeelFeedback(sessionId, effort = 5, note = null, finalizeWaitStepMillis = 1L)

        verify(mockDao, times(1)).updateFeelFeedback(sessionId, 5, null)
    }

    @Test
    fun `saveFeelFeedback is a no-op when effort is null and note is blank`() = runTest {
        val sessionId = 42L

        repository.saveFeelFeedback(sessionId, effort = null, note = "   ")

        verify(mockDao, never()).getSessionById(any())
        verify(mockDao, never()).updateFeelFeedback(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `saveFeelFeedback trims a blank note down to null when effort is provided`() = runTest {
        val sessionId = 42L
        val finalizedSession = RunnerSession(startTime = 1_000L, endTime = 2_000L)
        whenever(mockDao.getSessionById(sessionId)).thenReturn(finalizedSession)

        repository.saveFeelFeedback(sessionId, effort = 3, note = "   ")

        verify(mockDao).updateFeelFeedback(sessionId, 3, null)
    }

    @Test
    fun `getTrackPointsForMap keeps BACKFILL points and only accurate GPS points`() = runTest {
        val sessionId = 7L
        val accurateGps = trackPoint(sessionId, lon = 1.0, accuracy = 15f, source = TrackPointSource.GPS)
        val boundaryGps = trackPoint(sessionId, lon = 2.0, accuracy = 30f, source = TrackPointSource.GPS)
        val noisyGps = trackPoint(sessionId, lon = 3.0, accuracy = 45f, source = TrackPointSource.GPS)
        val unknownAccuracyGps = trackPoint(sessionId, lon = 4.0, accuracy = null, source = TrackPointSource.GPS)
        val backfill = trackPoint(sessionId, lon = 5.0, accuracy = null, source = TrackPointSource.BACKFILL)
        val mockTrackPointDao: TrackPointDao = mock()
        whenever(mockTrackPointDao.getTrackPointsForSessionOnce(sessionId)).thenReturn(
            listOf(accurateGps, boundaryGps, noisyGps, unknownAccuracyGps, backfill)
        )
        val repositoryWithTrackPoints = SessionRepository(sessionDao = mockDao, trackPointDao = mockTrackPointDao)

        val result = repositoryWithTrackPoints.getTrackPointsForMap(sessionId)

        assertEquals(listOf(accurateGps, boundaryGps, backfill), result)
    }

    @Test
    fun `getTrackPointsForMap carries a pause boundary onto the next point the accuracy gate keeps`() = runTest {
        // The fix a run resumes on is the likeliest in the run to be thrown out: GPS was torn down
        // for the pause and is re-acquiring, which is exactly when accuracy is at its worst. Losing
        // the boundary with it would draw and measure the route straight across the pause.
        val sessionId = 7L
        val beforePause = trackPoint(sessionId, lon = 1.0, accuracy = 15f, source = TrackPointSource.GPS)
        val resumedButNoisy = trackPoint(sessionId, lon = 2.0, accuracy = 45f, source = TrackPointSource.GPS)
            .copy(startsAfterPause = true)
        val afterResume = trackPoint(sessionId, lon = 3.0, accuracy = 15f, source = TrackPointSource.GPS)
        val mockTrackPointDao: TrackPointDao = mock()
        whenever(mockTrackPointDao.getTrackPointsForSessionOnce(sessionId)).thenReturn(
            listOf(beforePause, resumedButNoisy, afterResume)
        )
        val repositoryWithTrackPoints = SessionRepository(sessionDao = mockDao, trackPointDao = mockTrackPointDao)

        val result = repositoryWithTrackPoints.getTrackPointsForMap(sessionId)

        assertEquals(listOf(beforePause, afterResume.copy(startsAfterPause = true)), result)
    }

    @Test
    fun `getTrackPointsForMap returns an empty list when no track point dao is configured`() = runTest {
        assertEquals(emptyList<TrackPoint>(), repository.getTrackPointsForMap(sessionId = 7L))
    }

    @Test
    fun `getTrackPointsForMapFlow applies the same accuracy filter as the one-shot read`() = runTest {
        val sessionId = 7L
        val accurateGps = trackPoint(sessionId, lon = 1.0, accuracy = 15f, source = TrackPointSource.GPS)
        val noisyGps = trackPoint(sessionId, lon = 3.0, accuracy = 45f, source = TrackPointSource.GPS)
        val backfill = trackPoint(sessionId, lon = 5.0, accuracy = null, source = TrackPointSource.BACKFILL)
        val mockTrackPointDao: TrackPointDao = mock()
        whenever(mockTrackPointDao.getTrackPointsForSession(sessionId)).thenReturn(
            flowOf(listOf(accurateGps, noisyGps, backfill))
        )
        val repositoryWithTrackPoints = SessionRepository(sessionDao = mockDao, trackPointDao = mockTrackPointDao)

        val result = repositoryWithTrackPoints.getTrackPointsForMapFlow(sessionId).first()

        assertEquals(listOf(accurateGps, backfill), result)
    }

    @Test
    fun `getTrackPointsForMapFlow emits an empty list when no track point dao is configured`() = runTest {
        assertEquals(emptyList<TrackPoint>(), repository.getTrackPointsForMapFlow(sessionId = 7L).first())
    }

    @Test
    fun `hasTrackFlow offers sharing only once a run with a route has finished`() = runTest {
        val sessionId = 7L
        val accurateGps = trackPoint(sessionId, lon = 1.0, accuracy = 15f, source = TrackPointSource.GPS)
        val mockTrackPointDao: TrackPointDao = mock()
        whenever(mockTrackPointDao.getTrackPointsForSession(sessionId)).thenReturn(flowOf(listOf(accurateGps)))

        // A run still being recorded: the row exists and points are arriving, but it has no end time.
        whenever(mockDao.getSessionByIdFlow(sessionId)).thenReturn(flowOf(session(sessionId, endTime = 0L)))
        val duringRun = SessionRepository(sessionDao = mockDao, trackPointDao = mockTrackPointDao)
        assertEquals(false, duringRun.hasTrackFlow(sessionId).first())

        whenever(mockDao.getSessionByIdFlow(sessionId)).thenReturn(flowOf(session(sessionId, endTime = 1_000L)))
        val afterRun = SessionRepository(sessionDao = mockDao, trackPointDao = mockTrackPointDao)
        assertEquals(true, afterRun.hasTrackFlow(sessionId).first())
    }

    @Test
    fun `hasTrackFlow does not offer sharing for a finished run with no route`() = runTest {
        val sessionId = 7L
        val mockTrackPointDao: TrackPointDao = mock()
        whenever(mockTrackPointDao.getTrackPointsForSession(sessionId)).thenReturn(flowOf(emptyList()))
        whenever(mockDao.getSessionByIdFlow(sessionId)).thenReturn(flowOf(session(sessionId, endTime = 1_000L)))
        val repositoryWithTrackPoints = SessionRepository(sessionDao = mockDao, trackPointDao = mockTrackPointDao)

        assertEquals(false, repositoryWithTrackPoints.hasTrackFlow(sessionId).first())
    }

    @Test
    fun `deleteSession refreshes the history backup after removing the row`() = runTest {
        var refreshCount = 0
        val repositoryWithBackup = SessionRepository(
            sessionDao = mockDao,
            refreshHistoryBackup = { refreshCount++ }
        )

        repositoryWithBackup.deleteSession(sessionId = 7L)

        verify(mockDao).deleteSessionById(7L)
        assertEquals(1, refreshCount)
    }

    @Test
    fun `deleteSessions refreshes the history backup when rows are removed`() = runTest {
        var refreshCount = 0
        val repositoryWithBackup = SessionRepository(
            sessionDao = mockDao,
            refreshHistoryBackup = { refreshCount++ }
        )

        repositoryWithBackup.deleteSessions(listOf(1L, 2L))

        verify(mockDao).deleteSessionsByIds(listOf(1L, 2L))
        assertEquals(1, refreshCount)
    }

    @Test
    fun `deleteSessions does not touch the backup when the id list is empty`() = runTest {
        var refreshCount = 0
        val repositoryWithBackup = SessionRepository(
            sessionDao = mockDao,
            refreshHistoryBackup = { refreshCount++ }
        )

        repositoryWithBackup.deleteSessions(emptyList())

        verify(mockDao, never()).deleteSessionsByIds(any())
        assertEquals(0, refreshCount)
    }

    @Test
    fun `the first deliberate max hr set recomputes every run's zone seconds`() = runTest {
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(maxHrEverSet = false)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L, 8L))
        // At Max HR 181 the Zone 2 floor is 109 and the Zone 3 floor is 127.
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(120, 121, 130))
        whenever(mockSampleDao.getRawBpmsForSession(8L)).thenReturn(emptyList())

        repositoryWithSamples.setStatedProfile(maxHr = 181, restingHr = null)

        verify(mockDao).updateZoneSeconds(
            sessionId = 7L, zone1 = 0, zone2 = 2, zone3 = 1, zone4 = 0, zone5 = 0
        )
        verify(mockDao).updateZoneSeconds(
            sessionId = 8L, zone1 = 0, zone2 = 0, zone3 = 0, zone4 = 0, zone5 = 0
        )
        verify(mockSettingsRepo).setStatedHeartRates(eq(181), anyOrNull())
    }

    @Test
    fun `with no samples to recompute from, the one-shot flag is left unspent`() = runTest {
        // Setting the flag without the recompute it pays for would strand history on the
        // placeholder Max HR forever, with nothing left to trigger a second attempt.
        val repositoryWithoutSamples = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(maxHrEverSet = false)))

        repositoryWithoutSamples.setStatedProfile(maxHr = 181, restingHr = null)

        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull())
    }

    @Test
    fun `the flag is only set once the recompute it pays for has finished`() = runTest {
        // An interrupted recompute must be retried, not remembered as done — so the flag lands
        // last. Ordering, not decoration: the other way round strands history half-converted.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(maxHrEverSet = false)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(120))

        repositoryWithSamples.setStatedProfile(maxHr = 181, restingHr = null)

        inOrder(mockDao, mockSettingsRepo) {
            verify(mockDao).updateZoneSeconds(
                sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0
            )
            verify(mockSettingsRepo).setStatedHeartRates(eq(181), anyOrNull())
        }
    }

    @Test
    fun `later max hr changes are future-only, leaving history frozen`() = runTest {
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(maxHrEverSet = true)))

        repositoryWithSamples.setStatedProfile(maxHr = 195, restingHr = null)

        verify(mockSettingsRepo).setStatedHeartRates(eq(195), anyOrNull())
        verify(mockDao, never()).getFinalizedSessionIds()
        verify(mockDao, never()).updateZoneSeconds(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `an unusable max hr is clamped before it reaches storage or the recompute`() = runTest {
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(maxHrEverSet = true)))

        repositoryWithSamples.setStatedProfile(maxHr = 999, restingHr = null)

        verify(mockSettingsRepo).setStatedHeartRates(eq(MAX_MAX_HR), anyOrNull())
    }

    @Test
    fun `the first deliberate max hr set recomputes against both stated numbers`() = runTest {
        // The pair bounds one reserve, so recomputing against half the profile would re-band every
        // run to a model nobody's zones are on: 140 is Tempo under 181 alone and Moderate under
        // 181 with a resting 60.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHrEverSet = false, restingHr = 60)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = 181, restingHr = null)

        verify(mockDao).updateZoneSeconds(
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0
        )
    }

    // --- Stating a resting heart rate (#172) ---

    @Test
    fun `stating a resting hr re-tallies every run from its stored samples`() = runTest {
        // At Max HR 181 with a resting 60 the reserve is 121: Zone 1 runs to 120, Zone 2 starts at
        // 133 and Zone 3 at 145.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L, 8L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(120, 140, 150))
        whenever(mockSampleDao.getRawBpmsForSession(8L)).thenReturn(emptyList())

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        verify(mockDao).updateZoneSeconds(
            sessionId = 7L, zone1 = 1, zone2 = 1, zone3 = 1, zone4 = 0, zone5 = 0
        )
        verify(mockDao).updateZoneSeconds(
            sessionId = 8L, zone1 = 0, zone2 = 0, zone3 = 0, zone4 = 0, zone5 = 0
        )
        verify(mockSettingsRepo).setStatedHeartRates(anyOrNull(), eq(60))
    }

    @Test
    fun `every later resting hr change re-tallies again, unlike max hr`() = runTest {
        // Not the same rule as Max HR by choice: a resting heart rate legitimately falls as fitness
        // improves, and a history banded half at one value and half at another cannot be compared
        // with itself — which is the only thing zone history is for.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true, restingHr = 60)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 52)

        verify(mockDao).updateZoneSeconds(
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0
        )
        verify(mockSettingsRepo).setStatedHeartRates(anyOrNull(), eq(52))
    }

    @Test
    fun `the resting hr is stored only once the re-tally it pays for has finished`() = runTest {
        // Same ordering as Max HR, for the same reason: an interruption must leave the old number
        // on screen and the conversion to be redone, not a settings screen claiming a re-band that
        // half happened.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        inOrder(mockDao, mockSettingsRepo) {
            verify(mockDao).updateZoneSeconds(
                sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0
            )
            verify(mockSettingsRepo).setStatedHeartRates(anyOrNull(), eq(60))
        }
    }

    @Test
    fun `stating a resting hr never spends the max hr one-shot flag`() = runTest {
        // The two numbers are stated independently. Re-banding history against a Max HR still
        // sitting on its placeholder must not be recorded as the runner having chosen one.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHrEverSet = false)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        verify(mockSettingsRepo).setStatedHeartRates(anyOrNull(), eq(60))
        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull())
    }

    @Test
    fun `stating both numbers at once re-tallies once, against the pair being stored`() = runTest {
        // The ordinary first fill-in: both fields pending, one statement. Sent as two the same two
        // edits left different history depending on which coroutine won the lock — resting-first
        // re-banded against the maximum about to be replaced.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 190, maxHrEverSet = false)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        // At Max HR 181 with a resting 60 the reserve is 121: Zone 2 starts at 133, Zone 3 at 145.
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = 181, restingHr = 60)

        verify(mockDao, times(1)).updateZoneSeconds(
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0
        )
        // One write, not two: a collector must never see the new maximum beside the old resting
        // heart rate, or a Run started in that gap pins a profile that was never anyone's.
        verify(mockSettingsRepo).setStatedHeartRates(181, 60)
    }

    @Test
    fun `stating both when max hr is already set re-bands against the stored maximum`() = runTest {
        // The two rules collide here, so the answer is pinned: the resting statement re-tallies
        // everything, and Max HR's future-only rule says the maximum it re-tallies against is the
        // one already in force. Read the other way round, a later Max HR correction would rewrite
        // runs the runner has already read — which is the whole point of the one-shot.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = 200, restingHr = 60)

        // Banded against (181, 60) — the stored maximum — not the 200 on its way to disk.
        verify(mockDao).updateZoneSeconds(
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0
        )
        verify(mockSettingsRepo).setStatedHeartRates(200, 60)
    }

    @Test
    fun `with nothing to re-band from, the resting hr still lands but the flag stays unspent`() = runTest {
        // The two numbers part company here. Max HR's flag is one-shot, so recording the set
        // against a recompute that never ran would strand history on the placeholder for good. A
        // resting heart rate carries no such flag — there is simply no history to move — and
        // dropping it would be the silent discard the screen exists to delete.
        val repositoryWithoutSamples = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(maxHrEverSet = false)))

        repositoryWithoutSamples.setStatedProfile(maxHr = 181, restingHr = 60)

        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull())
        verify(mockSettingsRepo).setStatedHeartRates(anyOrNull(), eq(60))
    }

    @Test
    fun `a re-tally is one transaction, and the backup is taken after it commits`() = runTest {
        // Re-banding walks every finished run one row at a time. Failing part-way would leave the
        // early runs on the new profile and the rest on the old — precisely the split #172 exists
        // to prevent, arriving by accident and with nothing on screen to say so. And a snapshot
        // taken mid-transaction would copy a history half-moved.
        val order = mutableListOf<String>()
        val mockSampleDao: SampleDao = mock()
        mockDao.stub {
            onBlocking { updateZoneSeconds(any(), any(), any(), any(), any(), any()) }
                .doSuspendableAnswer { order += "row" }
        }
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo,
            refreshHistoryBackup = { order += "backup" },
            inTransaction = { block ->
                order += "begin"
                block()
                order += "commit"
            }
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L, 8L))
        whenever(mockSampleDao.getRawBpmsForSession(any())).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        assertEquals(listOf("begin", "row", "row", "commit", "backup"), order)
    }

    @Test
    fun `the statement is noted before history moves and cleared only when it lands`() = runTest {
        // History is in the database and the profile is in DataStore, so the two writes cannot be
        // one transaction. Die in the gap and every finished run is banded against a profile the
        // settings do not hold, with nothing to notice or repair it.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        inOrder(mockDao, mockSettingsRepo) {
            verify(mockSettingsRepo).beginStatement(null, 60)
            verify(mockDao).updateZoneSeconds(
                sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0
            )
            verify(mockSettingsRepo).setStatedHeartRates(null, 60)
        }
    }

    @Test
    fun `a statement that moves no history is not noted at all`() = runTest {
        // Nothing to be interrupted between, so nothing to recover — and a note left for every
        // future-only Max HR change would be a resume pass that re-tallies for no reason.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true)))

        repositoryWithSamples.setStatedProfile(maxHr = 195, restingHr = null)

        verify(mockSettingsRepo, never()).beginStatement(anyOrNull(), anyOrNull())
        verify(mockSettingsRepo).setStatedHeartRates(eq(195), anyOrNull())
    }

    @Test
    fun `an interrupted statement is handed back to be stated again`() = runTest {
        // Handed back rather than applied here: replaying is a statement like any other and has to
        // queue behind whatever the runner states in the meantime, or a resume racing a fresh edit
        // re-bands history to last session's number and stores it over the one just typed.
        whenever(mockSettingsRepo.interruptedStatement()).thenReturn(StatedHeartRates(null, 60))

        assertEquals(StatedHeartRates(null, 60), repository.interruptedStatement())

        verify(mockSettingsRepo, never()).setStatedHeartRates(anyOrNull(), anyOrNull())
        verify(mockDao, never()).updateZoneSeconds(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `with nothing interrupted, startup touches nothing`() = runTest {
        whenever(mockSettingsRepo.interruptedStatement()).thenReturn(null)

        assertNull(repository.interruptedStatement())

        verify(mockSettingsRepo, never()).discardStatement()
    }

    @Test
    fun `a note with nothing in it is dropped rather than found again every launch`() = runTest {
        // Unreachable from beginStatement, so this is a corrupt note. Left in place it would be
        // read, logged and skipped on every launch for ever.
        whenever(mockSettingsRepo.interruptedStatement()).thenReturn(StatedHeartRates(null, null))

        assertNull(repository.interruptedStatement())

        verify(mockSettingsRepo).discardStatement()
    }

    @Test
    fun `a statement with neither number touches nothing`() = runTest {
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mock(),
            settingsRepository = mockSettingsRepo
        )

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = null)

        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull())
        verify(mockSettingsRepo, never()).setStatedHeartRates(anyOrNull(), any())
        verify(mockDao, never()).updateZoneSeconds(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `the two halves of the profile door cannot interleave`() = runTest {
        // Leaving settings commits both fields at once, each on its own IO coroutine — the ordinary
        // case the first time both numbers are filled in. Unserialized, each snapshots the settings
        // before the other's write lands and re-tallies against a pair that was never stored.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))
        // Parks the resting-HR door mid-write, so the Max HR door has something to interleave with.
        val heldMidWrite = CompletableDeferred<Unit>()
        mockSettingsRepo.stub {
            onBlocking { setStatedHeartRates(anyOrNull(), any()) }
                .doSuspendableAnswer { heldMidWrite.await() }
        }

        val resting = launch { repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60) }
        runCurrent()
        val maximum = launch { repositoryWithSamples.setStatedProfile(maxHr = 190, restingHr = null) }
        runCurrent()

        // The second door has not read, re-tallied or stored anything while the first is open.
        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull())

        heldMidWrite.complete(Unit)
        listOf(resting, maximum).joinAll()
        verify(mockSettingsRepo).setStatedHeartRates(eq(190), anyOrNull())
    }

    // --- What the coach may prescribe (#113) ---

    @Test
    fun `an omitted target zone leaves the workout's own target alone`() {
        // "Aerobic Foundation" is a Zone 2 workout; the coach adjusting only the intervals must not
        // silently move the target it never spoke about.
        assertEquals(2, coachTargetZone(requested = null, workoutTargetZone = 2, settingsTargetZone = 4))
        assertEquals(4, coachTargetZone(requested = 9, workoutTargetZone = 4, settingsTargetZone = 2))
    }

    @Test
    fun `with no plan attached an omitted target zone falls back to the global`() {
        assertEquals(3, coachTargetZone(requested = null, workoutTargetZone = null, settingsTargetZone = 3))
    }

    @Test
    fun `the coach can move the target inside the coaching range`() {
        assertEquals(2, coachTargetZone(requested = 2, workoutTargetZone = 4, settingsTargetZone = 4))
        assertEquals(4, coachTargetZone(requested = 4, workoutTargetZone = 2, settingsTargetZone = 2))
    }

    @Test
    fun `an edge zone is snapped, so the coach cannot re-open the overstated in-target bug`() {
        // Zone 1 and Zone 5 overstate time in target (#117). The picker is closed to them; the
        // coach must not be the back door.
        assertEquals(2, coachTargetZone(requested = 1, workoutTargetZone = 4, settingsTargetZone = 4))
        assertEquals(4, coachTargetZone(requested = 5, workoutTargetZone = 2, settingsTargetZone = 2))
    }

    @Test
    fun `a graduation clears the prescription instead of writing one for the stage just left`() = runTest {
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = mockPrescriptions,
            aiCoachClient = mockCoach
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "base_builder"))
        )
        // What the evaluation reasoned about — every coach write has to carry it, so a plan
        // chosen while Gemini was still thinking can be refused at the write itself.
        val activeScope = CoachWriteScope("5k_sub_25", "base_builder")
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = 3,
                graduatedToNextStage = true,
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder")

        verify(mockSettingsRepo).advanceStageAndClearPrescription("sub_30_bridge", activeScope)
        verify(mockPrescriptions, never()).prescribe(any(), any())
        // The debrief is about the run just finished, so it survives the graduation.
        verify(mockSettingsRepo).setLatestCoachMessage("Stage complete.", activeScope)
    }

    @Test
    fun `a normal evaluation writes one prescription and touches no setting but the debrief`() = runTest {
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = mockPrescriptions,
            aiCoachClient = mockCoach
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "base_builder"))
        )
        // What the evaluation reasoned about — every coach write has to carry it, so a plan
        // chosen while Gemini was still thinking can be refused at the write itself.
        val activeScope = CoachWriteScope("5k_sub_25", "base_builder")
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = 3,
                graduatedToNextStage = false,
                coachMessage = "Good session."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder")

        val prescribed = argumentCaptor<CoachPrescription>()
        verify(mockPrescriptions).prescribe(prescribed.capture(), eq(activeScope))
        assertEquals(360, prescribed.firstValue.runDurationSeconds)
        assertEquals(60, prescribed.firstValue.walkDurationSeconds)
        assertEquals(5, prescribed.firstValue.totalRepeats)
        assertEquals(3, prescribed.firstValue.targetZone)
        verify(mockSettingsRepo).setLatestCoachMessage("Good session.", activeScope)
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescription(anyOrNull(), any())
        verify(mockSettingsRepo, never()).setCoachingEnabled(any())
        verify(mockSettingsRepo, never()).setTargetZone(any())
        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull())
    }

    private fun session(id: Long, endTime: Long) = RunnerSession(
        id = id,
        startTime = 0L,
        endTime = endTime,
        durationSeconds = 60,
        avgBpm = 130,
        maxBpm = 150,
        targetZone = 2
    )

    private fun trackPoint(sessionId: Long, lon: Double, accuracy: Float?, source: String) = TrackPoint(
        sessionId = sessionId,
        latitude = 0.0,
        longitude = lon,
        horizontalAccuracyMeters = accuracy,
        timestampMillis = 0L,
        source = source
    )
}
