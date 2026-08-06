package com.example.runningapp.data

import com.example.runningapp.COACH_PRESCRIPTION_MAX_AGE_DAYS
import com.example.runningapp.CoachPrescription
import com.example.runningapp.CoachPrescriptions
import com.example.runningapp.CoachPrescriptionRepository
import com.example.runningapp.CoachWriteScope
import com.example.runningapp.HrProfile
import com.example.runningapp.MAX_MAX_HR
import com.example.runningapp.RunType
import com.example.runningapp.SettingsRepository
import com.example.runningapp.StatedHeartRates
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.UserSettings
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.training.FormVerdict
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
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
import java.time.LocalDate
import java.time.ZoneOffset

/** Monday 5 January 2026, midday UTC — the first day of the training weeks these tests read. */
private const val DAY_MILLIS_2026_01_05 = 1_767_614_400_000L
private const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000

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
        // A history with nothing scored in it, which is what most of these tests are about — a mock
        // left unstubbed answers a suspending read with null rather than an empty list. Tests that
        // care about the curves stub these over.
        whenever(mockDao.getScoredRunsFlow()).thenReturn(flowOf(emptyList()))
        whenever(mockDao.getRunVolumesFlow()).thenReturn(flowOf(emptyList()))
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

    // The stage's own workout: 6 x (180s run + 60s walk) = 1440s of main set, 1080s of it running.
    private val introIntervals = WorkoutTemplate(
        id = "w1_s1",
        title = "Intro Intervals",
        targetZone = 2,
        runDurationSeconds = 180,
        walkDurationSeconds = 60,
        totalRepeats = 6,
        runType = RunType.LONG
    )

    @Test
    fun `floorAiResponse raises a prescription that asks for less work than the workout`() {
        // The coach asks for 3 x (60s run + 60s walk) = 360s — well under the plan.
        val eased = AiCoachResponse(
            nextRunDurationSeconds = 60,
            nextWalkDurationSeconds = 60,
            nextRepeats = 3,
            graduatedToNextStage = false,
            coachMessage = "Take it easy."
        )

        val floored = repository.floorAiResponseAtWorkout(eased, introIntervals)

        assertEquals(180, floored.nextRunDurationSeconds)
        assertEquals(60, floored.nextWalkDurationSeconds)
        assertEquals(6, floored.nextRepeats)
    }

    @Test
    fun `floorAiResponse raises a prescription that pads the same total with walking`() {
        // 6 x (30s run + 210s walk) = 1440s, matching the plan second for second, on a sixth of
        // the running. Total alone would wave this through.
        val padded = AiCoachResponse(
            nextRunDurationSeconds = 30,
            nextWalkDurationSeconds = 210,
            nextRepeats = 6,
            graduatedToNextStage = false,
            coachMessage = "Mostly walking today."
        )

        val floored = repository.floorAiResponseAtWorkout(padded, introIntervals)

        assertEquals(180, floored.nextRunDurationSeconds)
        assertEquals(60, floored.nextWalkDurationSeconds)
        assertEquals(6, floored.nextRepeats)
    }

    @Test
    fun `floorAiResponse raises a prescription the ceiling clamped below the workout`() = runTest {
        // A 900s run — one cut short — is the 30-day maximum, so the ceiling allows 990s total.
        // Against the workout's own 480s/180s envelope that leaves 330s for the main set, less
        // than the workout's 1440s. The floor outranks it.
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 2.0, maxDurationSeconds = 900L)
        )

        val onPlan = AiCoachResponse(
            nextRunDurationSeconds = 180,
            nextWalkDurationSeconds = 60,
            nextRepeats = 6,
            graduatedToNextStage = false,
            coachMessage = "Same again."
        )

        val ceilinged = repository.clampAiResponseByRecentLoad(
            onPlan,
            warmUpSeconds = introIntervals.warmUpSeconds,
            coolDownSeconds = introIntervals.coolDownSeconds
        )
        // The ceiling did cut it — that rule is untouched.
        assertEquals(6, ceilinged.nextRunDurationSeconds)
        assertEquals(5, ceilinged.nextRepeats)

        val floored = repository.floorAiResponseAtWorkout(ceilinged, introIntervals)

        assertEquals(180, floored.nextRunDurationSeconds)
        assertEquals(60, floored.nextWalkDurationSeconds)
        assertEquals(6, floored.nextRepeats)
    }

    @Test
    fun `floorAiResponse leaves a prescription between the floor and the ceiling alone`() {
        // 6 x (240s + 60s) = 1800s — more than the plan's 1440s, so the floor has no say.
        val harder = AiCoachResponse(
            nextRunDurationSeconds = 240,
            nextWalkDurationSeconds = 60,
            nextRepeats = 6,
            graduatedToNextStage = false,
            coachMessage = "Push on."
        )

        val floored = repository.floorAiResponseAtWorkout(harder, introIntervals)

        assertEquals(240, floored.nextRunDurationSeconds)
        assertEquals(60, floored.nextWalkDurationSeconds)
        assertEquals(6, floored.nextRepeats)
    }

    @Test
    fun `floorAiResponse holds with no run history, where the ceiling does nothing`() = runTest {
        // No run history at all: the ceiling reads a zero maximum and passes the response straight
        // through. The floor is the only rule left standing.
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        val eased = AiCoachResponse(
            nextRunDurationSeconds = 30,
            nextWalkDurationSeconds = 30,
            nextRepeats = 2,
            graduatedToNextStage = false,
            coachMessage = "Ease back in."
        )

        val ceilinged = repository.clampAiResponseByRecentLoad(
            eased,
            warmUpSeconds = introIntervals.warmUpSeconds,
            coolDownSeconds = introIntervals.coolDownSeconds
        )
        assertEquals(30, ceilinged.nextRunDurationSeconds)

        val floored = repository.floorAiResponseAtWorkout(ceilinged, introIntervals)

        assertEquals(180, floored.nextRunDurationSeconds)
        assertEquals(60, floored.nextWalkDurationSeconds)
        assertEquals(6, floored.nextRepeats)
    }

    @Test
    fun `floorAiResponse passes the response through when no workout is queued`() {
        val eased = AiCoachResponse(
            nextRunDurationSeconds = 30,
            nextWalkDurationSeconds = 30,
            nextRepeats = 2,
            graduatedToNextStage = false,
            coachMessage = "Ease back in."
        )

        val floored = repository.floorAiResponseAtWorkout(eased, workout = null)

        assertEquals(30, floored.nextRunDurationSeconds)
        assertEquals(30, floored.nextWalkDurationSeconds)
        assertEquals(2, floored.nextRepeats)
    }

    /** A runner carrying more than they have absorbed: Fatigue above Fitness (#248). */
    private val carryingIt = AiFitnessAndForm(
        fitness = 13,
        fatigue = 23,
        form = -14,
        verdict = FormVerdict.FATIGUED,
        weeklyEffortScores = listOf(700, 200),
        todaysRunIsInTheNumbers = true
    )

    /**
     * 6 x (300s run + 60s walk) — more work than the stage's own workout, so the floor has no say
     * and nothing but the hold would take it back down.
     */
    private val harder = AiCoachResponse(
        nextRunDurationSeconds = 300,
        nextWalkDurationSeconds = 60,
        nextRepeats = 6,
        nextTargetZone = 3,
        graduatedToNextStage = false,
        coachMessage = "Adding a bit today."
    )

    @Test
    fun `holdAiResponse gives a fatigued runner the workout, whatever the coach asked for`() {
        val held = repository.holdAiResponseAtWorkout(harder, introIntervals, carryingIt)

        assertEquals(180, held.nextRunDurationSeconds)
        assertEquals(60, held.nextWalkDurationSeconds)
        assertEquals(6, held.nextRepeats)
        // The hold is about how much work, not how hard: the coach's target zone stands, exactly as
        // it does under the floor.
        assertEquals(3, held.nextTargetZone)
        assertEquals("Adding a bit today.", held.coachMessage)
    }

    @Test
    fun `holdAiResponse leaves a runner who has absorbed their training alone`() {
        val fresh = carryingIt.copy(fitness = 30, fatigue = 12, form = 18, verdict = FormVerdict.FRESH)
        val held = repository.holdAiResponseAtWorkout(harder, introIntervals, fresh)

        assertEquals(300, held.nextRunDurationSeconds)
        assertEquals(6, held.nextRepeats)
    }

    @Test
    fun `holdAiResponse holds on the pair, not on the Form verdict, where the two disagree`() {
        // Form is where the day started; the pair is where it stands after today's Run. A runner who
        // began the day level can finish it carrying more than they have absorbed, and still print
        // "neutral" — the pair is what the coach was told to read, so it is what is held to.
        val movedToday = carryingIt.copy(
            fitness = 20,
            fatigue = 26,
            form = 2,
            verdict = FormVerdict.NEUTRAL
        )

        val held = repository.holdAiResponseAtWorkout(harder, introIntervals, movedToday)

        assertEquals(180, held.nextRunDurationSeconds)
        assertEquals(6, held.nextRepeats)
    }

    @Test
    fun `holdAiResponse reads level Fitness and Fatigue as absorbed, the same way the prompt does`() {
        // "Fatigue above Fitness" is the line the coach is given, on these same rounded numbers —
        // equal is not above it, in the prompt or here.
        val level = carryingIt.copy(fitness = 23, fatigue = 23, form = 0, verdict = FormVerdict.NEUTRAL)
        val held = repository.holdAiResponseAtWorkout(harder, introIntervals, level)

        assertEquals(300, held.nextRunDurationSeconds)
        assertEquals(6, held.nextRepeats)
    }

    @Test
    fun `holdAiResponse passes the response through with no training state to read`() {
        // No scored history at all, so there is no fatigue reading to hold anyone on — the coach was
        // told nothing about it either.
        assertEquals(
            300,
            repository.holdAiResponseAtWorkout(harder, introIntervals, fitnessAndForm = null)
                .nextRunDurationSeconds
        )
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

    // --- Changing what was said about a Run, from its own page (#80) ---------------------------

    @Test
    fun `editFeelFeedback writes an effort and a note onto a finished Run`() = runTest {
        val finished = RunnerSession(startTime = 1_000L, endTime = 2_000L)
        whenever(mockDao.getSessionById(42L)).thenReturn(finished)
        var refreshCount = 0
        val repositoryWithBackup = SessionRepository(
            sessionDao = mockDao,
            refreshHistoryBackup = { refreshCount++ }
        )

        repositoryWithBackup.editFeelFeedback(42L, effort = 7, note = "  Felt strong  ")

        verify(mockDao).updateFeelFeedback(42L, 7, "Felt strong")
        assertEquals(1, refreshCount)
    }

    @Test
    fun `editFeelFeedback empties a note that is cleared`() = runTest {
        // The difference from the sheet at the finish: nothing left to save is exactly what a
        // runner clearing a note is asking for, so it is written rather than treated as a skip.
        val finished = RunnerSession(
            startTime = 1_000L,
            endTime = 2_000L,
            perceivedEffort = 7,
            sessionNote = "Felt strong"
        )
        whenever(mockDao.getSessionById(42L)).thenReturn(finished)

        repository.editFeelFeedback(42L, effort = null, note = "")

        verify(mockDao).updateFeelFeedback(42L, null, null)
    }

    @Test
    fun `editFeelFeedback leaves the row alone when nothing changed`() = runTest {
        val finished = RunnerSession(
            startTime = 1_000L,
            endTime = 2_000L,
            perceivedEffort = 7,
            sessionNote = "Felt strong"
        )
        whenever(mockDao.getSessionById(42L)).thenReturn(finished)
        var refreshCount = 0
        val repositoryWithBackup = SessionRepository(
            sessionDao = mockDao,
            refreshHistoryBackup = { refreshCount++ }
        )

        repositoryWithBackup.editFeelFeedback(42L, effort = 7, note = "Felt strong")

        verify(mockDao, never()).updateFeelFeedback(any(), anyOrNull(), anyOrNull())
        assertEquals(0, refreshCount)
    }

    @Test
    fun `editFeelFeedback refuses a Run that is still being recorded`() = runTest {
        val unfinished = RunnerSession(startTime = 1_000L, endTime = 0L)
        whenever(mockDao.getSessionById(42L)).thenReturn(unfinished)

        repository.editFeelFeedback(42L, effort = 7, note = "Felt strong")

        // finalizeRun writes the row whole, so anything landing before it would be overwritten.
        verify(mockDao, never()).updateFeelFeedback(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `editFeelFeedback refuses a Run that is not there`() = runTest {
        whenever(mockDao.getSessionById(42L)).thenReturn(null)

        repository.editFeelFeedback(42L, effort = 7, note = "Felt strong")

        verify(mockDao, never()).updateFeelFeedback(any(), anyOrNull(), anyOrNull())
    }

    // --- Stating how far a treadmill Run went (#231, ADR 0008) ---------------------------------

    @Test
    fun `a stated distance is written with the pace that follows from it`() = runTest {
        // 5 km in 25 minutes is 5:00/km, measured over the Run's own clock: there is no track to
        // measure a moving time from.
        whenever(mockDao.getSessionById(42L)).thenReturn(aTreadmillRun(id = 42, seconds = 1_500))
        var refreshCount = 0
        val repositoryWithBackup = SessionRepository(
            sessionDao = mockDao,
            refreshHistoryBackup = { refreshCount++ }
        )

        repositoryWithBackup.stateDistance(42L, distanceKm = 5.0)

        verify(mockDao).setStatedDistance(42L, 5.0, 5.0)
        // The snapshot finalizeRun took went out before the number existed, so a Run restored from
        // it would come back with the distance gone.
        assertEquals(1, refreshCount)
    }

    @Test
    fun `a stated distance waits for the Run to be finished before it is written`() = runTest {
        // The sheet asking for the number is on screen from the moment STOP is pressed, and
        // finalizeRun writes the row whole — so a distance landing first would be overwritten.
        val stillWriting = aTreadmillRun(id = 42, seconds = 1_500).copy(endTime = 0L)
        whenever(mockDao.getSessionById(42L))
            .thenReturn(stillWriting, aTreadmillRun(id = 42, seconds = 1_500))

        repository.stateDistance(42L, distanceKm = 5.0, finalizeWaitStepMillis = 1L)

        verify(mockDao, times(1)).setStatedDistance(42L, 5.0, 5.0)
    }

    @Test
    fun `only a treadmill Run can be told a distance`() = runTest {
        // An outdoor Run's distance is measured, and one whose GPS recorded nothing is not rescued
        // this way — that restriction is what keeps a stated distance to one column and no
        // migration (ADR 0008).
        whenever(mockDao.getSessionById(42L))
            .thenReturn(session(id = 42, endTime = 1_000L).copy(runMode = "outdoor"))

        repository.stateDistance(42L, distanceKm = 5.0)

        verify(mockDao, never()).setStatedDistance(any(), any(), any())
    }

    @Test
    fun `a number that is not a distance is refused before anything is read`() = runTest {
        repository.stateDistance(42L, distanceKm = -3.0)
        repository.stateDistance(42L, distanceKm = 0.0)
        repository.stateDistance(42L, distanceKm = Double.NaN)

        verify(mockDao, never()).getSessionById(any())
        verify(mockDao, never()).setStatedDistance(any(), any(), any())
    }

    @Test
    fun `stating the number already there costs nothing`() = runTest {
        whenever(mockDao.getSessionById(42L))
            .thenReturn(aTreadmillRun(id = 42, seconds = 1_500).copy(distanceKm = 5.0))
        var refreshCount = 0
        val repositoryWithBackup = SessionRepository(
            sessionDao = mockDao,
            refreshHistoryBackup = { refreshCount++ }
        )

        repositoryWithBackup.stateDistance(42L, distanceKm = 5.0)

        verify(mockDao, never()).setStatedDistance(any(), any(), any())
        assertEquals(0, refreshCount)
    }

    @Test
    fun `a stated distance is scored against the record book`() = runTest {
        val run = aTreadmillRun(id = 42, seconds = 1_500)
        whenever(mockDao.getSessionById(42L)).thenReturn(run, run.copy(distanceKm = 12.0))
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao
        )

        repositoryWithRecords.stateDistance(42L, distanceKm = 12.0)

        // Scored from the row as it stands *after* the write, or the Run would be ranked on the
        // distance it no longer has. The longest Run of an indoor winter takes the record (ADR 0008).
        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(RecordType.LONGEST_DISTANCE to 12_000.0, RecordType.LONGEST_DURATION to 1_500.0),
            book.firstValue.map { it.type to it.value },
        )
    }

    @Test
    fun `a distance corrected downward rebuilds the record it held, promoting the run behind it`() = runTest {
        val medalHolder = aTreadmillRun(id = 2, seconds = 1_500).copy(distanceKm = 12.0)
        whenever(mockDao.getSessionById(2L)).thenReturn(medalHolder)
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.LONGEST_DISTANCE, medal = Medal.GOLD, value = 12_000.0))
        )
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        // History as it stands once the typo is corrected: the 9 km Run that was second exists
        // nowhere but here — only the top three are banked, so re-scoring Run 2 alone could not
        // find it.
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_200).copy(distanceKm = 9.0),
                medalHolder.copy(distanceKm = 1.25),
            )
        )
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao
        )

        repositoryWithRecords.stateDistance(2L, distanceKm = 1.25)

        verify(mockDao).setStatedDistance(2L, 1.25, 20.0)
        val book = argumentCaptor<List<Achievement>>()
        // Only the longest distance is rebuilt: the duration is untouched by a distance, and a
        // treadmill Run contests none of the fastest five.
        verify(mockAchievementDao).deleteAchievementsOfTypes(listOf(RecordType.LONGEST_DISTANCE))
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(1L to Medal.GOLD, 2L to Medal.SILVER),
            book.firstValue.map { it.sessionId to it.medal },
        )
    }

    @Test
    fun `withdrawing a distance leaves the Run with none, rather than one of zero`() = runTest {
        val run = aTreadmillRun(id = 42, seconds = 1_500).copy(distanceKm = 5.0)
        whenever(mockDao.getSessionById(42L)).thenReturn(run)
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(42L))).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao
        )

        repositoryWithRecords.stateDistance(42L, distanceKm = null)

        // A zero is what a Run nobody stated a distance for has always carried, and it contests no
        // distance record — so the pace goes with it.
        verify(mockDao).setStatedDistance(42L, 0.0, 0.0)
    }

    @Test
    fun `a withdrawn distance gives up the medal it held, rather than keeping it at a number the Run no longer has`() = runTest {
        val medalHolder = aTreadmillRun(id = 2, seconds = 1_500).copy(distanceKm = 12.0)
        whenever(mockDao.getSessionById(2L)).thenReturn(medalHolder)
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.LONGEST_DISTANCE, medal = Medal.GOLD, value = 12_000.0))
        )
        // The book still holds Run 2's gold while the rebuild is measuring, which is what the
        // rebuild has to see past: a Run that now measures to nothing must not have its old row
        // carried back in as a claim of its own.
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.LONGEST_DISTANCE, medal = Medal.GOLD, value = 12_000.0))
        )
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_200).copy(distanceKm = 9.0),
                medalHolder.copy(distanceKm = 0.0),
            )
        )
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao
        )

        repositoryWithRecords.stateDistance(2L, distanceKm = null)

        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(1L to 9_000.0),
            book.firstValue.map { it.sessionId to it.value },
        )
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

        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 7L, zone1 = 0, zone2 = 2, zone3 = 1, zone4 = 0, zone5 = 0, effortScore = 0
        )
        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 8L, zone1 = 0, zone2 = 0, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = null
        )
        verify(mockSettingsRepo).setStatedHeartRates(eq(181), anyOrNull(), anyOrNull())
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

        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull(), anyOrNull())
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
            verify(mockDao).updateZoneSecondsAndEffort(
                sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0
            )
            verify(mockSettingsRepo).setStatedHeartRates(eq(181), anyOrNull(), anyOrNull())
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

        // Null third argument is the contract: this moved no history, so it must neither record a
        // maximum for history nor clear a note left by an interrupted re-tally.
        verify(mockSettingsRepo).setStatedHeartRates(195, null, null)
        verify(mockDao, never()).getFinalizedSessionIds()
        verify(mockDao, never()).updateZoneSecondsAndEffort(any(), any(), any(), any(), any(), any(), anyOrNull())
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

        verify(mockSettingsRepo).setStatedHeartRates(eq(MAX_MAX_HR), anyOrNull(), anyOrNull())
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

        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0
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
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true, historyMaxHr = 181)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L, 8L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(120, 140, 150))
        whenever(mockSampleDao.getRawBpmsForSession(8L)).thenReturn(emptyList())

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 7L, zone1 = 1, zone2 = 1, zone3 = 1, zone4 = 0, zone5 = 0, effortScore = 0
        )
        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 8L, zone1 = 0, zone2 = 0, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = null
        )
        verify(mockSettingsRepo).setStatedHeartRates(anyOrNull(), eq(60), anyOrNull())
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
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true, restingHr = 60, historyMaxHr = 181)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 52)

        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0
        )
        verify(mockSettingsRepo).setStatedHeartRates(anyOrNull(), eq(52), anyOrNull())
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
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true, historyMaxHr = 181)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        inOrder(mockDao, mockSettingsRepo) {
            verify(mockDao).updateZoneSecondsAndEffort(
                sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0
            )
            verify(mockSettingsRepo).setStatedHeartRates(anyOrNull(), eq(60), anyOrNull())
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

        verify(mockSettingsRepo).setStatedHeartRates(anyOrNull(), eq(60), anyOrNull())
        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull(), anyOrNull())
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

        verify(mockDao, times(1)).updateZoneSecondsAndEffort(
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0
        )
        // One write, not two: a collector must never see the new maximum beside the old resting
        // heart rate, or a Run started in that gap pins a profile that was never anyone's.
        verify(mockSettingsRepo).setStatedHeartRates(181, 60, 181)
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
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true, historyMaxHr = 181)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = 200, restingHr = 60)

        // Banded against (181, 60) — the stored maximum — not the 200 on its way to disk.
        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0
        )
        verify(mockSettingsRepo).setStatedHeartRates(200, 60, 181)
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

        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull(), anyOrNull())
        // Nothing was re-banded — there was nothing to re-band from — so no note is finished.
        verify(mockSettingsRepo).setStatedHeartRates(null, 60, null)
    }

    @Test
    fun `a resting hr statement re-bands against the maximum history is already on`() = runTest {
        // Max HR 181 was stated, then corrected to 195. That correction is future-only, so the runs
        // stay banded on 181 — deliberately, because a correction must not rewrite runs already
        // read (#112). A resting-HR statement must not drag them onto 195 by a side door.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(maxHr = 195, maxHrEverSet = true, historyMaxHr = 181))
        )
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(150))

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        // 150 is the beat that tells the two apart. Against (181, 60) the reserve is 121, so Zone 3
        // runs 145-156 and it lands there. Against (195, 60) the reserve is 135, Zone 3 starts at
        // 155, and it would drop to Zone 2 — a run the runner has already read, silently re-filed.
        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 7L, zone1 = 0, zone2 = 0, zone3 = 1, zone4 = 0, zone5 = 0, effortScore = 0
        )
        verify(mockSettingsRepo).setStatedHeartRates(null, 60, 181)
    }

    @Test
    fun `a re-tally re-scores what it re-bands, so a run's effort and its zones agree`() = runTest {
        // The Effort Score is weighted against the same zone edges the chart draws (#61), so moving
        // the edges moves both or neither — one hour described by two numbers that no longer mean
        // the same seconds is exactly what #99 says must not happen.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true, historyMaxHr = 181)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        // Ten minutes at 140: against (181, 60) the reserve is 121, so Zone 2 runs 133-144 and the
        // whole ten minutes weighs 2 — a Score of 20.
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(List(600) { 140 })

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 7L, zone1 = 0, zone2 = 600, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 20
        )
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
            onBlocking { updateZoneSecondsAndEffort(any(), any(), any(), any(), any(), any(), anyOrNull()) }
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
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true, historyMaxHr = 181)))
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
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true, historyMaxHr = 181)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        inOrder(mockDao, mockSettingsRepo) {
            verify(mockSettingsRepo).beginStatement(null, 60)
            verify(mockDao).updateZoneSecondsAndEffort(
                sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0
            )
            verify(mockSettingsRepo).setStatedHeartRates(null, 60, 181)
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
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true, historyMaxHr = 181)))

        repositoryWithSamples.setStatedProfile(maxHr = 195, restingHr = null)

        verify(mockSettingsRepo, never()).beginStatement(anyOrNull(), anyOrNull())
        verify(mockSettingsRepo).setStatedHeartRates(eq(195), anyOrNull(), anyOrNull())
    }

    @Test
    fun `an interrupted statement is handed back to be stated again`() = runTest {
        // Handed back rather than applied here: replaying is a statement like any other and has to
        // queue behind whatever the runner states in the meantime, or a resume racing a fresh edit
        // re-bands history to last session's number and stores it over the one just typed.
        whenever(mockSettingsRepo.interruptedStatement()).thenReturn(StatedHeartRates(null, 60))

        assertEquals(StatedHeartRates(null, 60), repository.interruptedStatement())

        verify(mockSettingsRepo, never()).setStatedHeartRates(anyOrNull(), anyOrNull(), anyOrNull())
        verify(mockDao, never()).updateZoneSecondsAndEffort(any(), any(), any(), any(), any(), any(), anyOrNull())
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

        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull(), anyOrNull())
        verify(mockSettingsRepo, never()).setStatedHeartRates(anyOrNull(), any(), anyOrNull())
        verify(mockDao, never()).updateZoneSecondsAndEffort(any(), any(), any(), any(), any(), any(), anyOrNull())
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
            .thenReturn(flowOf(UserSettings(maxHr = 181, maxHrEverSet = true, historyMaxHr = 181)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(140))
        // Parks the resting-HR door mid-write, so the Max HR door has something to interleave with.
        val heldMidWrite = CompletableDeferred<Unit>()
        mockSettingsRepo.stub {
            onBlocking { setStatedHeartRates(anyOrNull(), any(), anyOrNull()) }
                .doSuspendableAnswer { heldMidWrite.await() }
        }

        val resting = launch { repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60) }
        runCurrent()
        val maximum = launch { repositoryWithSamples.setStatedProfile(maxHr = 190, restingHr = null) }
        runCurrent()

        // The second door has not read, re-tallied or stored anything while the first is open.
        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull(), anyOrNull())

        heldMidWrite.complete(Unit)
        listOf(resting, maximum).joinAll()
        verify(mockSettingsRepo).setStatedHeartRates(eq(190), anyOrNull(), anyOrNull())
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

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions("sub_30_bridge", activeScope)
        verify(mockPrescriptions, never()).prescribe(any(), any(), any())
        // The debrief is about the run just finished, so it survives the graduation.
        verify(mockSettingsRepo).setLatestCoachMessage("Stage complete.", activeScope)
    }

    @Test
    fun `a stage offering no workout of the run type is not evaluated at all`() = runTest {
        // Stage 3 is two hard days and no endurance run, so a Long prescription there has nothing to
        // be floored at (#176) — and its own first Workout is a Quality one, which is exactly the
        // slot a prescription reasoned about a Long Run must never land in.
        //
        // The coach is not asked either. The way to arrive here is an earlier evaluation graduating
        // the plan while this Long Run was still going, so the stage on the way out is one the Run
        // was never judged against — and its debrief would land on top of the graduation's own.
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = mockPrescriptions,
            aiCoachClient = mockCoach
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_25_peak"))
        )
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 660,
                nextWalkDurationSeconds = 60,
                nextRepeats = 4,
                nextTargetZone = 3,
                graduatedToNextStage = false,
                coachMessage = "Good session."
            )
        )

        repo.evaluateAndAdjustPlan("sub_25_peak", RunType.LONG)

        verify(mockCoach, never()).evaluateProgress(any())
        verify(mockPrescriptions, never()).prescribe(any(), any(), any())
        verify(mockSettingsRepo, never()).setLatestCoachMessage(any(), any())
    }

    @Test
    fun `an Easy Run is recorded in full but never evaluated`() = runTest {
        // The gate is the Run Type (#176): the Easy Run stays fixed at its continuous stretch, so
        // the coach is not even asked about it. Nothing here stops it being recorded or counting
        // toward the 30-day load — those happen before an evaluation is ever considered.
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

        repo.evaluateAndAdjustPlan("base_builder", RunType.EASY)

        verify(mockCoach, never()).evaluateProgress(any())
        verify(mockPrescriptions, never()).prescribe(any(), any(), any())
        verify(mockSettingsRepo, never()).setLatestCoachMessage(any(), any())
    }

    @Test
    fun `a Quality Run is never evaluated, so its slot is never written`() = runTest {
        // Six strides until it is changed by hand — an accepted gap (#176), and an AI does not
        // belong in the hard day at all.
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

        repo.evaluateAndAdjustPlan("base_builder", RunType.QUALITY)

        verify(mockCoach, never()).evaluateProgress(any())
        verify(mockPrescriptions, never()).prescribe(any(), any(), any())
        verify(mockSettingsRepo, never()).setLatestCoachMessage(any(), any())
    }

    @Test
    fun `a Run following no workout has no run type, so it is not evaluated`() = runTest {
        // An unplanned Run, or one where today's plan was skipped: there is no Workout to name a
        // kind, and a prescription with no kind is the hazard the slots exist to prevent (#175).
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = mockPrescriptions,
            aiCoachClient = mockCoach
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings()))

        repo.evaluateAndAdjustPlan("base_builder", runType = null)

        verify(mockCoach, never()).evaluateProgress(any())
        verify(mockPrescriptions, never()).prescribe(any(), any(), any())
        verify(mockSettingsRepo, never()).setLatestCoachMessage(any(), any())
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
            // Above the stage's own Long run (3 x 10 min), so the floor (#170) leaves it alone and
            // this stays a test of what one evaluation writes.
            AiCoachResponse(
                nextRunDurationSeconds = 660,
                nextWalkDurationSeconds = 60,
                nextRepeats = 4,
                nextTargetZone = 3,
                graduatedToNextStage = false,
                coachMessage = "Good session."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        val prescribed = argumentCaptor<CoachPrescription>()
        // Stored under the Run Type of the Run just finished (#176), which is the Workout the
        // evaluation was floored against. Nothing it writes can reach the other two kinds.
        verify(mockPrescriptions).prescribe(eq(RunType.LONG), prescribed.capture(), eq(activeScope))
        assertEquals(660, prescribed.firstValue.runDurationSeconds)
        assertEquals(60, prescribed.firstValue.walkDurationSeconds)
        assertEquals(4, prescribed.firstValue.totalRepeats)
        assertEquals(3, prescribed.firstValue.targetZone)
        verify(mockSettingsRepo).setLatestCoachMessage("Good session.", activeScope)
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(anyOrNull(), any())
        verify(mockSettingsRepo, never()).setCoachingEnabled(any())
        verify(mockSettingsRepo, never()).setTargetZone(any())
        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `the coach is asked about the same workout its answer is floored at`() = runTest {
        // The floor and the ceiling both measure the answer against this Workout, so the coach is
        // shown it before it answers (#246) — the same one resolved for the Run Type that finished,
        // not a second lookup that could drift from it.
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = mock(),
            aiCoachClient = mockCoach
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "base_builder"))
        )
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 660,
                nextWalkDurationSeconds = 60,
                nextRepeats = 4,
                graduatedToNextStage = false,
                coachMessage = "Good session."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        val asked = argumentCaptor<AiTrainingContext>()
        verify(mockCoach).evaluateProgress(asked.capture())
        // Stage 1's Long run, stated rather than resolved again here: an oracle built from the same
        // call production makes would pass on a wrong resolution.
        val shown = asked.firstValue.stageWorkout
        assertEquals(RunType.LONG, shown?.runType)
        assertEquals(600, shown?.runDurationSeconds)
        assertEquals(120, shown?.walkDurationSeconds)
        assertEquals(3, shown?.totalRepeats)
        // And it is the very Workout the floor is applied against, not a second lookup beside it.
        assertEquals(
            TrainingPlanProvider.resolveWorkoutOfType("5k_sub_25", "base_builder", RunType.LONG),
            shown
        )
    }

    @Test
    fun `a prescription is floored at the stage's workout of the same run type`() = runTest {
        // Stage 1's Long run is 3 x (10 min run + 2 min walk). A coach asking for less than that is
        // refused and given the Workout's own three numbers whole (#170), and the Workout it is held
        // to is the Long one because that is the kind of Run just finished (#176).
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
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            // Less work than the Long run, and *more* than the Easy or Quality Workout — so flooring
            // at either of those would let this through unchanged.
            AiCoachResponse(
                nextRunDurationSeconds = 300,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = 2,
                graduatedToNextStage = false,
                coachMessage = "Steady."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        val prescribed = argumentCaptor<CoachPrescription>()
        verify(mockPrescriptions).prescribe(eq(RunType.LONG), prescribed.capture(), any())
        assertEquals(600, prescribed.firstValue.runDurationSeconds)
        assertEquals(120, prescribed.firstValue.walkDurationSeconds)
        assertEquals(3, prescribed.firstValue.totalRepeats)
    }

    @Test
    fun `a fatigued runner is prescribed the stage's own workout, not the coach's harder one`() = runTest {
        // The hold the coach is asked for, kept on the write (#248). One hard Run today is enough to
        // put Fatigue above Fitness — the 7-day curve takes most of it and the 42-day curve barely
        // any — so the coach's 4 x 11 min, which clears the floor and would otherwise stand, is
        // replaced by stage 1's own Long run of 3 x (10 min + 2 min).
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
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        // Dated now, because the curves this reads are taken through today.
        val today = System.currentTimeMillis()
        whenever(mockDao.getScoredRunsFlow()).thenReturn(
            flowOf(listOf(ScoredRunProjection(startTime = today, effortScore = 200)))
        )
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(listOf(volumeRow(startTime = today, effortScore = 200)))
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 660,
                nextWalkDurationSeconds = 60,
                nextRepeats = 4,
                nextTargetZone = 2,
                graduatedToNextStage = false,
                coachMessage = "Not a week to be adding work to."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        // The state the coach was shown is the state the hold was read from — one reading, not two.
        val asked = argumentCaptor<AiTrainingContext>()
        verify(mockCoach).evaluateProgress(asked.capture())
        val shown = asked.firstValue.fitnessAndForm!!
        assertTrue(shown.fatigue > shown.fitness)

        val prescribed = argumentCaptor<CoachPrescription>()
        verify(mockPrescriptions).prescribe(eq(RunType.LONG), prescribed.capture(), any())
        assertEquals(600, prescribed.firstValue.runDurationSeconds)
        assertEquals(120, prescribed.firstValue.walkDurationSeconds)
        assertEquals(3, prescribed.firstValue.totalRepeats)
        // The debrief is untouched: the hold is about the intervals, not about what was said.
        verify(mockSettingsRepo).setLatestCoachMessage(eq("Not a week to be adding work to."), any())
    }

    @Test
    fun `an unreachable coach still holds a standing prescription at the workout`() = runTest {
        // Silence is not neutral (#248). Last week's 4 x 11 min stands for a fortnight and overrides
        // the stage's Long run every time one is started, so a runner who is fatigued *today* would
        // be handed exactly the harder intervals the hold exists to take away — because the network
        // was down. Fatigue is measured on this side, so the hold does not need a reply.
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = fatiguedRunnerEvaluating(mockPrescriptions, mockCoach)
        val standing = CoachPrescription(
            targetZone = 3,
            runDurationSeconds = 660,
            walkDurationSeconds = 60,
            totalRepeats = 4,
            prescribedAtEpochMillis = System.currentTimeMillis() - ONE_DAY_MILLIS
        )
        whenever(mockPrescriptions.prescriptionsFlow)
            .thenReturn(flowOf(CoachPrescriptions(mapOf(RunType.LONG to standing))))
        whenever(mockCoach.evaluateProgress(any())).thenReturn(null)

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        val held = argumentCaptor<CoachPrescription>()
        verify(mockPrescriptions).prescribe(
            eq(RunType.LONG),
            held.capture(),
            eq(CoachWriteScope("5k_sub_25", "base_builder"))
        )
        // Stage 1's own Long run: 3 x (10 min + 2 min).
        assertEquals(600, held.firstValue.runDurationSeconds)
        assertEquals(120, held.firstValue.walkDurationSeconds)
        assertEquals(3, held.firstValue.totalRepeats)
        // Kept from the prescription being pared back: the hold has no view on how hard the Run is,
        // and re-stamping would hand a fortnight-old prescription another fortnight to stand.
        assertEquals(3, held.firstValue.targetZone)
        assertEquals(standing.prescribedAtEpochMillis, held.firstValue.prescribedAtEpochMillis)
        // Nothing was learned about the runner, so nothing is said about them.
        verify(mockSettingsRepo, never()).setLatestCoachMessage(any(), any())
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(anyOrNull(), any())
    }

    @Test
    fun `an unreachable coach leaves a standing prescription alone for an absorbed runner`() = runTest {
        // No hold to apply, so the no-response path is what it always was: an evaluation that failed
        // writes nothing, and last week's prescription goes on standing.
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = fatiguedRunnerEvaluating(mockPrescriptions, mockCoach, absorbed = true)
        whenever(mockPrescriptions.prescriptionsFlow).thenReturn(
            flowOf(
                CoachPrescriptions(
                    mapOf(
                        RunType.LONG to CoachPrescription(
                            targetZone = 3,
                            runDurationSeconds = 660,
                            walkDurationSeconds = 60,
                            totalRepeats = 4,
                            prescribedAtEpochMillis = System.currentTimeMillis() - ONE_DAY_MILLIS
                        )
                    )
                )
            )
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(null)

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockPrescriptions, never()).prescribe(any(), any(), any())
    }

    @Test
    fun `an unreachable coach writes nothing for a fatigued runner with no prescription standing`() =
        runTest {
            // Nothing standing means the plan runs as written, which is the workout — already where
            // the hold would put them. Writing one would be the app inventing a prescription on a
            // day the coach said nothing.
            val mockPrescriptions: CoachPrescriptionRepository = mock()
            val mockCoach: AiCoachClient = mock()
            val repo = fatiguedRunnerEvaluating(mockPrescriptions, mockCoach)
            whenever(mockPrescriptions.prescriptionsFlow).thenReturn(flowOf(CoachPrescriptions.NONE))
            whenever(mockCoach.evaluateProgress(any())).thenReturn(null)

            repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

            verify(mockPrescriptions, never()).prescribe(any(), any(), any())
        }

    @Test
    fun `an unreachable coach does not rewrite a prescription that has already expired`() = runTest {
        // Past 14 days the workout is already what runs, so there is nothing to hold back — and a
        // write here would leave a fresh-looking record of a decision nobody made today.
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = fatiguedRunnerEvaluating(mockPrescriptions, mockCoach)
        whenever(mockPrescriptions.prescriptionsFlow).thenReturn(
            flowOf(
                CoachPrescriptions(
                    mapOf(
                        RunType.LONG to CoachPrescription(
                            targetZone = 3,
                            runDurationSeconds = 660,
                            walkDurationSeconds = 60,
                            totalRepeats = 4,
                            prescribedAtEpochMillis = System.currentTimeMillis() -
                                (COACH_PRESCRIPTION_MAX_AGE_DAYS + 1) * ONE_DAY_MILLIS
                        )
                    )
                )
            )
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(null)

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockPrescriptions, never()).prescribe(any(), any(), any())
    }

    /**
     * A repository set up to evaluate a stage 1 Long Run for a runner carrying today's hard session:
     * one 200-point Run dated now puts Fatigue above Fitness, because the 7-day curve takes most of
     * it and the 42-day curve barely any. [absorbed] dates the same Run far enough back that both
     * curves have had it, which is the runner the hold must not touch.
     */
    private suspend fun fatiguedRunnerEvaluating(
        prescriptions: CoachPrescriptionRepository,
        coach: AiCoachClient,
        absorbed: Boolean = false
    ): SessionRepository {
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = prescriptions,
            aiCoachClient = coach
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "base_builder"))
        )
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        val runDay = System.currentTimeMillis() - if (absorbed) 30 * ONE_DAY_MILLIS else 0L
        whenever(mockDao.getScoredRunsFlow()).thenReturn(
            flowOf(listOf(ScoredRunProjection(startTime = runDay, effortScore = 200)))
        )
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(listOf(volumeRow(startTime = runDay, effortScore = 200)))
        )
        return repo
    }

    @Test
    fun `an outdoor Run reaches the coach with its distance and its measured 5K`() = runTest {
        // The evidence a 5K-in-a-time Stage needs (#182): a 24-minute 5K inside a 35-minute Run.
        // Judged by durationSeconds it fails the stage it just passed.
        val metresPerDegreeLatitude = 111_132.0
        var latitude = 50.79
        var timestamp = 1_700_000_000_000L
        val points = mutableListOf(fiveKFix(latitude, timestamp))
        listOf(1.3 to 480, 5000.0 / 1440 to 1440, 1.3 to 180).forEach { (speedMps, seconds) ->
            repeat(seconds) {
                latitude += speedMps / metresPerDegreeLatitude
                timestamp += 1_000
                points += fiveKFix(latitude, timestamp)
            }
        }
        val outdoorRun = session(id = 7L, endTime = 1_000L)
            .copy(runMode = "outdoor", distanceKm = 6.4, durationSeconds = 2100)
        val mockTrackPointDao: TrackPointDao = mock()
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(listOf(outdoorRun))
        whenever(mockTrackPointDao.getTrackPointsForSessionOnce(7L)).thenReturn(points)
        val repositoryWithTrackPoints = SessionRepository(sessionDao = mockDao, trackPointDao = mockTrackPointDao)

        val recentRun = repositoryWithTrackPoints.getAiTrainingContext("sub_30_bridge").recentRuns.single()

        assertEquals("outdoor", recentRun.runMode)
        assertEquals(6.4, recentRun.distanceKm!!, 0.001)
        assertEquals(2100L, recentRun.durationSeconds)
        assertEquals(true, recentRun.fastest5kSeconds!! in 1435..1445)
    }

    @Test
    fun `a treadmill Run reaches the coach with no distance and no 5K to graduate on`() = runTest {
        // A treadmill Run has no GPS, so its stored distance is a zero rather than a measurement.
        // Sent as a zero it would read as a Run that covered no ground; sent as null it reads as
        // what it is, and the coach can say a 5K Stage cannot be settled from it at all.
        val treadmillRun = session(id = 8L, endTime = 1_000L).copy(runMode = "treadmill")
        val mockTrackPointDao: TrackPointDao = mock()
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(listOf(treadmillRun))
        whenever(mockTrackPointDao.getTrackPointsForSessionOnce(8L)).thenReturn(emptyList())
        val repositoryWithTrackPoints = SessionRepository(sessionDao = mockDao, trackPointDao = mockTrackPointDao)

        val recentRun = repositoryWithTrackPoints.getAiTrainingContext("sub_30_bridge").recentRuns.single()

        assertEquals("treadmill", recentRun.runMode)
        assertNull(recentRun.distanceKm)
        assertNull(recentRun.fastest5kSeconds)
    }

    @Test
    fun `a treadmill Run's stated distance reaches the coach like a measured one`() = runTest {
        // Where the winter was going missing (#231): with a distance the Run counts toward the
        // volume the next Long Run is judged against, instead of looking like a Run that never
        // happened.
        val treadmillRun = aTreadmillRun(id = 8, seconds = 1_500).copy(distanceKm = 5.0)
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(listOf(treadmillRun))

        val recentRun = repository.getAiTrainingContext("sub_30_bridge").recentRuns.single()

        assertEquals(5.0, recentRun.distanceKm!!, 0.001)
        // Still no 5K: a Best Effort is a stretch found inside a route, and there is no route.
        assertNull(recentRun.fastest5kSeconds)
    }

    @Test
    fun `a distance typed fast cannot join its own Run's evaluation`() = runTest {
        // The evaluation of the Run just finished is still in flight while the sheet asking for the
        // number is on screen, and it reads the sessions out of the database on its way to the
        // coach. Judged on a number typed since, a typo could graduate a Stage nothing can
        // un-graduate (#231, ADR 0008) — so the Run is judged as it stood when it was finalized.
        val asFinalized = aTreadmillRun(id = 8, seconds = 1_500)
        val olderRun = aTreadmillRun(id = 7, seconds = 1_800).copy(distanceKm = 6.0)
        whenever(mockDao.getLast3AiEligibleCompletedSessions())
            .thenReturn(listOf(asFinalized.copy(distanceKm = 5.0), olderRun))

        val recentRuns = repository
            .getAiTrainingContext("sub_30_bridge", asFinalized = asFinalized)
            .recentRuns

        assertNull(recentRuns.first().distanceKm)
        // Only that Run: a distance stated for an earlier one is history, and history is what the
        // coach is meant to be reading.
        assertEquals(6.0, recentRuns.last().distanceKm!!, 0.001)
    }

    @Test
    fun `the coach is told what the runner is carrying, on the same numbers the Progress screen shows`() = runTest {
        // A hard week — seven days at an Effort Score of 100 — and then a week off it, with one Run
        // in that week that wore no Strap. Read on the Sunday of the second week (#66).
        val hardWeek = (0..6).map { day ->
            ScoredRunProjection(startTime = DAY_MILLIS_2026_01_05 + day * ONE_DAY_MILLIS, effortScore = 100)
        }
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getScoredRunsFlow()).thenReturn(flowOf(hardWeek))
        whenever(mockDao.getRunVolumesFlow()).thenReturn(flowOf(
            hardWeek.map { volumeRow(startTime = it.startTime, effortScore = it.effortScore) } +
                volumeRow(startTime = DAY_MILLIS_2026_01_05 + 7 * ONE_DAY_MILLIS, effortScore = null)
        ))

        val state = repository.getAiTrainingContext(
            "sub_30_bridge",
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 1, 18)
        ).fitnessAndForm!!

        // Fitness remembers the hard week; Fatigue has already let most of it go, and Form — the
        // gap between the two, read as of yesterday — still says the runner is carrying it.
        assertEquals(13, state.fitness)
        assertEquals(23, state.fatigue)
        assertEquals(-14, state.form)
        assertEquals(FormVerdict.FATIGUED, state.verdict)
        // Two weeks of history, oldest first — and the strapless week is null rather than a zero,
        // which is the difference between a week nobody measured and a week nobody ran.
        assertEquals(listOf(700, null), state.weeklyEffortScores)
    }

    @Test
    fun `a Run that wore no Strap is not inside the numbers the coach is given`() = runTest {
        // No heart rate is no Effort Score, so the curves cannot see the Run that just finished —
        // and told nothing, a coach reads a hard strapless hour as an hour of rest (#66).
        val strapless = aTreadmillRun(id = 9, seconds = 3_600).copy(effortScore = null)
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(listOf(strapless))
        whenever(mockDao.getScoredRunsFlow()).thenReturn(
            flowOf(listOf(ScoredRunProjection(startTime = DAY_MILLIS_2026_01_05, effortScore = 100)))
        )
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(listOf(volumeRow(startTime = DAY_MILLIS_2026_01_05, effortScore = 100)))
        )

        val state = repository.getAiTrainingContext(
            "sub_30_bridge",
            asFinalized = strapless,
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 1, 18)
        ).fitnessAndForm!!

        assertFalse(state.todaysRunIsInTheNumbers)
        // The same Run with a Score is inside them, which is the case the flag has to tell apart.
        val scored = strapless.copy(effortScore = 140)
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(listOf(scored))
        assertTrue(
            repository.getAiTrainingContext(
                "sub_30_bridge",
                asFinalized = scored,
                zone = ZoneOffset.UTC,
                today = LocalDate.of(2026, 1, 18)
            ).fitnessAndForm!!.todaysRunIsInTheNumbers
        )
    }

    @Test
    fun `a Run stamped in the future is not claimed as inside the numbers either`() = runTest {
        // A clock corrected backwards mid-Run leaves the Run dated after today, and progressCurve
        // drops it rather than let a wrong clock bend today's figures. A Score is then not enough to
        // say the Run is in them.
        val scoredButAhead = aTreadmillRun(id = 11, seconds = 3_600).copy(
            effortScore = 140,
            startTime = DAY_MILLIS_2026_01_05 + 30 * ONE_DAY_MILLIS
        )
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(listOf(scoredButAhead))
        whenever(mockDao.getScoredRunsFlow()).thenReturn(
            flowOf(listOf(ScoredRunProjection(startTime = DAY_MILLIS_2026_01_05, effortScore = 100)))
        )
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(listOf(volumeRow(startTime = DAY_MILLIS_2026_01_05, effortScore = 100)))
        )

        val state = repository.getAiTrainingContext(
            "sub_30_bridge",
            asFinalized = scoredButAhead,
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 1, 18)
        ).fitnessAndForm!!

        assertFalse(state.todaysRunIsInTheNumbers)
    }

    @Test
    fun `a week the runner rested is sent as a zero, not as a week nothing measured`() = runTest {
        // Opposite news for a coach reading fatigue: a week off is the rest that earns a harder next
        // Run, while "nothing measured" is training the app could not see. The weeks themselves
        // cannot tell the two apart — both come back with no Effort Score (#66).
        val oneScoredRun = ScoredRunProjection(startTime = DAY_MILLIS_2026_01_05, effortScore = 100)
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getScoredRunsFlow()).thenReturn(flowOf(listOf(oneScoredRun)))
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(listOf(volumeRow(startTime = DAY_MILLIS_2026_01_05, effortScore = 100)))
        )

        val state = repository.getAiTrainingContext(
            "sub_30_bridge",
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 1, 18)
        ).fitnessAndForm!!

        // One week of training, then a week in which nothing at all was run.
        assertEquals(listOf(100, 0), state.weeklyEffortScores)
    }

    @Test
    fun `only the last four weeks of Effort Score reach the coach`() = runTest {
        // Six weeks of one scored Run each, so the block is a compact reading of the recent past
        // rather than a runner's whole training history pasted into a prompt.
        val weekly = (0..5).map { week ->
            ScoredRunProjection(
                startTime = DAY_MILLIS_2026_01_05 + week * 7 * ONE_DAY_MILLIS,
                effortScore = 10 * (week + 1)
            )
        }
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getScoredRunsFlow()).thenReturn(flowOf(weekly))
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(weekly.map { volumeRow(startTime = it.startTime, effortScore = it.effortScore) })
        )

        val state = repository.getAiTrainingContext(
            "sub_30_bridge",
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 2, 15)
        ).fitnessAndForm!!

        assertEquals(listOf(30, 40, 50, 60), state.weeklyEffortScores)
    }

    @Test
    fun `with no scored Run in history the coach is told nothing about fatigue`() = runTest {
        // A new phone, or a runner who has never run with a Strap: there is no curve to read, and
        // zeroes would read as six weeks of doing nothing.
        whenever(mockDao.getLast3AiEligibleCompletedSessions()).thenReturn(emptyList())
        whenever(mockDao.getScoredRunsFlow()).thenReturn(flowOf(emptyList()))
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(listOf(volumeRow(startTime = DAY_MILLIS_2026_01_05, effortScore = null)))
        )

        val context = repository.getAiTrainingContext(
            "sub_30_bridge",
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 1, 18)
        )

        assertNull(context.fitnessAndForm)
    }

    @Test
    fun `scoring a run banks the medals it won and reports them back`() = runTest {
        val run = session(id = 7, endTime = 1_000L).copy(runMode = "treadmill", durationSeconds = 3_600)
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockDao.getSessionById(7L)).thenReturn(run)
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao
        )

        val earned = repositoryWithRecords.scoreRecords(7L)

        assertEquals(listOf(RecordType.LONGEST_DURATION), earned.map { it.type })
        assertEquals(listOf(Medal.GOLD), earned.map { it.medal })
        // Only the record it actually contested is rewritten: a treadmill run must not be able to
        // clear the distance records off the book on its way past.
        verify(mockAchievementDao).deleteAchievementsOfTypes(listOf(RecordType.LONGEST_DURATION))
        verify(mockAchievementDao).insertAchievements(earned)
    }

    @Test
    fun `a run still being recorded is not scored at all`() = runTest {
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockDao.getSessionById(7L)).thenReturn(session(id = 7, endTime = 0L))
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao
        )

        assertEquals(emptyList<Achievement>(), repositoryWithRecords.scoreRecords(7L))

        verify(mockAchievementDao, never()).insertAchievements(any())
        verify(mockAchievementDao, never()).deleteAchievementsOfTypes(any())
    }

    // --- Seeding the record book from history, and repairing it after a delete (#50) ------------

    @Test
    fun `seeding scores the whole history and banks the book`() = runTest {
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(aTreadmillRun(id = 1, seconds = 600), aTreadmillRun(id = 2, seconds = 1_800), aTreadmillRun(id = 3, seconds = 1_200))
        )
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory()

        repositoryWithRecords.seedRecordsFromHistory()

        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(2L to Medal.GOLD, 3L to Medal.SILVER, 1L to Medal.BRONZE),
            book.firstValue.map { it.sessionId to it.medal },
        )
        // Marked only after the book is written, so an interrupted pass is owed again.
        verify(mockSettingsRepo).setHistoryRecordsSeeded()
    }

    @Test
    fun `history already seeded is not measured again`() = runTest {
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory(seeded = true)

        repositoryWithRecords.seedRecordsFromHistory()

        verify(mockDao, never()).getAllSessions()
        verify(mockAchievementDao, never()).insertAchievements(any())
    }

    @Test
    fun `a seeding pass that cannot write the book is owed again at the next launch`() = runTest {
        whenever(mockDao.getAllSessions()).thenReturn(listOf(aTreadmillRun(id = 1, seconds = 600)))
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory()
        whenever(mockAchievementDao.insertAchievements(any())).thenThrow(RuntimeException("disk full"))

        // Does not throw: the launch scope has no handler behind it.
        repositoryWithRecords.seedRecordsFromHistory()

        verify(mockSettingsRepo, never()).setHistoryRecordsSeeded()
    }

    @Test
    fun `a run deleted while history is being scored leaves the pass owed again`() = runTest {
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory()
        whenever(mockAchievementDao.getAchievementsForSessions(any())).thenReturn(emptyList())
        // The delete lands while the pass is measuring, which is the whole window this guards.
        whenever(mockDao.getAllSessions()).then {
            runBlocking { repositoryWithRecords.deleteSession(2L) }
            listOf(aTreadmillRun(id = 1, seconds = 600))
        }

        repositoryWithRecords.seedRecordsFromHistory()

        // The book is written, but not marked: the delete read history as unseeded so it lifted no
        // mark of its own, and marking here would stand over a mend that can still be cut short.
        verify(mockAchievementDao).insertAchievements(any())
        verify(mockSettingsRepo, never()).setHistoryRecordsSeeded()
    }

    @Test
    fun `a delete already under way when scoring starts leaves the pass owed again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockAchievementDao.getAchievementsForSessions(any())).thenReturn(emptyList())
        whenever(mockDao.getAllSessions()).thenReturn(listOf(aTreadmillRun(id = 1, seconds = 600)))
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = false)))
        var firstTransaction = true
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            settingsRepository = mockSettingsRepo,
            inTransaction = { block ->
                // Holds the delete open, and only the delete: the pass runs to completion inside it.
                if (firstTransaction) {
                    firstTransaction = false
                    gate.await()
                }
                block()
            }
        )

        val deleting = launch { repositoryWithRecords.deleteSession(2L) }
        runCurrent()

        repositoryWithRecords.seedRecordsFromHistory()

        // The delete was already counted when the pass took its baseline, so a count of starts
        // cannot see it. Only "one is running right now" can, and it is why the pass stays owed.
        verify(mockSettingsRepo, never()).setHistoryRecordsSeeded()

        gate.complete(Unit)
        deleting.join()
    }

    @Test
    fun `a delete cancelled mid-mend does not hold the mark down for good`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockAchievementDao.getAchievementsForSessions(any())).thenReturn(emptyList())
        whenever(mockDao.getAllSessions()).thenReturn(listOf(aTreadmillRun(id = 1, seconds = 600)))
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = false)))
        var firstTransaction = true
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            settingsRepository = mockSettingsRepo,
            inTransaction = { block ->
                if (firstTransaction) {
                    firstTransaction = false
                    gate.await()
                }
                block()
            }
        )

        // The runner leaves the history screen mid-delete and the view model's scope goes with them.
        val deleting = launch { repositoryWithRecords.deleteSession(2L) }
        runCurrent()
        deleting.cancelAndJoin()

        repositoryWithRecords.seedRecordsFromHistory()

        // The count came down on the way out, so the pass that follows can still call the book
        // whole. Left up, no delete and no seeding pass in this process could ever mark it again,
        // and the install would reseed at every launch for as long as it lived.
        verify(mockSettingsRepo).setHistoryRecordsSeeded()
    }

    @Test
    fun `seeding measures a run's stored track, breadcrumbs and all`() = runTest {
        // A run recorded as sparse breadcrumbs — no accuracy recorded, which is what history from
        // before the app kept one looks like. It still covered ground, so it contests the distances.
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(session(id = 1, endTime = 1_000L).copy(runMode = "outdoor", distanceKm = 1.2, durationSeconds = 300))
        )
        val mockTrackPointDao: TrackPointDao = mock()
        whenever(mockTrackPointDao.getTrackPointsForSessionOnce(1L)).thenReturn(
            (0..300 step 10).map { second ->
                // 4 m/s north from the equator: 1.2 km in five minutes, fixes ten seconds apart.
                breadcrumb(sessionId = 1, latitude = second * 4.0 / 111_320.0, timestampMillis = second * 1_000L)
            }
        )
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory(
            trackPointDao = mockTrackPointDao
        )

        repositoryWithRecords.seedRecordsFromHistory()

        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            setOf(RecordType.FASTEST_1K, RecordType.LONGEST_DISTANCE, RecordType.LONGEST_DURATION),
            book.firstValue.map { it.type }.toSet(),
        )
        assertEquals(250.0, book.firstValue.single { it.type == RecordType.FASTEST_1K }.value, 15.0)
    }

    @Test
    fun `a run scored while history was being measured keeps its place in the book`() = runTest {
        // Run 9 was still being recorded when the pass read history, so it measures to nothing —
        // then finished and scored itself. Its rows would be wiped by the rewrite if the book did
        // not carry over what it never measured an effort for.
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(aTreadmillRun(id = 1, seconds = 600), session(id = 9, endTime = 0L))
        )
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(
            listOf(Achievement(sessionId = 9, type = RecordType.LONGEST_DURATION, medal = Medal.GOLD, value = 3_600.0))
        )

        repositoryWithRecords.seedRecordsFromHistory()

        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(9L to Medal.GOLD, 1L to Medal.SILVER),
            book.firstValue.map { it.sessionId to it.medal },
        )
    }

    @Test
    fun `deleting a medal holder promotes the next best effort`() = runTest {
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.LONGEST_DURATION, medal = Medal.GOLD, value = 1_800.0))
        )
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        // What history is once the deleted run is gone.
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(aTreadmillRun(id = 1, seconds = 600), aTreadmillRun(id = 3, seconds = 1_200))
        )
        val order = mutableListOf<String>()
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            refreshHistoryBackup = { order += "backup" }
        )
        whenever(mockAchievementDao.insertAchievements(any())).then { order += "rebuild"; Unit }

        repositoryWithRecords.deleteSession(2L)

        val book = argumentCaptor<List<Achievement>>()
        verify(mockDao).deleteSessionById(2L)
        // Only the record the deleted run held is rebuilt, and the two runs left move up a place.
        verify(mockAchievementDao).deleteAchievementsOfTypes(listOf(RecordType.LONGEST_DURATION))
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(3L to Medal.GOLD, 1L to Medal.SILVER),
            book.firstValue.map { it.sessionId to it.medal },
        )
        // The deletion is made durable *before* the minutes-long rebuild, so a process killed inside
        // it cannot leave a Downloads snapshot a restore would bring the deleted run back from. The
        // second refresh carries the mended book out too.
        assertEquals(listOf("backup", "rebuild", "backup"), order)
    }

    @Test
    fun `the medals a deleted run held are read in the transaction that removes it`() = runTest {
        val order = mutableListOf<String>()
        val mockAchievementDao: AchievementDao = mock()
        mockAchievementDao.stub {
            onBlocking { getAchievementsForSessions(any()) }
                .doSuspendableAnswer { order += "read"; emptyList() }
        }
        mockDao.stub {
            onBlocking { deleteSessionById(any()) }.doSuspendableAnswer { order += "delete" }
        }
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            inTransaction = { block ->
                order += "begin"
                block()
                order += "commit"
            }
        )

        repositoryWithRecords.deleteSession(2L)

        // Both inside one transaction: a medal awarded by the seeding pass in between would be
        // cascaded away by the delete without ever showing up as a record to repair.
        assertEquals(listOf("begin", "read", "delete", "commit"), order)
    }

    @Test
    fun `deleting a run that won nothing leaves the book alone`() = runTest {
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L, 5L))).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao
        )

        repositoryWithRecords.deleteSessions(listOf(2L, 5L))

        verify(mockDao).deleteSessionsByIds(listOf(2L, 5L))
        // Not even measured: proving nothing changed must not cost a walk of the whole history.
        verify(mockDao, never()).getAllSessions()
        verify(mockAchievementDao, never()).deleteAchievementsOfTypes(any())
        verify(mockAchievementDao, never()).insertAchievements(any())
    }

    @Test
    fun `the debt is written down before the run is deleted`() = runTest {
        val order = mutableListOf<String>()
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(emptyList())
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = true)))
        mockSettingsRepo.stub {
            onBlocking { clearHistoryRecordsSeeded() }.doSuspendableAnswer { order += "owe" }
            onBlocking { setHistoryRecordsSeeded() }.doSuspendableAnswer { order += "paid" }
        }
        mockDao.stub {
            onBlocking { deleteSessionById(any()) }.doSuspendableAnswer { order += "delete" }
        }
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            settingsRepository = mockSettingsRepo,
            refreshHistoryBackup = { order += "backup" }
        )

        repositoryWithRecords.deleteSession(2L)

        // The debt goes down first, because everything after the delete can be cut short — the
        // process reclaimed, the screen left mid-backup — and a debt recorded later is one those
        // endings skip. Lifted even here, where the run turns out to have held nothing: what it held
        // is not known until it is already gone.
        assertEquals(listOf("owe", "delete", "backup", "paid"), order)
    }

    @Test
    fun `a delete overtaken by another does not call the book whole`() = runTest {
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(any())).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.LONGEST_DURATION, medal = Medal.GOLD, value = 1_800.0))
        )
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = true)))
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            settingsRepository = mockSettingsRepo
        )
        // A second delete begins while the first is still measuring, which the history screen
        // allows, and does not get as far as mending anything.
        whenever(mockDao.deleteSessionById(5L)).thenThrow(RuntimeException("the second delete fails"))
        var overtaken = false
        whenever(mockDao.getAllSessions()).then {
            if (!overtaken) {
                overtaken = true
                runCatching { runBlocking { repositoryWithRecords.deleteSession(5L) } }
            }
            listOf(aTreadmillRun(id = 1, seconds = 600))
        }

        repositoryWithRecords.deleteSession(2L)

        verify(mockSettingsRepo, atLeastOnce()).clearHistoryRecordsSeeded()
        verify(mockSettingsRepo, never()).setHistoryRecordsSeeded()
    }

    @Test
    fun `a repair that fails leaves history owing a full reseed`() = runTest {
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.LONGEST_DURATION, medal = Medal.GOLD, value = 1_800.0))
        )
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockDao.getAllSessions()).thenThrow(RuntimeException("history unreadable"))
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = true)))
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            settingsRepository = mockSettingsRepo
        )

        repositoryWithRecords.deleteSession(2L)

        // The medals went with the run and the mend never landed, so the record stands short — and
        // only the top three are stored, so nothing but a full reseed can find the effort that
        // should move up. The mark stays lifted and the next launch pays it.
        verify(mockSettingsRepo).clearHistoryRecordsSeeded()
        verify(mockSettingsRepo, never()).setHistoryRecordsSeeded()
    }

    @Test
    fun `a repair that lands hands the seeded mark back`() = runTest {
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.LONGEST_DURATION, medal = Medal.GOLD, value = 1_800.0))
        )
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockDao.getAllSessions()).thenReturn(listOf(aTreadmillRun(id = 1, seconds = 600)))
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = true)))
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            settingsRepository = mockSettingsRepo
        )

        repositoryWithRecords.deleteSession(2L)

        verify(mockSettingsRepo).clearHistoryRecordsSeeded()
        verify(mockSettingsRepo).setHistoryRecordsSeeded()
    }

    @Test
    fun `a join that fails part-way leaves no phantom delete behind`() = runTest {
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.LONGEST_DURATION, medal = Medal.GOLD, value = 1_800.0))
        )
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockDao.getAllSessions()).thenReturn(listOf(aTreadmillRun(id = 1, seconds = 600)))
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = true)))
        // The first delete gets as far as joining the count and no further: lowering the mark throws.
        whenever(mockSettingsRepo.clearHistoryRecordsSeeded())
            .thenThrow(IllegalStateException("the settings store is unwell"))
            .thenAnswer { }
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            settingsRepository = mockSettingsRepo
        )

        val brokenJoin = runCatching { repositoryWithRecords.deleteSession(2L) }
        assertEquals(true, brokenJoin.isFailure)

        // Nothing of that delete is left standing in the count, so this one is still the only one
        // there is and can hand the mark back. Left behind, the phantom would have held the mark
        // down for the life of the process — a full reseed at every launch.
        repositoryWithRecords.deleteSession(2L)

        verify(mockSettingsRepo).setHistoryRecordsSeeded()
    }

    @Test
    fun `a delete on an install still owing its first seeding does not mark it done`() = runTest {
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.LONGEST_DURATION, medal = Medal.GOLD, value = 1_800.0))
        )
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockDao.getAllSessions()).thenReturn(listOf(aTreadmillRun(id = 1, seconds = 600)))
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = false)))
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            settingsRepository = mockSettingsRepo
        )

        repositoryWithRecords.deleteSession(2L)

        // A two-record repair is not the seeding pass, and must not cancel a debt it never paid.
        verify(mockSettingsRepo, never()).setHistoryRecordsSeeded()
        verify(mockSettingsRepo, never()).clearHistoryRecordsSeeded()
    }

    /** A repository whose history has never been scored, and the book it writes to. */
    private suspend fun repositoryWithUnseededHistory(
        seeded: Boolean = false,
        trackPointDao: TrackPointDao? = null
    ): Pair<SessionRepository, AchievementDao> {
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = seeded)))
        return SessionRepository(
            sessionDao = mockDao,
            trackPointDao = trackPointDao,
            achievementDao = mockAchievementDao,
            settingsRepository = mockSettingsRepo
        ) to mockAchievementDao
    }

    /** A historical fix with no accuracy recorded — always kept, see [acceptedForMap]. */
    private fun breadcrumb(sessionId: Long, latitude: Double, timestampMillis: Long) = TrackPoint(
        sessionId = sessionId,
        latitude = latitude,
        longitude = 0.0,
        horizontalAccuracyMeters = null,
        timestampMillis = timestampMillis,
        source = TrackPointSource.BACKFILL
    )

    /** A finished run with a duration and nothing measured against ground — see [bestEffortsOf]. */
    private fun aTreadmillRun(id: Long, seconds: Long) =
        session(id = id, endTime = 1_000L).copy(runMode = "treadmill", durationSeconds = seconds)

    // --- Scoring the history recorded before the Effort Score shipped (#62) ---
    //
    // At Max HR 181 with no resting heart rate stated, the Zone 3 floor is 127 and the Zone 1 floor
    // is 91 — so a minute at 130 is 60 seconds weighted 3, which is a Score of 3.

    private fun repositoryScoring(samples: SampleDao) = SessionRepository(
        sessionDao = mockDao,
        sampleDao = samples,
        settingsRepository = mockSettingsRepo
    )

    @Test
    fun `the backfill scores every finished run that has no score yet`() = runTest {
        val mockSampleDao: SampleDao = mock()
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(historyMaxHr = 181)))
        whenever(mockDao.getSessionIdsMissingEffort()).thenReturn(listOf(7L, 8L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(List(60) { 130 })
        whenever(mockSampleDao.getRawBpmsForSession(8L)).thenReturn(List(120) { 150 })

        repositoryScoring(mockSampleDao).backfillEffortScores()

        verify(mockDao).setEffortScore(7L, 3)
        verify(mockDao).setEffortScore(8L, 8)
    }

    @Test
    fun `a run that recorded no beats is left unscored rather than stored as a zero`() = runTest {
        // Zero is a real answer here — an hour spent below Zone 1 — so a Run with nothing to
        // measure must not be written down as one.
        val mockSampleDao: SampleDao = mock()
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(historyMaxHr = 181)))
        whenever(mockDao.getSessionIdsMissingEffort()).thenReturn(listOf(9L))
        whenever(mockSampleDao.getRawBpmsForSession(9L)).thenReturn(emptyList())

        repositoryScoring(mockSampleDao).backfillEffortScores()

        verify(mockDao, never()).setEffortScore(any(), any())
    }

    @Test
    fun `a run spent entirely below zone 1 scores zero, which is a measurement`() = runTest {
        val mockSampleDao: SampleDao = mock()
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(historyMaxHr = 181)))
        whenever(mockDao.getSessionIdsMissingEffort()).thenReturn(listOf(9L))
        whenever(mockSampleDao.getRawBpmsForSession(9L)).thenReturn(List(600) { 70 })

        repositoryScoring(mockSampleDao).backfillEffortScores()

        verify(mockDao).setEffortScore(9L, 0)
    }

    @Test
    fun `running the backfill again once history is scored writes nothing`() = runTest {
        val mockSampleDao: SampleDao = mock()
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(historyMaxHr = 181)))
        whenever(mockDao.getSessionIdsMissingEffort()).thenReturn(emptyList())

        repositoryScoring(mockSampleDao).backfillEffortScores()

        verify(mockDao, never()).setEffortScore(any(), any())
        verify(mockSampleDao, never()).getRawBpmsForSession(any())
    }

    @Test
    fun `a run that cannot be scored costs the rest of the pass nothing`() = runTest {
        // It stays unscored, so the next launch's work list picks it up again — which is the same
        // mechanism that makes a pass killed half way through resumable.
        val mockSampleDao: SampleDao = mock()
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(historyMaxHr = 181)))
        whenever(mockDao.getSessionIdsMissingEffort()).thenReturn(listOf(7L, 8L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenThrow(IllegalStateException("unreadable"))
        whenever(mockSampleDao.getRawBpmsForSession(8L)).thenReturn(List(60) { 130 })

        repositoryScoring(mockSampleDao).backfillEffortScores()

        verify(mockDao, never()).setEffortScore(eq(7L), any())
        verify(mockDao).setEffortScore(8L, 3)
    }

    @Test
    fun `the backfill scores against a heart-rate profile it is handed`() = runTest {
        // The reuse the Max HR change needs: the same pass, against a maximum nobody has stored.
        val mockSampleDao: SampleDao = mock()
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(UserSettings(historyMaxHr = 181)))
        whenever(mockDao.getSessionIdsMissingEffort()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(List(60) { 130 })

        // At Max HR 140 the same minute at 130 is Zone 5 rather than Zone 3.
        repositoryScoring(mockSampleDao).backfillEffortScores(HrProfile(maxHr = 140))

        verify(mockDao).setEffortScore(7L, 5)
    }

    @Test
    fun `with nothing handed in, history is scored against the maximum it is banded on`() = runTest {
        // Not the maximum in force: after a future-only correction those differ, and scoring
        // against the stored one would put these Runs on a profile their zone times are not on.
        val mockSampleDao: SampleDao = mock()
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 145, historyMaxHr = 181)))
        whenever(mockDao.getSessionIdsMissingEffort()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(List(60) { 130 })

        repositoryScoring(mockSampleDao).backfillEffortScores()

        verify(mockDao).setEffortScore(7L, 3)
    }

    @Test
    fun `with no samples wired there is nothing to score from`() = runTest {
        repository.backfillEffortScores()

        verify(mockDao, never()).getSessionIdsMissingEffort()
    }

    private fun fiveKFix(latitude: Double, timestampMillis: Long) = TrackPoint(
        sessionId = 7L,
        latitude = latitude,
        longitude = 0.22,
        horizontalAccuracyMeters = 5f,
        timestampMillis = timestampMillis,
        source = TrackPointSource.GPS
    )

    private fun volumeRow(startTime: Long, effortScore: Int?) = RunVolumeProjection(
        startTime = startTime,
        distanceKm = 5.0,
        durationSeconds = 1_800,
        movingTimeSeconds = null,
        effortScore = effortScore
    )

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
