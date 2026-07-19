package com.example.runningapp.data

import com.example.runningapp.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
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

    private fun trackPoint(sessionId: Long, lon: Double, accuracy: Float?, source: String) = TrackPoint(
        sessionId = sessionId,
        latitude = 0.0,
        longitude = lon,
        horizontalAccuracyMeters = accuracy,
        timestampMillis = 0L,
        source = source
    )
}
