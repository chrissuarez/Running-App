package com.example.runningapp.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.example.runningapp.COACH_PRESCRIPTION_MAX_AGE_DAYS
import com.example.runningapp.BestEffortRequirement
import com.example.runningapp.CoachPrescription
import com.example.runningapp.CoachPrescriptions
import com.example.runningapp.CoachPrescriptionRepository
import com.example.runningapp.CoachWriteScope
import com.example.runningapp.DebriefAuthor
import com.example.runningapp.PreferencesKeys
import com.example.runningapp.coachWriteAllowed
import com.example.runningapp.HrProfile
import com.example.runningapp.MAX_MAX_HR
import com.example.runningapp.RunType
import com.example.runningapp.SettingsRepository
import com.example.runningapp.StatedHeartRates
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.tests
import com.example.runningapp.UserSettings
import com.example.runningapp.userSettingsOf
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.training.FormVerdict
import com.example.runningapp.training.GoalMetric
import com.example.runningapp.training.GoalPeriod
import com.example.runningapp.training.HistoryBestEffort
import com.example.runningapp.training.PlanCompletion
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
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
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Monday 5 January 2026, midday UTC — the first day of the training weeks these tests read. */
private const val DAY_MILLIS_2026_01_05 = 1_767_614_400_000L
private const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
private const val ONE_HOUR_MILLIS = 60L * 60 * 1000

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
        // Every Run a delete is given may have fed the coach unless a test says otherwise, which is
        // the ordinary case: sharing is on by default (#156).
        mockDao.stub {
            onBlocking { getAiEligibleIdsIn(any()) }
                .thenAnswer { it.getArgument<List<Long>>(0) }
        }
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
        weeklyEfforts = listOf(AiWeeklyEffort(700, false), AiWeeklyEffort(200, false)),
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

    // --- Marking a Run as a Walk (#275) --------------------------------------------------------

    @Test
    fun `marking a Run a Walk writes the mark and refreshes the backup`() = runTest {
        whenever(mockDao.getSessionById(42L)).thenReturn(aTreadmillRun(id = 42, seconds = 1_800))
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(42L))).thenReturn(emptyList())
        var refreshCount = 0
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            refreshHistoryBackup = { refreshCount++ },
        )

        repositoryWithRecords.markAsWalk(42L, isWalk = true)

        verify(mockDao).setIsWalk(42L, true)
        // The snapshot finalizeRun took went out before the mark existed, and a Run restored from
        // it would come back a Run.
        assertTrue(refreshCount >= 1)
    }

    @Test
    fun `marking a record-holding Run a Walk hands the medal to the next best Run`() = runTest {
        // The demotion path #282 built, reached from the one edit that can take every medal at once:
        // a Walk contests nothing, so the Run that should move up exists nowhere but in history.
        val medalHolder = aTreadmillRun(id = 2, seconds = 3_600)
        whenever(mockDao.getSessionById(2L)).thenReturn(medalHolder)
        val mockAchievementDao: AchievementDao = mock()
        val gold = Achievement(
            sessionId = 2,
            type = RecordType.LONGEST_DURATION,
            medal = Medal.GOLD,
            value = 3_600.0,
        )
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(listOf(gold))
        // The book still holds the gold while the rebuild measures — the rewrite is what takes it.
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(listOf(gold))
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_200),
                medalHolder.copy(isWalk = true),
            )
        )
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
        )

        repositoryWithRecords.markAsWalk(2L, isWalk = true)

        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(1L to 1_200.0),
            book.firstValue.map { it.sessionId to it.value },
        )
    }

    @Test
    fun `a Run that is a Walk no longer contests, so unmarking it puts it back in the running`() = runTest {
        // The opposite direction, and the improvement path rather than the mend: a Run that is a Run
        // again can only win things back, so it is re-scored rather than rebuilt from history.
        val run = aTreadmillRun(id = 42, seconds = 1_800)
        // A Walk when it is asked, and a Run by the time the scoring reads it back — which is the
        // order the write actually lands in.
        whenever(mockDao.getSessionById(42L)).thenReturn(run.copy(isWalk = true), run)
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
        )

        repositoryWithRecords.markAsWalk(42L, isWalk = false)

        verify(mockDao).setIsWalk(42L, false)
        // The scoring mark is lifted before the change and handed back once the book has taken it,
        // so nothing that ends short leaves a claim the book never went back for.
        verify(mockDao).clearRecordsScored(42L)
        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(42L to 1_800.0),
            book.firstValue.map { it.sessionId to it.value },
        )
        // Nothing is rebuilt from history: a Run that is a Run again can only win things back.
        verify(mockAchievementDao, never()).getAchievementsForSessions(any())
    }

    @Test
    fun `marking a Run the way it is already marked costs nothing`() = runTest {
        whenever(mockDao.getSessionById(42L))
            .thenReturn(aTreadmillRun(id = 42, seconds = 1_800).copy(isWalk = true))
        val mockAchievementDao: AchievementDao = mock()
        var refreshCount = 0
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            refreshHistoryBackup = { refreshCount++ },
        )

        repositoryWithRecords.markAsWalk(42L, isWalk = true)

        verify(mockDao, never()).setIsWalk(any(), any())
        verifyNoInteractions(mockAchievementDao)
        assertEquals(0, refreshCount)
    }

    @Test
    fun `marking a Run a Walk waits for it to be finalized`() = runTest {
        // The sheet asking the question is on screen from the moment STOP is pressed, while
        // finalizeRun is still writing the row whole — a mark landing first would be overwritten.
        val unfinalized = aTreadmillRun(id = 42, seconds = 0).copy(endTime = 0L)
        whenever(mockDao.getSessionById(42L))
            .thenReturn(unfinalized, aTreadmillRun(id = 42, seconds = 1_800))
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(42L))).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
        )

        repositoryWithRecords.markAsWalk(42L, isWalk = true, finalizeWaitStepMillis = 1L)

        verify(mockDao, times(1)).setIsWalk(42L, true)
    }

    @Test
    fun `a Walk keeps the Effort Score it measured`() = runTest {
        // The Score is what the heart did, and marking the Run does not touch it: what changes is
        // which curve reads how much of it.
        whenever(mockDao.getSessionById(42L))
            .thenReturn(aTreadmillRun(id = 42, seconds = 1_800).copy(effortScore = 55))
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(42L))).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
        )

        repositoryWithRecords.markAsWalk(42L, isWalk = true)

        verify(mockDao, never()).setEffortScore(any(), any())
    }

    @Test
    fun `a Walk carries a quarter of its Score into the Fatigue the coach is told`() = runTest {
        // The same read the Progress screen makes, through the repository (#275). Seven days of the
        // same Score, walked rather than run: Fitness is untouched and Fatigue is a quarter of it.
        val week = (0..6).map { day -> DAY_MILLIS_2026_01_05 + day * ONE_DAY_MILLIS }
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(week.map { volumeRow(startTime = it, effortScore = 100) })
        )

        whenever(mockDao.getScoredRunsFlow()).thenReturn(
            flowOf(week.map { ScoredRunProjection(startTime = it, effortScore = 100) })
        )
        val ran = repository.getAiTrainingContext(
            "sub_30_bridge",
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 1, 11),
        ).fitnessAndForm!!

        whenever(mockDao.getScoredRunsFlow()).thenReturn(
            flowOf(week.map { ScoredRunProjection(startTime = it, effortScore = 100, isWalk = true) })
        )
        val walked = repository.getAiTrainingContext(
            "sub_30_bridge",
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 1, 11),
        ).fitnessAndForm!!

        assertEquals(ran.fitness, walked.fitness)
        assertTrue("fatigue is far lower", walked.fatigue < ran.fatigue)
        // And the whole point of the ticket: the same week run leaves the runner fatigued and the
        // hold fires, where walked it does not — which is what the coach's over-caution was.
        assertEquals(FormVerdict.FATIGUED, ran.verdict)
        assertEquals(FormVerdict.NEUTRAL, walked.verdict)
    }

    @Test
    fun `a Walk is shown to the coach but is not among the Runs that could answer the Stage`() = runTest {
        // The two lists the context keeps apart: everything shown was reasoned from, and only a
        // structured Run/Walk may graduate. A Walk beside a real Run must not take the Stage's
        // evidence away with it (#275) — and an unplanned Open Run beside them is no evidence
        // either, which is what the prompt says and what this list has to enforce.
        val walked = aTreadmillRun(id = 9, seconds = 1_800).copy(isWalk = true, startTime = 2_000L)
        val ran = aTreadmillRun(id = 10, seconds = 1_800)
            .copy(isRunWalkMode = true, startTime = 3_000L)
        val openRun = aTreadmillRun(id = 11, seconds = 1_800).copy(startTime = 4_000L)
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any()))
            .thenReturn(listOf(walked, ran, openRun))

        val context = repository.getAiTrainingContext("sub_30_bridge")

        assertEquals(setOf(9L, 10L, 11L), context.sourceRunIds)
        // Keyed by the timestamp the coach is shown, so a reply naming one comes back to it (#287).
        assertEquals(mapOf(3_000L to 10L), context.requirementEvidenceRunIdsByTimestamp)
    }

    @Test
    fun `two Runs that started at the same instant are named by neither of their timestamps`() = runTest {
        // A timestamp is only a name while one Run answers to it. Two sharing one is a name that
        // resolves to a coin toss, so it resolves to nothing at all — a graduation cannot be taken
        // back, and this is the direction that doubt is settled in (#287).
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 10, seconds = 1_800).copy(isRunWalkMode = true, startTime = 7_000L),
                aTreadmillRun(id = 11, seconds = 1_800).copy(isRunWalkMode = true, startTime = 7_000L),
                aTreadmillRun(id = 12, seconds = 1_800).copy(isRunWalkMode = true, startTime = 8_000L),
            )
        )

        val context = repository.getAiTrainingContext("sub_30_bridge")

        assertEquals(setOf(10L, 11L, 12L), context.sourceRunIds)
        assertEquals(mapOf(8_000L to 12L), context.requirementEvidenceRunIdsByTimestamp)
    }

    @Test
    fun `a Walk sharing a start with a Run does not hand the coach the Run under the Walk's number`() = runTest {
        // The collision that would put the hole straight back (#287): drop the ambiguous timestamps
        // only from among the Runs that can answer the Stage, and a Walk starting at the same
        // instant as a structured Run is the one discarded — leaving the Run answering to a number
        // the coach read off the Walk. So the ambiguity is settled across every Run shown, before
        // any of them is set aside as unable to graduate anything.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 10, seconds = 1_800).copy(isRunWalkMode = true, startTime = 5_000L),
                aTreadmillRun(id = 11, seconds = 7_200).copy(isWalk = true, startTime = 5_000L),
                aTreadmillRun(id = 12, seconds = 1_800).copy(isRunWalkMode = true, startTime = 6_000L),
            )
        )

        val context = repository.getAiTrainingContext("sub_30_bridge")

        assertEquals(mapOf(6_000L to 12L), context.requirementEvidenceRunIdsByTimestamp)
    }

    @Test
    fun `naming nothing, and naming what the Stage cannot rest on, both come back as no evidence`() {
        // The helper the graduation is decided from, asked each way a name can fail. All of them
        // are one answer — null, the refusal — and an empty list is the one the schema itself
        // invites, since it tells the coach to leave the field empty when it is not graduating.
        val context = AiTrainingContext(
            currentStageTitle = "Base Builder",
            graduationRequirement = "Complete 4 weeks of consistent Zone 2 training.",
            recentRuns = emptyList(),
            requirementEvidenceRunIdsByTimestamp = mapOf(1_000L to 10L, 2_000L to 11L)
        )
        val aGraduation = AiCoachResponse(
            nextRunDurationSeconds = 360,
            nextWalkDurationSeconds = 60,
            nextRepeats = 5,
            graduatedToNextStage = true,
            coachMessage = "Stage complete."
        )
        fun naming(timestamps: List<Long>?) =
            context.evidenceRunIdsNamedBy(aGraduation.copy(graduationEvidenceRunTimestamps = timestamps))

        assertNull(naming(null))
        assertNull(naming(emptyList()))
        // A Run nobody was shown, and a Run shown but unable to answer the Stage, are the same "no".
        assertNull(naming(listOf(9_999L)))
        // And one bad name among good ones takes the whole graduation with it.
        assertNull(naming(listOf(1_000L, 9_999L)))
        assertEquals(setOf(10L, 11L), naming(listOf(1_000L, 2_000L)))
        // The same Run named twice is still that one Run, not two runs' worth of evidence.
        assertEquals(setOf(10L), naming(listOf(1_000L, 1_000L)))
    }

    @Test
    fun `a Walk reaches the coach named as a Walk and never as the workout it followed`() = runTest {
        // Shown rather than hidden — a week of walking is not a week of rest — but it did not
        // complete the Workout, whatever structure it happened to follow (#275).
        val walked = aTreadmillRun(id = 9, seconds = 1_800)
            .copy(isWalk = true, isRunWalkMode = true)
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(walked))

        val recentRuns = repository.getAiTrainingContext("sub_30_bridge").recentRuns

        assertEquals(listOf("Walk"), recentRuns.map { it.sessionType })
    }

    @Test
    fun `a Walk is worth no measured 5K, whatever its track covered`() = runTest {
        // CONTEXT.md says a Walk holds no Best Effort and the record book keeps to it, so the one
        // place a Stage requirement is read from must not be where the app disagrees with itself
        // (#290). A brisk 5 km walk is still a walk.
        val trackDao: TrackPointDao = mock()
        whenever(trackDao.getTrackPointsForSessionOnce(9L)).thenReturn(fiveKilometresIn(500))
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            trackPointDao = trackDao,
        )
        val ran = session(id = 9, endTime = 1_000L).copy(isRunWalkMode = true)
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(ran))

        assertEquals(
            listOf(500L),
            repo.getAiTrainingContext("sub_30_bridge").recentRuns.map { it.fastest5kSeconds }
        )

        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(ran.copy(isWalk = true)))

        assertEquals(
            listOf(null),
            repo.getAiTrainingContext("sub_30_bridge").recentRuns.map { it.fastest5kSeconds }
        )
    }

    /** A straight 5 km covered in [seconds], as 51 backfilled fixes 100 m apart. */
    private fun fiveKilometresIn(seconds: Long): List<TrackPoint> = (0..50).map { step ->
        TrackPoint(
            sessionId = 9,
            latitude = 0.0,
            // 100 m of longitude at the equator, so 50 legs make 5 km.
            longitude = step * (100.0 / 111_319.49),
            horizontalAccuracyMeters = null,
            timestampMillis = step * seconds * 1_000 / 50,
            source = "BACKFILL",
        )
    }

    // --- Stating a Best Effort a treadmill console showed (#282, ADR 0015) ---------------------

    @Test
    fun `a stated best effort is written and scored against the record book`() = runTest {
        val run = aTreadmillRun(id = 42, seconds = 1_800).copy(distanceKm = 6.0)
        whenever(mockDao.getSessionById(42L)).thenReturn(run)
        val claim = StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_5K, seconds = 1_440)
        val statedDao: StatedBestEffortDao = mock()
        // Nothing stated when the claim is read, and the claim standing every time afterwards: the
        // scoring must rank the Run on what it has just been told, not on what it held before.
        whenever(statedDao.getForSession(42L)).thenReturn(emptyList(), listOf(claim))
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        var refreshCount = 0
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
            refreshHistoryBackup = { refreshCount++ },
        )

        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 1_440)

        verify(statedDao).state(claim)
        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(
                RecordType.FASTEST_5K to 1_440.0,
                RecordType.LONGEST_DISTANCE to 6_000.0,
                RecordType.LONGEST_DURATION to 1_800.0,
            ),
            book.firstValue.map { it.type to it.value },
        )
        // As with a stated distance: the snapshot finalizeRun took went out before the claim
        // existed, and a Run restored from it would come back without it.
        assertEquals(1, refreshCount)
    }

    @Test
    fun `the same Run can be told two different record distances`() = runTest {
        val run = aTreadmillRun(id = 42, seconds = 1_800).copy(distanceKm = 6.0)
        whenever(mockDao.getSessionById(42L)).thenReturn(run)
        val fiveK = StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_5K, seconds = 1_440)
        val statedDao: StatedBestEffortDao = mock()
        // The 5 km already stated when the 1 km is claimed: a console shows lap times, and neither
        // claim derives the other.
        whenever(statedDao.getForSession(42L)).thenReturn(listOf(fiveK))
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_1K, seconds = 280)

        verify(statedDao).state(
            StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_1K, seconds = 280)
        )
        // And the 5 km is untouched — a second claim is not a correction of the first.
        verify(statedDao, never()).withdraw(any(), any())
    }

    @Test
    fun `only a treadmill Run can be told a best effort`() = runTest {
        // An outdoor Run's efforts are measured, and one whose GPS recorded nothing is not rescued
        // this way — the same refusal a stated distance makes, and what keeps any Run from holding
        // a measured effort and a stated one at the same record.
        whenever(mockDao.getSessionById(42L))
            .thenReturn(session(id = 42, endTime = 1_000L).copy(runMode = "outdoor"))
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 1_440)

        verify(statedDao, never()).state(any())
    }

    @Test
    fun `a claim the Run could not contain is refused`() = runTest {
        // Not the app doubting the runner, which it does nowhere: an implausible time is believed
        // and corrected afterwards. What is refused is the arithmetically impossible — a 5 km that
        // took longer than the whole Run, and a 10 km inside a Run of six.
        whenever(mockDao.getSessionById(42L))
            .thenReturn(aTreadmillRun(id = 42, seconds = 1_800).copy(distanceKm = 6.0))
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 1_801)
        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_10K, seconds = 1_700)
        // Neither is a record run over a distance, so neither is a Best Effort to be told.
        repositoryWithRecords.stateBestEffort(42L, RecordType.LONGEST_DISTANCE, seconds = 100)
        repositoryWithRecords.stateBestEffort(42L, RecordType.LONGEST_DURATION, seconds = 100)
        // And a time is a positive number of seconds or it is nothing.
        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 0)

        verify(statedDao, never()).state(any())
    }

    @Test
    fun `a Run nobody stated a distance for can still be told a best effort`() = runTest {
        // The two statements are independent: a runner who noted the 5 km split and never looked at
        // the total has still said something true.
        whenever(mockDao.getSessionById(42L)).thenReturn(aTreadmillRun(id = 42, seconds = 1_800))
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(emptyList())
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 1_440)

        verify(statedDao).state(
            StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_5K, seconds = 1_440)
        )
    }

    @Test
    fun `a half marathon is allowed the rounding a stated distance is typed at`() = runTest {
        // 21.09 km is how a genuine half marathon reads in a field that takes two decimal places,
        // and the record is 21 097.5 m. The shortfall is the format's, not the runner's.
        whenever(mockDao.getSessionById(42L))
            .thenReturn(aTreadmillRun(id = 42, seconds = 7_200).copy(distanceKm = 21.09))
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(emptyList())
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_HALF, seconds = 7_000)

        verify(statedDao).state(
            StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_HALF, seconds = 7_000)
        )
    }

    @Test
    fun `stating the time already there costs nothing`() = runTest {
        whenever(mockDao.getSessionById(42L))
            .thenReturn(aTreadmillRun(id = 42, seconds = 1_800).copy(distanceKm = 6.0))
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(
            listOf(StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_5K, seconds = 1_440))
        )
        var refreshCount = 0
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            statedBestEffortDao = statedDao,
            refreshHistoryBackup = { refreshCount++ },
        )

        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 1_440)

        verify(statedDao, never()).state(any())
        assertEquals(0, refreshCount)
    }

    @Test
    fun `a slower correction rebuilds the record it held, promoting the run behind it`() = runTest {
        // The opposite direction from a stated distance, and the same rule: a claim made worse can
        // demote a Medal, and the Run that should move up exists nowhere but in history.
        val medalHolder = aTreadmillRun(id = 2, seconds = 1_800).copy(distanceKm = 6.0)
        whenever(mockDao.getSessionById(2L)).thenReturn(medalHolder)
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(2L)).thenReturn(
            listOf(StatedBestEffort(sessionId = 2, type = RecordType.FASTEST_5K, seconds = 1_380))
        )
        // Every claim in history as it stands once the correction has landed. Run 1's 24:00 was
        // second and exists nowhere but here, since only the top three are ever banked — which is
        // why re-scoring Run 2 alone could not find it.
        whenever(statedDao.getAll()).thenReturn(
            listOf(
                StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_5K, seconds = 1_440),
                StatedBestEffort(sessionId = 2, type = RecordType.FASTEST_5K, seconds = 1_500),
            )
        )
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_380.0))
        )
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_800).copy(distanceKm = 6.0),
                medalHolder,
            )
        )
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateBestEffort(2L, RecordType.FASTEST_5K, seconds = 1_500)

        verify(statedDao).state(
            StatedBestEffort(sessionId = 2, type = RecordType.FASTEST_5K, seconds = 1_500)
        )
        // Only the record the claim was made at is rebuilt: nothing else about the Run moved.
        verify(mockAchievementDao).deleteAchievementsOfTypes(listOf(RecordType.FASTEST_5K))
        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(1L to Medal.GOLD, 2L to Medal.SILVER),
            book.firstValue.map { it.sessionId to it.medal },
        )
    }

    @Test
    fun `a claim the Run stopped containing while it was being saved is refused`() = runTest {
        // The check that matters is the one inside the transaction that stores the claim. Here the
        // Run reads as 6 km when the dialog is answered and as 3 km by the time the write lands —
        // a distance corrected in between — and the claim must not reach the book.
        val asAnswered = aTreadmillRun(id = 42, seconds = 1_800).copy(distanceKm = 6.0)
        val asStored = asAnswered.copy(distanceKm = 3.0)
        whenever(mockDao.getSessionById(42L)).thenReturn(asAnswered, asStored)
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(emptyList())
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 1_440)

        verify(statedDao, never()).state(any())
    }

    @Test
    fun `a claim whose scoring cannot finish leaves the Run owing one`() = runTest {
        // A Run is marked scored once and never revisited, so a claim stored against a marked Run
        // is a medal nobody goes back for the moment the scoring behind it ends short. The mark is
        // lifted before the write and handed back only once the book has taken it.
        val run = aTreadmillRun(id = 42, seconds = 1_800).copy(distanceKm = 6.0)
        whenever(mockDao.getSessionById(42L)).thenReturn(run)
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(emptyList())
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenThrow(RuntimeException("no book today"))
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 1_440)

        // The claim is stored — a book that cannot be written must not read as a statement that did
        // not save — and the debt is left standing rather than marked paid.
        verify(statedDao).state(
            StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_5K, seconds = 1_440)
        )
        verify(mockDao).clearRecordsScored(42L)
        verify(mockDao, never()).setRecordsScored(42L)
    }

    @Test
    fun `a claim that scores hands the mark back`() = runTest {
        val run = aTreadmillRun(id = 42, seconds = 1_800).copy(distanceKm = 6.0)
        whenever(mockDao.getSessionById(42L)).thenReturn(run)
        val claim = StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_5K, seconds = 1_440)
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(emptyList(), listOf(claim))
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 1_440)

        verify(mockDao).setRecordsScored(42L)
    }

    @Test
    fun `correcting the distance takes the best efforts it has made impossible with it`() = runTest {
        // A Run that says it went three kilometres cannot hold a five. A correction is the one way a
        // claim that was possible when it was made stops being one, so the same act removes it —
        // otherwise a Medal stands on an impossible claim with nothing left to notice.
        val run = aTreadmillRun(id = 2, seconds = 1_800).copy(distanceKm = 6.0)
        whenever(mockDao.getSessionById(2L)).thenReturn(run)
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(2L)).thenReturn(
            listOf(
                StatedBestEffort(sessionId = 2, type = RecordType.FASTEST_1K, seconds = 280),
                StatedBestEffort(sessionId = 2, type = RecordType.FASTEST_5K, seconds = 1_380),
            )
        )
        // What is left once the correction has landed: the 1 km survives a 3 km Run, the 5 km does
        // not, and Run 1's 24:00 exists nowhere but here.
        whenever(statedDao.getAll()).thenReturn(
            listOf(
                StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_5K, seconds = 1_440),
                StatedBestEffort(sessionId = 2, type = RecordType.FASTEST_1K, seconds = 280),
            )
        )
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(
            listOf(
                Achievement(sessionId = 2, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_380.0),
                Achievement(sessionId = 2, type = RecordType.LONGEST_DISTANCE, medal = Medal.GOLD, value = 6_000.0),
            )
        )
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_800).copy(distanceKm = 6.0),
                run.copy(distanceKm = 3.0),
            )
        )
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateDistance(2L, distanceKm = 3.0)

        // The 5 km goes; the 1 km stays, because a 3 km Run holds a kilometre perfectly well.
        verify(statedDao).withdraw(2L, RecordType.FASTEST_5K)
        verify(statedDao, never()).withdraw(2L, RecordType.FASTEST_1K)
        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        // Run 1 takes the 5 km the correction gave up, and Run 2's own 1 km is untouched by any of it.
        assertEquals(
            listOf(1L to RecordType.FASTEST_5K, 1L to RecordType.LONGEST_DISTANCE, 2L to RecordType.LONGEST_DISTANCE),
            book.firstValue.map { it.sessionId to it.type }.sortedWith(compareBy({ it.first }, { it.second })),
        )
    }

    @Test
    fun `withdrawing the distance entirely orphans no best effort`() = runTest {
        // A Run with no stated distance contradicts no claim: the two statements are independent in
        // what they require of each other, and only a distance that is *there* can be too short.
        val run = aTreadmillRun(id = 2, seconds = 1_800).copy(distanceKm = 6.0)
        whenever(mockDao.getSessionById(2L)).thenReturn(run)
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(2L)).thenReturn(
            listOf(StatedBestEffort(sessionId = 2, type = RecordType.FASTEST_5K, seconds = 1_380))
        )
        whenever(statedDao.getAll()).thenReturn(
            listOf(StatedBestEffort(sessionId = 2, type = RecordType.FASTEST_5K, seconds = 1_380))
        )
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(emptyList())
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockDao.getAllSessions()).thenReturn(listOf(run.copy(distanceKm = 0.0)))
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateDistance(2L, distanceKm = null)

        verify(statedDao, never()).withdraw(any(), any())
    }

    @Test
    fun `a withdrawn best effort gives up the medal it held`() = runTest {
        val medalHolder = aTreadmillRun(id = 2, seconds = 1_800).copy(distanceKm = 6.0)
        whenever(mockDao.getSessionById(2L)).thenReturn(medalHolder)
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(2L)).thenReturn(
            listOf(StatedBestEffort(sessionId = 2, type = RecordType.FASTEST_5K, seconds = 1_380))
        )
        // Withdrawn, so history holds only Run 1's claim by the time the rebuild reads it.
        whenever(statedDao.getAll()).thenReturn(
            listOf(StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_5K, seconds = 1_440))
        )
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAchievementsForSessions(listOf(2L))).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_380.0))
        )
        // The book still holds Run 2's gold while the rebuild measures, which is what it must see
        // past: a Run that now claims nothing must not have its old row carried back in.
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(
            listOf(Achievement(sessionId = 2, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_380.0))
        )
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_800).copy(distanceKm = 6.0),
                medalHolder,
            )
        )
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repositoryWithRecords.stateBestEffort(2L, RecordType.FASTEST_5K, seconds = null)

        verify(statedDao).withdraw(2L, RecordType.FASTEST_5K)
        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(
            listOf(1L to 1_440.0),
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
    fun `getTrackPointsForMap carries an opening pause boundary onto the point that replaces it`() = runTest {
        // A Run paused before its first fix landed marks that opening fix (#195). Thrown out by the
        // accuracy gate, it hands the mark on like any other — and the point that takes it is the
        // opening fix in its turn, where every reader that measures between fixes leaves it alone.
        val sessionId = 7L
        val openedButNoisy = trackPoint(sessionId, lon = 1.0, accuracy = 45f, source = TrackPointSource.GPS)
            .copy(startsAfterPause = true)
        val nextFix = trackPoint(sessionId, lon = 2.0, accuracy = 15f, source = TrackPointSource.GPS)
        val mockTrackPointDao: TrackPointDao = mock()
        whenever(mockTrackPointDao.getTrackPointsForSessionOnce(sessionId)).thenReturn(
            listOf(openedButNoisy, nextFix)
        )
        val repositoryWithTrackPoints = SessionRepository(sessionDao = mockDao, trackPointDao = mockTrackPointDao)

        val result = repositoryWithTrackPoints.getTrackPointsForMap(sessionId)

        assertEquals(listOf(nextFix.copy(startsAfterPause = true)), result)
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
    fun `deleting a run takes back the coaching that stood on it`() = runTest {
        // The bug this closes: the row goes and the workout the coach prescribed *from* it stays on
        // the card, debrief and all (#156).
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val repositoryWithCoach = SessionRepository(
            sessionDao = mockDao,
            coachPrescriptionRepository = mockPrescriptions
        )

        repositoryWithCoach.deleteSessions(listOf(7L, 8L))

        verify(mockPrescriptions).forgetWorkFedBy(setOf(7L, 8L))
    }

    @Test
    fun `deleting a run kept out of AI training disturbs no coaching`() = runTest {
        // A Run the runner excluded from AI training was never evidence for anything, so nothing the
        // coach said can be about it.
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        mockDao.stub { onBlocking { getAiEligibleIdsIn(any()) }.thenReturn(emptyList()) }
        val repositoryWithCoach = SessionRepository(
            sessionDao = mockDao,
            coachPrescriptionRepository = mockPrescriptions
        )

        repositoryWithCoach.deleteSession(sessionId = 7L)

        verify(mockDao).deleteSessionById(7L)
        // An empty set is a rollback of nothing, so this may be called or not — what must never
        // happen is the deleted Run being offered as one the coach could have reasoned from.
        verify(mockPrescriptions, never()).forgetWorkFedBy(setOf(7L))
    }

    @Test
    fun `which runs fed the coach is asked before the rows are gone`() = runTest {
        // Afterwards there is nothing left to ask: the sharing flag lives on the row being deleted.
        val repositoryWithCoach = SessionRepository(
            sessionDao = mockDao,
            coachPrescriptionRepository = mock()
        )

        repositoryWithCoach.deleteSession(sessionId = 7L)

        inOrder(mockDao) {
            verify(mockDao).getAiEligibleIdsIn(listOf(7L))
            verify(mockDao).deleteSessionById(7L)
        }
    }

    @Test
    fun `a delete cut short on the way out still takes the coaching back`() = runTest {
        // The rollback is the delete's promise to the runner, and there is no second attempt at it
        // and no startup pass behind it — so it must not be one of the things a cancelled scope
        // walks away from (#156).
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        var tookBack = emptySet<Long>()
        mockPrescriptions.stub {
            onBlocking { forgetWorkFedBy(any()) }.doSuspendableAnswer { asked ->
                // The real rollback suspends on the settings store, which is where a cancelled
                // scope would otherwise abandon it half-done.
                yield()
                tookBack = asked.getArgument(0)
            }
        }
        val repositoryWithCoach = SessionRepository(
            sessionDao = mockDao,
            coachPrescriptionRepository = mockPrescriptions
        )
        lateinit var deleting: Job
        mockDao.stub {
            // The runner leaves the history screen the instant the row goes, taking the view
            // model's scope with them.
            onBlocking { deleteSessionById(any()) }.doSuspendableAnswer { deleting.cancel() }
        }

        deleting = launch { repositoryWithCoach.deleteSession(7L) }
        deleting.join()

        // The row is gone for good, so the coaching that stood on it cannot be left standing.
        assertEquals(setOf(7L), tookBack)
    }

    @Test
    fun `the coaching is taken back before the backup, not after it`() = runTest {
        // The Downloads snapshot is a copy of rows that have already gone; the coaching is the
        // thing the delete promised. Behind the backup, a process reclaimed mid-copy would leave
        // the card coaching about a Run nobody has — the whole complaint of #156.
        val order = mutableListOf<String>()
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        mockPrescriptions.stub {
            onBlocking { forgetWorkFedBy(any()) }.doSuspendableAnswer { order += "take back" }
        }
        val repositoryWithCoach = SessionRepository(
            sessionDao = mockDao,
            coachPrescriptionRepository = mockPrescriptions,
            refreshHistoryBackup = { order += "backup" }
        )

        repositoryWithCoach.deleteSession(7L)

        assertEquals(listOf("take back", "backup"), order)
    }

    @Test
    fun `a stated distance leaves the coaching alone`() = runTest {
        // A correction is not a Run leaving history: the evidence the coach reasoned from is all still
        // there, and a Run's own evaluation is deliberately never replayed when a distance arrives
        // later (#231).
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val repositoryWithCoach = SessionRepository(
            sessionDao = mockDao,
            coachPrescriptionRepository = mockPrescriptions
        )
        whenever(mockDao.getSessionById(42L)).thenReturn(aTreadmillRun(id = 42, seconds = 1_500))

        repositoryWithCoach.stateDistance(42L, distanceKm = 5.0)

        verify(mockPrescriptions, never()).forgetWorkFedBy(any())
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
    fun `the highest recorded heart rate is the one held for three seconds`() = runTest {
        // The spike guard the card's suggestion rests on (#65, #103): a maximum offered to the
        // runner has to be a heart rate they reached and stayed at, not a strap artefact. Three
        // banked seconds, because samples are banked once a second.
        val mockSampleDao: SampleDao = mock()
        val repository = SessionRepository(sessionDao = mockDao, sampleDao = mockSampleDao)
        whenever(mockSampleDao.getHighestSustainedBpm(3)).thenReturn(181)

        assertEquals(181, repository.highestRecordedHr())
    }

    @Test
    fun `a history with nothing recorded has no maximum to offer`() = runTest {
        // Both ways of having nothing: samples not wired at all, and samples wired but too few to
        // clear the guard. One answer, because the card asks the same question of both.
        val mockSampleDao: SampleDao = mock()
        whenever(mockSampleDao.getHighestSustainedBpm(any())).thenReturn(null)

        assertNull(SessionRepository(sessionDao = mockDao).highestRecordedHr())
        assertNull(SessionRepository(sessionDao = mockDao, sampleDao = mockSampleDao).highestRecordedHr())
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
            sessionId = 7L, zone1 = 0, zone2 = 2, zone3 = 1, zone4 = 0, zone5 = 0, effortScore = 0, bandedOnMaxHr = 181, bandedOnRestingHr = 0
        )
        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 8L, zone1 = 0, zone2 = 0, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = null, bandedOnMaxHr = 181, bandedOnRestingHr = 0
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
                sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0, bandedOnMaxHr = 181, bandedOnRestingHr = 0
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
        verify(mockDao, never()).updateZoneSecondsAndEffort(any(), any(), any(), any(), any(), any(), anyOrNull(), any(), any())
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
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0, bandedOnMaxHr = 181, bandedOnRestingHr = 60
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
            sessionId = 7L, zone1 = 1, zone2 = 1, zone3 = 1, zone4 = 0, zone5 = 0, effortScore = 0, bandedOnMaxHr = 181, bandedOnRestingHr = 60
        )
        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 8L, zone1 = 0, zone2 = 0, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = null, bandedOnMaxHr = 181, bandedOnRestingHr = 60
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
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0, bandedOnMaxHr = 181, bandedOnRestingHr = 52
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
                sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0, bandedOnMaxHr = 181, bandedOnRestingHr = 60
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
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0, bandedOnMaxHr = 181, bandedOnRestingHr = 60
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
            sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0, bandedOnMaxHr = 181, bandedOnRestingHr = 60
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
            sessionId = 7L, zone1 = 0, zone2 = 0, zone3 = 1, zone4 = 0, zone5 = 0, effortScore = 0, bandedOnMaxHr = 181, bandedOnRestingHr = 60
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
            sessionId = 7L, zone1 = 0, zone2 = 600, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 20, bandedOnMaxHr = 181, bandedOnRestingHr = 60
        )
    }

    @Test
    fun `a re-tally stamps every run it re-bands with the heart rates it re-banded it against`() = runTest {
        // A run recorded under one Reserve and re-banded onto another really is on the new one
        // afterwards (#228). Left holding the Reserve it started life on, its route map would
        // colour by numbers its own zone bars had moved off.
        val mockSampleDao: SampleDao = mock()
        val repositoryWithSamples = SessionRepository(
            sessionDao = mockDao,
            sampleDao = mockSampleDao,
            settingsRepository = mockSettingsRepo
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(maxHr = 195, maxHrEverSet = true, historyMaxHr = 181)))
        whenever(mockDao.getFinalizedSessionIds()).thenReturn(listOf(7L))
        whenever(mockSampleDao.getRawBpmsForSession(7L)).thenReturn(listOf(150))

        repositoryWithSamples.setStatedProfile(maxHr = null, restingHr = 60)

        // The pair history is re-banded against, which is the stored 195's history maximum of 181
        // beside the resting heart rate just stated — never the 195 in force.
        verify(mockDao).updateZoneSecondsAndEffort(
            sessionId = 7L, zone1 = 0, zone2 = 0, zone3 = 1, zone4 = 0, zone5 = 0, effortScore = 0,
            bandedOnMaxHr = 181, bandedOnRestingHr = 60
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
            onBlocking { updateZoneSecondsAndEffort(any(), any(), any(), any(), any(), any(), anyOrNull(), any(), any()) }
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
                sessionId = 7L, zone1 = 0, zone2 = 1, zone3 = 0, zone4 = 0, zone5 = 0, effortScore = 0, bandedOnMaxHr = 181, bandedOnRestingHr = 60
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
        verify(mockDao, never()).updateZoneSecondsAndEffort(any(), any(), any(), any(), any(), any(), anyOrNull(), any(), any())
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
        verify(mockDao, never()).updateZoneSecondsAndEffort(any(), any(), any(), any(), any(), any(), anyOrNull(), any(), any())
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
        // A structured Run recorded under the Stage being graduated, which is the only kind that
        // can graduate one (#234, #275).
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 1_000_000L)
            )
        )
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
                // Named, and it is the structured Run the Stage can be graduated on (#287).
                graduationEvidenceRunTimestamps = listOf(1_000_000L),
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions("sub_30_bridge", activeScope)
        verify(mockPrescriptions, never()).prescribe(any(), any(), any(), any(), any())
        verify(mockPrescriptions, never()).amendStanding(any(), any(), any())
        // The debrief is about the run just finished, so it survives the graduation.
        verify(mockSettingsRepo).setLatestDebrief("Stage complete.", DebriefAuthor.COACH, activeScope)
    }

    // --- A requirement stated in numbers is answered by the app (#290, ADR 0016) ---------------
    //
    // Stage 2 asks for a 5K under 30 minutes and stage 3 for one in 24:59 or faster, so both carry
    // a BestEffortRequirement. The Runs below are treadmill Runs told what their console showed,
    // which is the cheapest way to hand the rule a Best Effort — a measured one is the same number
    // arriving through the same door (`bestEffortsOf`).

    /** The Stage's own Runs, load and latest-finish stubs the coach's path reads on its way past. */
    private suspend fun stubTheCoachsReads() {
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
    }

    /** A finished treadmill Run of [id], told its console showed [fiveKSeconds] for 5 km. */
    private suspend fun aRunTold(
        id: Long,
        fiveKSeconds: Int,
        statedDao: StatedBestEffortDao,
        isWalk: Boolean = false,
        isRunWalkMode: Boolean = true,
    ): RunnerSession {
        whenever(statedDao.getForSession(id)).thenReturn(
            listOf(StatedBestEffort(sessionId = id, type = RecordType.FASTEST_5K, seconds = fiveKSeconds))
        )
        return aTreadmillRun(id = id, seconds = 1_900)
            .copy(distanceKm = 5.0, isWalk = isWalk, isRunWalkMode = isRunWalkMode)
    }

    @Test
    fun `the app answers the requirement before the coach is asked anything`() = runTest {
        // The ordering is the decision (#290): the rule grants, the stored Stage moves, and the
        // coach's own guard then finds a Stage the Run no longer belongs to. Pinned here because a
        // later refactor reordering these two would have the coach prescribing into a Stage the
        // runner has already left.
        val statedDao: StatedBestEffortDao = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
            aiCoachClient = mockCoach,
        )
        // The settings as the rule finds them, and then as the coach's path finds them a moment
        // later — which is what advancing the Stage does to the second read.
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge")),
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_25_peak")),
        )
        stubTheCoachsReads()
        val run = aRunTold(id = 7, fiveKSeconds = 1_632, statedDao = statedDao)

        repo.settleStageAfterRun("sub_30_bridge", RunType.LONG, run)

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions(
            "sub_25_peak",
            CoachWriteScope("5k_sub_25", "sub_30_bridge")
        )
        // 27:12, and the runner is told what they ran rather than what the bar was.
        verify(mockSettingsRepo).setLatestDebrief(
            "You ran 5 km in 27:12. Stage 2: Sub-30 Bridge complete. Next up: Stage 3: Sub-25 Peak.",
            // Stamped as the app's, because the app wrote it: the card must not head these words
            // with the coach's name (#296).
            DebriefAuthor.APP,
            CoachWriteScope("5k_sub_25", "sub_30_bridge")
        )
        // And the coach is never asked, because by the time it looks the Stage has moved.
        verify(mockCoach, never()).evaluateProgress(any())
    }

    @Test
    fun `an opted-out Run is not sent to the coach, whatever row sorts latest`() = runTest {
        // Consent belongs to the Run being evaluated, not to whichever row `getMostRecentFinalized
        // Session` happens to return — a restored future-dated session is enough to make them
        // different rows, and the shareable one must not speak for the opted-out one (#290).
        val statedDao: StatedBestEffortDao = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
            aiCoachClient = mockCoach,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
        )
        stubTheCoachsReads()
        // Some other row sorts latest, and it is shareable.
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        // The Run that actually finished answers nothing and is not the runner's to share.
        val run = aRunTold(id = 7, fiveKSeconds = 1_900, statedDao = statedDao)
            .copy(includeInAiTraining = false)

        repo.settleStageAfterRun("sub_30_bridge", RunType.LONG, run)

        verify(mockCoach, never()).evaluateProgress(any())
    }

    @Test
    fun `the rule runs first even where the coach is still asked afterwards`() = runTest {
        // The same ordering, pinned without leaning on the guard that makes it invisible: the
        // settings do not move under the second read, so the coach is asked — and the grant still
        // has to have happened first.
        val statedDao: StatedBestEffortDao = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
            aiCoachClient = mockCoach,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
        )
        stubTheCoachsReads()
        whenever(mockCoach.evaluateProgress(any())).thenReturn(null)
        val run = aRunTold(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)

        repo.settleStageAfterRun("sub_30_bridge", RunType.LONG, run)

        inOrder(mockSettingsRepo, mockCoach) {
            verify(mockSettingsRepo).advanceStageAndClearPrescriptions(any(), any())
            verify(mockCoach).evaluateProgress(any())
        }
    }

    @Test
    fun `an Open Run answers a requirement stated as a time`() = runTest {
        // The old bar on an Open Run is sound for a structural requirement and wrong for a time
        // (#290): a parkrun is the truest 5K test there is, and the number is the number wherever
        // it turned up.
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
        )
        stubTheCoachsReads()
        val run = aRunTold(id = 7, fiveKSeconds = 1_700, statedDao = statedDao, isRunWalkMode = false)

        repo.settleStageAfterRun("sub_30_bridge", runType = null, finalizedRun = run)

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions(eq("sub_25_peak"), any())
    }

    @Test
    fun `a Walk graduates nothing, however quick the console said it was`() = runTest {
        // A Walk holds no Best Effort at all, so it clears no bar — the rule inherits that from
        // `bestEffortsOf` rather than restating it (#275, #290).
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
        )
        stubTheCoachsReads()
        val run = aRunTold(id = 7, fiveKSeconds = 1_500, statedDao = statedDao, isWalk = true)

        repo.settleStageAfterRun("sub_30_bridge", runType = null, finalizedRun = run)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a runner who shares nothing with the coach still graduates`() = runTest {
        // "AI training data sharing" is consent to send a Run to Gemini, and this rule sends nothing
        // anywhere. Gating on it would mean a runner who never turns it on can never leave stage 2,
        // because the coach may no longer grant a requirement written in numbers (ADR 0016) — a
        // privacy choice quietly becoming a plan that cannot progress.
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
        )
        stubTheCoachsReads()
        val run = aRunTold(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
            .copy(includeInAiTraining = false)

        repo.settleStageAfterRun("sub_30_bridge", runType = null, finalizedRun = run)

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions(eq("sub_25_peak"), any())
    }

    @Test
    fun `a 5K one second over the bar does not graduate, and one on it does`() = runTest {
        // "Under 30 minutes" is 1799 and not 1800, which is the whole of what the stored number
        // means (#290).
        suspend fun settleWith(fiveKSeconds: Int): SettingsRepository {
            val statedDao: StatedBestEffortDao = mock()
            val settingsRepo: SettingsRepository = mock()
            val repo = SessionRepository(
                sessionDao = mockDao,
                settingsRepository = settingsRepo,
                statedBestEffortDao = statedDao,
            )
            whenever(settingsRepo.userSettingsFlow).thenReturn(
                flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
            )
            stubTheCoachsReads()
            val run = aRunTold(id = 7, fiveKSeconds = fiveKSeconds, statedDao = statedDao)
            repo.settleStageAfterRun("sub_30_bridge", runType = null, finalizedRun = run)
            return settingsRepo
        }

        verify(settleWith(1_799)).advanceStageAndClearPrescriptions(eq("sub_25_peak"), any())
        verify(settleWith(1_800), never()).advanceStageAndClearPrescriptions(any(), any())
    }

    @Test
    fun `the coach cannot graduate a Stage whose requirement the app answers`() = runTest {
        // The prompt tells it not to, and a prompt sentence is a promise the code has to keep
        // (#286, #288): the two paths must never both be able to grant.
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = mockPrescriptions,
            aiCoachClient = mockCoach,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
        )
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        // A structured Run of the Stage's own, named as the evidence — everything the coach's own
        // graduation needs, so the only thing refusing it is the Stage stating its bar in numbers.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(session(id = 1, endTime = 1_000L).copy(isRunWalkMode = true, startTime = 1_000_000L))
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 600,
                nextWalkDurationSeconds = 60,
                nextRepeats = 4,
                nextTargetZone = null,
                graduatedToNextStage = true,
                graduationEvidenceRunTimestamps = listOf(1_000_000L),
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("sub_30_bridge", RunType.LONG)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        // Refused, so this is an ordinary evaluation and the debrief goes with the numbers (#156).
        verify(mockPrescriptions).prescribe(any(), any(), eq("Stage complete."), any(), any())
    }

    // --- Finishing the whole Plan (#294) -------------------------------------------------------

    /** London, so a Run stamped at midday is plainly inside one day whatever the machine's zone. */
    private val london = ZoneId.of("Europe/London")

    /** A Run of [fiveKSeconds] that started at noon on [day] — the day a finished plan records. */
    private suspend fun aRunOn(day: String, fiveKSeconds: Int, statedDao: StatedBestEffortDao) =
        aRunTold(id = 7, fiveKSeconds = fiveKSeconds, statedDao = statedDao).copy(
            startTime = LocalDate.parse(day).atTime(12, 0).atZone(london).toInstant().toEpochMilli()
        )

    /** The rule's own settings, carrying [completion] as the Plan the runner has finished. */
    private fun onTheLastStage(completion: PlanCompletion? = null) = UserSettings(
        activePlanId = "5k_sub_25",
        activeStageId = "sub_25_peak",
        planCompletion = completion
    )

    @Test
    fun `the day a plan is recorded on is the Run's own, wherever the phone is by then`() = runTest {
        // #304's headline case. A Run at 23:30 in London, and the claim typed the next morning
        // after the runner has flown to Sydney — where the same moment reads as the fifteenth.
        // Every other day in the app self-corrects on the way home; this one is written once.
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(onTheLastStage()))
        stubTheCoachsReads()
        val run = aRunTold(id = 7, fiveKSeconds = 1_463, statedDao = statedDao).copy(
            startTime = LocalDate.parse("2026-08-14").atTime(23, 30)
                .atZone(london).toInstant().toEpochMilli(),
            ranAtUtcOffsetSeconds = 3_600,
        )

        repo.settleStageAfterRun(
            "sub_25_peak",
            runType = null,
            finalizedRun = run,
            zone = ZoneId.of("Australia/Sydney"),
        )

        verify(mockSettingsRepo).completePlan(
            argThat { completedOnEpochDay == LocalDate.parse("2026-08-14").toEpochDay() },
            any(),
            any(),
        )
    }

    @Test
    fun `a Run that wrote down no offset records the day in the phone's zone`() = runTest {
        // Every Run recorded before v32: nothing can say where its clock was, so the fallback is
        // the behaviour it has always had rather than a guess dressed up as a fact.
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(onTheLastStage()))
        stubTheCoachsReads()
        val run = aRunTold(id = 7, fiveKSeconds = 1_463, statedDao = statedDao).copy(
            startTime = LocalDate.parse("2026-08-14").atTime(23, 30)
                .atZone(london).toInstant().toEpochMilli(),
            ranAtUtcOffsetSeconds = null,
        )

        repo.settleStageAfterRun(
            "sub_25_peak",
            runType = null,
            finalizedRun = run,
            zone = ZoneId.of("Australia/Sydney"),
        )

        verify(mockSettingsRepo).completePlan(
            argThat { completedOnEpochDay == LocalDate.parse("2026-08-15").toEpochDay() },
            any(),
            any(),
        )
    }

    @Test
    fun `finishing the last Stage records the plan as complete and says so`() = runTest {
        // There is no Stage 4 to advance to, and clearing the standing Prescription here would
        // delete the runner's numbers and leave them in a Stage that never moved — the bug #294
        // exists to fix. What replaces it is a recorded completion: the plan, the day and the time.
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(onTheLastStage()))
        stubTheCoachsReads()
        val run = aRunOn("2026-08-14", fiveKSeconds = 1_463, statedDao = statedDao)

        repo.settleStageAfterRun("sub_25_peak", runType = null, finalizedRun = run, zone = london)

        verify(mockSettingsRepo).completePlan(
            PlanCompletion(
                planId = "5k_sub_25",
                // The Run's own day, not the day this write happened.
                completedOnEpochDay = LocalDate.parse("2026-08-14").toEpochDay(),
                seconds = 1_463
            ),
            "You ran 5 km in 24:23. Stage 3: Sub-25 Peak complete. " +
                "That's the whole plan: 5K to Sub-25 Progressive Plan, done.",
            CoachWriteScope("5k_sub_25", "sub_25_peak")
        )
        // The Prescription stands, and the debrief slot is written by the completion itself rather
        // than by a second write that could land without it.
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a plan already finished is not finished a second time`() = runTest {
        // A later Run clearing the bar again records nothing and congratulates nobody: this is the
        // day the plan was finished, not the runner's best — the record book owns that (#294).
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(
                onTheLastStage(
                    PlanCompletion(
                        planId = "5k_sub_25",
                        completedOnEpochDay = LocalDate.parse("2026-08-14").toEpochDay(),
                        seconds = 1_492
                    )
                )
            )
        )
        stubTheCoachsReads()
        // Quicker than the recorded time, which changes nothing either.
        val run = aRunOn("2026-09-01", fiveKSeconds = 1_400, statedDao = statedDao)

        repo.settleStageAfterRun("sub_25_peak", runType = null, finalizedRun = run, zone = london)

        verify(mockSettingsRepo, never()).completePlan(any(), any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a completion belonging to another plan does not stand in the way of this one`() = runTest {
        // Keyed by plan id, so a plan finished under a different plan is not this plan's ending
        // (#294).
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(
                onTheLastStage(
                    PlanCompletion(
                        planId = "desk_test_plan",
                        completedOnEpochDay = 0L,
                        seconds = 1_400
                    )
                )
            )
        )
        stubTheCoachsReads()
        val run = aRunOn("2026-08-14", fiveKSeconds = 1_463, statedDao = statedDao)

        repo.settleStageAfterRun("sub_25_peak", runType = null, finalizedRun = run, zone = london)

        verify(mockSettingsRepo).completePlan(
            eq(PlanCompletion("5k_sub_25", LocalDate.parse("2026-08-14").toEpochDay(), 1_463)),
            any(),
            any()
        )
    }

    @Test
    fun `a Run short of the bar finishes nothing`() = runTest {
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(onTheLastStage()))
        stubTheCoachsReads()
        val run = aRunOn("2026-08-14", fiveKSeconds = 1_500, statedDao = statedDao)

        repo.settleStageAfterRun("sub_25_peak", runType = null, finalizedRun = run, zone = london)

        verify(mockSettingsRepo, never()).completePlan(any(), any(), any())
    }

    @Test
    fun `the coach is told the plan is finished, and not told so while it is not`() = runTest {
        // The Stage is still theirs and still has Workouts; what must stop is the coach being told
        // forever to aim them at a time they have already run (#294).
        val repo = SessionRepository(sessionDao = mockDao, settingsRepository = mockSettingsRepo)
        stubTheCoachsReads()

        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(onTheLastStage()))
        assertFalse(repo.getAiTrainingContext("sub_25_peak").planComplete)

        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(onTheLastStage(PlanCompletion("5k_sub_25", 20_000L, 1_492)))
        )
        assertTrue(repo.getAiTrainingContext("sub_25_peak").planComplete)
        // An earlier Stage of the same plan is not the end of anything: a runner who re-attached
        // the plan is in Stage 1, and telling the coach there is no Stage after it is a sentence
        // their own plan contradicts.
        assertFalse(repo.getAiTrainingContext("sub_30_bridge").planComplete)
        // And a Stage of a plan nobody has finished is not swept up by it.
        assertFalse(repo.getAiTrainingContext("desk_test_stage").planComplete)
    }

    // --- When the Stage is settled: once the runner's word is in (#297) ------------------------
    //
    // The rule above is asked of a Run; these are about the moment it is asked. The Walk mark is
    // the runner's own word and it arrives on the finish sheet, seconds after STOP — so a
    // judgement made at STOP is made before the one fact that can withdraw the Run from it.

    /**
     * A finished Run of the Stage under test, in the database and owing a settlement — which is
     * what every Run reaches the finish owing.
     */
    private suspend fun aRunOwingSettlement(
        id: Long,
        fiveKSeconds: Int,
        statedDao: StatedBestEffortDao,
        isWalk: Boolean = false,
        workoutId: String? = null,
    ): RunnerSession {
        val run = aRunTold(id = id, fiveKSeconds = fiveKSeconds, statedDao = statedDao, isWalk = isWalk)
            .copy(ranUnderStageId = "sub_30_bridge", ranUnderWorkoutId = workoutId, stageSettled = false)
        whenever(mockDao.getSessionById(id)).thenReturn(run)
        return run
    }

    /**
     * A repository settling Runs for a runner in [activeStageId], with the coach's own reads stubbed
     * — the arrange every test in this section makes.
     */
    private suspend fun repositoryForSettling(
        statedDao: StatedBestEffortDao,
        coach: AiCoachClient? = null,
        activeStageId: String = "sub_30_bridge",
        refreshHistoryBackup: (suspend () -> Unit)? = null,
    ): SessionRepository {
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = activeStageId))
        )
        stubTheCoachsReads()
        return SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
            aiCoachClient = coach,
            refreshHistoryBackup = refreshHistoryBackup,
        )
    }

    @Test
    fun `a Run whose finish sheet is still open is not settled at the finish`() = runTest {
        // The sheet carrying the Walk switch is on screen from the moment STOP is pressed. Judging
        // the Run before it resolves is judging it before the runner has said what it was (#297).
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)

        repo.finishSheetOpened(7L)
        repo.settleStageForRun(7L)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        // And the debt stands, so the sheet — or the next launch — still has it to pay.
        verify(mockDao, never()).setStageSettled(any())
    }

    @Test
    fun `the Walk marked on the finish sheet reaches the rule`() = runTest {
        // The defect this ticket is about (#297): the Run cleared the bar as it ended, and the
        // runner then said it was a walk. A Walk holds no Best Effort, so it graduates nothing —
        // and that promise is only kept if the mark is in before the rule is asked.
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        val asItEnded = aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
        assertFalse("the row at STOP carries no mark", asItEnded.isWalk)

        repo.finishSheetOpened(7L)
        // The finish is reached first and finds the sheet open, exactly as it does on the phone.
        repo.settleStageForRun(7L)
        // The runner ticks the switch, and the sheet closes behind it.
        whenever(mockDao.getSessionById(7L)).thenReturn(asItEnded.copy(isWalk = true))
        repo.finishSheetClosed(7L)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
        // Asked and answered: the question is closed either way.
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `closing the finish sheet on a Run that cleared the bar graduates the Stage`() = runTest {
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)

        repo.finishSheetOpened(7L)
        repo.settleStageForRun(7L)
        repo.finishSheetClosed(7L)

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions(eq("sub_25_peak"), any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a write that fails on the way out of the sheet still settles the Stage`() = runTest {
        // The sheet's writes are the last thing between STOP and the settlement, and they are the
        // one step here that can throw. A throw that carried the close away with it would leave the
        // gate naming this Run: the finish has already declined it and the launch pass has already
        // run for this process, so nothing left would settle it until the process was killed (#297).
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)

        repo.finishSheetOpened(7L)
        repo.settleStageForRun(7L)
        repo.finishSheetAnswered(7L) { throw IllegalStateException("the Save could not be written") }

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions(eq("sub_25_peak"), any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a Walk ticked into a Save whose other writes fail still graduates nothing`() = runTest {
        // The Walk is not one of the answer's writes, it is the word the settlement reads (#297).
        // When it shared a block with the effort and the stated distance, an effort that threw took
        // it down too and the Stage was then judged on the `isWalk = false` still on the row — a
        // graduation on a walk, and a graduation cannot be taken back.
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        val asItEnded = aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
        assertFalse("the row at STOP carries no mark", asItEnded.isWalk)

        repo.finishSheetOpened(7L)
        repo.settleStageForRun(7L)
        repo.finishSheetAnswered(7L, markedAsWalk = true) {
            throw IllegalStateException("the effort could not be written")
        }

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
        // Still asked and answered, so the gate is closed and no launch pass owes this Run.
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a Walk the mark itself could not store still graduates nothing`() = runTest {
        // The word beats the column, because the column is a write and a write can fail (#297).
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
        whenever(mockDao.setIsWalk(any(), any()))
            .thenThrow(IllegalStateException("the mark could not be written"))

        repo.finishSheetOpened(7L)
        repo.settleStageForRun(7L)
        repo.finishSheetAnswered(7L, markedAsWalk = true) {}

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a close that fails does not escape the runner's answer`() = runTest {
        // The answer runs on the process-wide scope, whose SupervisorJob stops one child's failure
        // reaching its siblings but does not handle it — so a throw out of the close reaches the
        // default handler and takes the app down (#297). Nothing the runner's answer does may cost
        // them the app; the settlement is left to the next launch's pass instead.
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
        whenever(mockDao.setStageSettled(7L))
            .thenThrow(IllegalStateException("the settlement could not be marked"))

        repo.finishSheetOpened(7L)
        repo.finishSheetAnswered(7L) {}

        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a settlement reaching the Run while the sheet's word is on its way judges nothing`() = runTest {
        // The gate says a word about this Run is still coming, so it may not read as open until the
        // settlement carrying that word owns the lock (#297). Opened first and settled after, the
        // sheet's wait for the finalize is a window the finish's own settlement can arrive in — and
        // it carries no word, so it judges the Run off the `isWalk = false` on the row and graduates
        // a Stage that cannot be taken back. The mark here is one that could not be stored, which is
        // exactly when the row and the runner disagree.
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        val asItEnded = aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
        assertFalse("the row carries no mark, because the mark's write failed", asItEnded.isWalk)
        // Still being written when the sheet goes, finished a step later — so the sheet waits.
        whenever(mockDao.getSessionById(7L)).thenReturn(asItEnded.copy(endTime = 0L), asItEnded)

        repo.finishSheetOpened(7L)
        val sheet = launch { repo.finishSheetClosed(7L, markedAsWalk = true, finalizeWaitStepMillis = 1L) }
        // The sheet is now inside its wait, its word not yet settled.
        runCurrent()
        // And the service finalizer reaches the same Run, carrying no word. It runs to the end
        // without the clock moving, so the sheet cannot resume underneath it.
        launch { repo.settleStageForRun(7L) }
        runCurrent()

        sheet.join()
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
        verify(mockDao, times(1)).setStageSettled(7L)
    }

    @Test
    fun `a second Run stopping does not settle the first Run's word away`() = runTest {
        // Two Runs can be owing their word at once (#297). The sheet is taken off screen the moment
        // it is answered and its writes and settlement run on afterwards, with START already armed
        // again — so a runner who saves run 1 and immediately starts and stops run 2 has run 2's
        // STOP arrive while run 1's answer is still in flight. Held in one slot, run 2 replaced run
        // 1, and a wordless settlement reaching run 1 then judged it off the `isWalk = false` on its
        // row and graduated a Stage that cannot be taken back.
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        val asItEnded = aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
        assertFalse("the row carries no mark, because the mark's write failed", asItEnded.isWalk)
        // Still being written when the sheet goes, finished a step later — so the sheet waits.
        whenever(mockDao.getSessionById(7L)).thenReturn(asItEnded.copy(endTime = 0L), asItEnded)

        repo.finishSheetOpened(7L)
        val sheet = launch { repo.finishSheetClosed(7L, markedAsWalk = true, finalizeWaitStepMillis = 1L) }
        // Run 1's answer is now inside its wait, its word not yet settled.
        runCurrent()
        // The runner starts and stops a second Run, which raises a sheet of its own.
        repo.finishSheetOpened(8L)
        // And run 1's finalizer reaches it, carrying no word. It runs to the end without the clock
        // moving, so run 1's answer cannot resume underneath it.
        launch { repo.settleStageForRun(7L) }
        runCurrent()

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockDao, never()).setStageSettled(7L)

        // And when run 1's Walk lands, it graduates nothing.
        sheet.join()
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
        verify(mockDao, times(1)).setStageSettled(7L)
    }

    @Test
    fun `the answer's writes land before the Stage is settled`() = runTest {
        // The order is the ticket: a Walk ticked into the sheet has to be in the row before the rule
        // reads it (#297), so the door that closes the gate is the same one that stores the answer.
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        val asItEnded = aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)

        repo.finishSheetOpened(7L)
        repo.settleStageForRun(7L)
        repo.finishSheetAnswered(7L) {
            whenever(mockDao.getSessionById(7L)).thenReturn(asItEnded.copy(isWalk = true))
        }

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a sheet dismissed before the Run has finished waits for it`() = runTest {
        // Dismissing writes nothing, so nothing else on this path makes the wait every other door
        // off the sheet makes. Without it a runner who swipes the sheet away inside the second after
        // STOP finds a Run with no end time, and the Stage holds until the next launch (#297).
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        val run = aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
        // Still being written when the sheet goes, finished a step later.
        whenever(mockDao.getSessionById(7L)).thenReturn(run.copy(endTime = 0L), run)

        repo.finishSheetOpened(7L)
        repo.finishSheetClosed(7L, finalizeWaitStepMillis = 1L)

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions(eq("sub_25_peak"), any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a distance stated into the sheet graduates nothing, however absurd`() = runTest {
        // The wait lets the sheet's stated distance into the judgement of its own Run, which #231
        // froze the row to keep out. It is safe, but not for the reason it looks: a stated distance
        // is a real distance and does place at LONGEST_DISTANCE. What bars it from a graduation is
        // BestEffortRequirement refusing to be written at anything but a fixed distance, so the two
        // records a distance can move are the two no requirement may be written in. Pinned here,
        // because a requirement written in a distance or a duration would silently undo it.
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        // Forty kilometres in 1900 seconds, told to a Run that has claimed no effort at any distance.
        whenever(statedDao.getForSession(7L)).thenReturn(emptyList())
        val absurd = aTreadmillRun(id = 7, seconds = 1_900)
            .copy(distanceKm = 40.0, ranUnderStageId = "sub_30_bridge", stageSettled = false)
        whenever(mockDao.getSessionById(7L)).thenReturn(absurd)

        repo.settleStageForRun(7L)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a Run nobody was shown a finish sheet for is settled at the finish`() = runTest {
        // A STOP from the notification shows no sheet at all, so there is no word still coming and
        // nothing to wait for. Waiting anyway would hold the graduation until the next launch.
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)

        repo.settleStageForRun(7L)

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions(eq("sub_25_peak"), any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a Stage already settled is not settled again`() = runTest {
        // The mark is what stops the launch pass re-judging a Run every time the app opens — and a
        // graduation cannot be taken back, so a second grant is not a harmless repeat.
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        val run = aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
        whenever(mockDao.getSessionById(7L)).thenReturn(run.copy(stageSettled = true))

        repo.settleStageForRun(7L)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockDao, never()).setStageSettled(any())
    }

    @Test
    fun `the Run Type the coach is gated on is read back off the Workout the Run followed`() = runTest {
        // The finish hands it over; a launch paying the debt days later has only the row. Both must
        // reach the same answer, or a Long Run settled at launch would slip past the gate (#176).
        val statedDao: StatedBestEffortDao = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = repositoryForSettling(statedDao, mockCoach)
        // Stage 2's Long run, and a Run well short of the bar so the rule declines and the coach
        // is the only thing left to reach.
        aRunOwingSettlement(id = 7, fiveKSeconds = 2_400, statedDao = statedDao, workoutId = "w2_s1")

        repo.settleStageForRun(7L)

        verify(mockCoach).evaluateProgress(any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a Run that followed no Workout asks the coach nothing`() = runTest {
        val statedDao: StatedBestEffortDao = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = repositoryForSettling(statedDao, mockCoach)
        aRunOwingSettlement(id = 7, fiveKSeconds = 2_400, statedDao = statedDao, workoutId = null)

        repo.settleStageForRun(7L)

        verify(mockCoach, never()).evaluateProgress(any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `the launch pass settles the Run a lost finish left owing`() = runTest {
        // The process dying between STOP and the sheet takes the sheet with it, so nothing is left
        // in this process to close the question. The next launch is what closes it (#297).
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
        whenever(mockDao.getSessionIdsOwingStageSettlement()).thenReturn(listOf(7L))

        repo.settleStagesMissedAtTheFinish()

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions(eq("sub_25_peak"), any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `the mark left by the sheet's settlement is folded into the Downloads snapshot`() = runTest {
        // Every snapshot this Run has behind it was taken before the mark: the after-run work is
        // booked at the finish and the feel sheet writes its own. Without a refresh here the copy
        // in Downloads always says the Run has not been judged, so a Clear-storage restore would
        // spend another Gemini call on it and overwrite its debrief and Prescription (#297).
        var snapshots = 0
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao, refreshHistoryBackup = { snapshots++ })
        val run = aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)

        repo.settleStageForRun(7L)
        assertEquals("the settlement owes a snapshot", 1, snapshots)

        // And nothing moved the second time, so nothing is owed: a Run already carrying the mark is
        // not worth a copy of the whole database.
        whenever(mockDao.getSessionById(7L)).thenReturn(run.copy(stageSettled = true))
        repo.settleStageForRun(7L)
        assertEquals("a Run already settled owes nothing", 1, snapshots)
    }

    @Test
    fun `the launch pass takes one snapshot for the whole pass`() = runTest {
        // The pass walks every Run the finish missed, and the snapshot is a copy of the whole
        // database: one for the pass, not one per Run (#297) — the rule the rescue pass keeps.
        var snapshots = 0
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao, refreshHistoryBackup = { snapshots++ })
        // Short of the bar, so the rule declines all three and only the mark is written.
        listOf(7L, 8L, 9L).forEach { aRunOwingSettlement(id = it, fiveKSeconds = 2_400, statedDao = statedDao) }
        whenever(mockDao.getSessionIdsOwingStageSettlement()).thenReturn(listOf(7L, 8L, 9L))

        repo.settleStagesMissedAtTheFinish()

        verify(mockDao).setStageSettled(7L)
        verify(mockDao).setStageSettled(8L)
        verify(mockDao).setStageSettled(9L)
        assertEquals("three Runs settled, one snapshot", 1, snapshots)
    }

    @Test
    fun `a launch pass that settled nothing takes no snapshot`() = runTest {
        // The finish can close a question the pass is already holding a list of. Nothing moved, so
        // a launch should not pay for a copy of the whole database (#297).
        var snapshots = 0
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao, refreshHistoryBackup = { snapshots++ })
        val run = aRunOwingSettlement(id = 7, fiveKSeconds = 2_400, statedDao = statedDao)
        whenever(mockDao.getSessionById(7L)).thenReturn(run.copy(stageSettled = true))
        whenever(mockDao.getSessionIdsOwingStageSettlement()).thenReturn(listOf(7L))

        repo.settleStagesMissedAtTheFinish()

        verify(mockDao, never()).setStageSettled(any())
        assertEquals("nothing settled, nothing copied", 0, snapshots)
    }

    @Test
    fun `a Run recorded under a Stage the runner has left settles without granting`() = runTest {
        // The debt is still paid, so the pass does not carry it forever — but the Run's evidence
        // belongs to the Stage it was run under, and that Stage is behind the runner now (#234).
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao, activeStageId = "sub_25_peak")
        aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)

        repo.settleStageForRun(7L)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockDao).setStageSettled(7L)
    }

    @Test
    fun `a Run still being recorded is not settled, and keeps the debt`() = runTest {
        val statedDao: StatedBestEffortDao = mock()
        val repo = repositoryForSettling(statedDao)
        val run = aRunOwingSettlement(id = 7, fiveKSeconds = 1_500, statedDao = statedDao)
        whenever(mockDao.getSessionById(7L)).thenReturn(run.copy(endTime = 0L))

        repo.settleStageForRun(7L)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockDao, never()).setStageSettled(any())
    }

    @Test
    fun `a claim stated before the Run is settled does not jump the settlement`() = runTest {
        // A stated Best Effort re-asks the rule (ADR 0016) — but not before the Run has been put to
        // the Plan at all. The settlement waiting on the finish sheet reads every claim the Run
        // holds when it comes, so asking here first would grant before the runner had said whether
        // the Run was a walk (#297).
        val run = aTreadmillRun(id = 42, seconds = 1_900)
            .copy(distanceKm = 5.0, ranUnderStageId = "sub_30_bridge", stageSettled = false)
        whenever(mockDao.getSessionById(42L)).thenReturn(run)
        val claim = StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_5K, seconds = 1_700)
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(emptyList(), listOf(claim))
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
        )
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repo.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 1_700)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
    }

    // --- The card is told when a Test is due (#292) --------------------------------------------

    /** The Test's last outing, [daysAgo] days back — read the same way the runner counts days. */
    private fun daysAgo(daysAgo: Long): Long =
        System.currentTimeMillis() - daysAgo * ONE_DAY_MILLIS

    /** Both Tests of the 5K plan, which is what history is asked about. */
    private val planTests = TrainingPlanProvider.getPlanById("5k_sub_25")!!.tests

    /** A Run of [planTests]' first Test that ran the whole of it, however many days back. */
    private fun aTestRun(
        startedAt: Long,
        durationSeconds: Int = 1_800,
        distanceKm: Double = 0.0,
    ) = TestRunProjection(
        startTime = startedAt,
        durationSeconds = durationSeconds,
        distanceKm = distanceKm,
        ranUnderWorkoutId = "w2_s3",
    )

    private fun dueFlow(
        lastTestStart: Long?,
        scored: List<ScoredRunProjection> = emptyList(),
    ): SessionRepository = dueFlow(
        testRuns = lastTestStart?.let { listOf(aTestRun(it)) }.orEmpty(),
        scored = scored,
    )

    private fun dueFlow(
        testRuns: List<TestRunProjection>,
        scored: List<ScoredRunProjection> = emptyList(),
    ): SessionRepository {
        whenever(mockDao.getCompletedRunsOfWorkouts(any())).thenReturn(flowOf(testRuns))
        whenever(mockDao.getScoredRunsFlow()).thenReturn(flowOf(scored))
        return repository
    }

    @Test
    fun `a plan offering no Test never asks history anything`() = runTest {
        assertFalse(repository.testDueFlow(planTests = emptyList()).first())
        verify(mockDao, never()).getCompletedRunsOfWorkouts(any())
    }

    @Test
    fun `a Test never run is due`() = runTest {
        assertTrue(dueFlow(lastTestStart = null).testDueFlow(planTests).first())
    }

    @Test
    fun `a Test three weeks old is due and a fortnight-old one is not`() = runTest {
        assertTrue(dueFlow(lastTestStart = daysAgo(22)).testDueFlow(planTests).first())
        assertFalse(dueFlow(lastTestStart = daysAgo(14)).testDueFlow(planTests).first())
    }

    @Test
    fun `the Test that graduated a Stage still counts as the last one`() = runTest {
        // The Stage the runner has just left offered the Test they ran to leave it, and the Stage
        // they land in offers a different one. Asked of the new Stage's Workout alone, history
        // would hold nothing and the card would ask for another Test the same afternoon.
        val repo = dueFlow(lastTestStart = daysAgo(1))

        assertFalse(repo.testDueFlow(planTests).first())
        argumentCaptor<List<String>>().apply {
            verify(mockDao).getCompletedRunsOfWorkouts(capture())
            assertEquals(planTests.map { it.workout.id }, firstValue)
        }
    }

    @Test
    fun `a Test abandoned two minutes in was not a Test`() = runTest {
        // START on the 30-minute Test and STOP straight after. Counted as a Test, that costs the
        // runner three weeks of prompting for a Test nobody ran (Codex P2).
        val repo = dueFlow(testRuns = listOf(aTestRun(daysAgo(1), durationSeconds = 121)))

        assertTrue(repo.testDueFlow(planTests).first())
    }

    @Test
    fun `a 5K run faster than the Workout is scheduled for is still a Test`() = runTest {
        // Stage 2 schedules thirty minutes to test a bar of *under* thirty, so the runner who
        // passes it stops before the clock does. Judged on the clock alone, the better the Run the
        // less it counted — and the runner it graduates would be asked for another the same
        // afternoon (Codex P2).
        val passed = aTestRun(daysAgo(1), durationSeconds = 1_560, distanceKm = 5.01)

        assertFalse(dueFlow(testRuns = listOf(passed)).testDueFlow(planTests).first())
    }

    @Test
    fun `a Run that stopped short of the distance is judged on the clock`() = runTest {
        // Three kilometres in twenty-six minutes is neither the distance nor most of the Workout.
        val abandoned = aTestRun(daysAgo(1), durationSeconds = 1_560, distanceKm = 3.0)

        assertTrue(dueFlow(testRuns = listOf(abandoned)).testDueFlow(planTests).first())
    }

    @Test
    fun `a Test stopped a few seconds early was still a Test`() = runTest {
        // The Run ends when STOP is pressed, so the last tenth is given away rather than argued
        // over: 27 minutes of the 30-minute Test is a Test that was run.
        val repo = dueFlow(testRuns = listOf(aTestRun(daysAgo(1), durationSeconds = 1_620)))

        assertFalse(repo.testDueFlow(planTests).first())
    }

    @Test
    fun `the last Test is the last one actually run, not the last one started`() = runTest {
        // Yesterday's attempt was abandoned; the one before it three weeks ago was run in full. The
        // abandoned Run must not be allowed to date the prompt from yesterday.
        val repo = dueFlow(
            testRuns = listOf(
                aTestRun(daysAgo(1), durationSeconds = 121),
                aTestRun(daysAgo(21), durationSeconds = 1_800),
            )
        )

        assertTrue(repo.testDueFlow(planTests).first())
    }

    @Test
    fun `each Test is measured against its own length`() = runTest {
        // 22:30 is three quarters of Stage 2's Test and the whole of nine tenths of Stage 3's, so
        // the same Run is a Test under one Workout id and an abandoned one under the other.
        val peakTest = aTestRun(daysAgo(1), durationSeconds = 1_350).copy(ranUnderWorkoutId = "w3_s2")

        assertFalse(dueFlow(testRuns = listOf(peakTest)).testDueFlow(planTests).first())
        assertTrue(
            dueFlow(testRuns = listOf(peakTest.copy(ranUnderWorkoutId = "w2_s3")))
                .testDueFlow(planTests).first()
        )
    }

    @Test
    fun `midnight makes a Test due with no Run recorded to prompt it`() = runTest {
        // Both the flow's other inputs are database reads, so nothing moves them on a phone that is
        // pocketed and not run with. Eleven o'clock on the twentieth night: due tomorrow, and
        // "tomorrow" has to arrive on its own (Codex P2).
        val zone = ZoneId.of("Europe/London")
        val startedAt = LocalDate.of(2026, 8, 14).atTime(23, 0).atZone(zone).toInstant()
        val clock = object : Clock() {
            override fun instant(): Instant = startedAt.plusMillis(testScheduler.currentTime)
            override fun getZone(): ZoneId = zone
            override fun withZone(overridden: ZoneId): Clock = this
        }
        val repo = dueFlow(lastTestStart = startedAt.minus(20, ChronoUnit.DAYS).toEpochMilli())

        val answers = mutableListOf<Boolean>()
        val collecting = launch { repo.testDueFlow(planTests, { zone }, clock).toList(answers) }
        runCurrent()
        assertEquals(listOf(false), answers)

        advanceTimeBy(ONE_HOUR_MILLIS + 1)
        runCurrent()
        assertEquals(listOf(false, true), answers)

        collecting.cancel()
    }

    @Test
    fun `the zone is read at each answer, not held from when the card opened`() = runTest {
        // The runner flies London to Auckland with the Today card in front of them. At this one
        // instant it is still the 15th in London and already the 16th in Auckland, and the Test was
        // last run on the 26th at an hour that is the 26th in both places — so it comes due on the
        // 16th, and only the zone decides whether that day has arrived (#299).
        val london = ZoneId.of("Europe/London")
        val auckland = ZoneId.of("Pacific/Auckland")
        val now = LocalDate.of(2026, 8, 15).atTime(20, 0).atZone(london).toInstant()
        val lastTest = LocalDate.of(2026, 7, 26).atTime(9, 0).atZone(london).toInstant()
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        var here = london
        // Shared rather than state, so the second read is made even though nothing about history
        // changed — it is the zone under the answer that moved, not the Runs.
        val scored = MutableSharedFlow<List<ScoredRunProjection>>(replay = 1)
        scored.tryEmit(emptyList())
        whenever(mockDao.getCompletedRunsOfWorkouts(any()))
            .thenReturn(flowOf(listOf(aTestRun(lastTest.toEpochMilli()))))
        whenever(mockDao.getScoredRunsFlow()).thenReturn(scored)

        val answers = mutableListOf<Boolean>()
        val collecting = launch { repository.testDueFlow(planTests, { here }, clock).toList(answers) }
        runCurrent()
        assertEquals(listOf(false), answers)

        here = auckland
        scored.tryEmit(emptyList())
        runCurrent()
        assertEquals(listOf(false, true), answers)

        collecting.cancel()
    }

    @Test
    fun `a fatigued runner is held, however long it has been`() = runTest {
        // A week of hard Runs finishing yesterday, which is what leaves Form below −10 — the same
        // read the Progress screen and the coach make.
        val hardWeek = (1L..7L).map { day ->
            ScoredRunProjection(startTime = daysAgo(day), effortScore = 100)
        }

        val repo = dueFlow(lastTestStart = daysAgo(90), scored = hardWeek)

        assertFalse(repo.testDueFlow(planTests).first())
    }

    // --- A bar already beaten in history is said out loud (#293) --------------------------------

    /** A repository that reads the record book [book] and the settings it is handed. */
    private fun repositoryReading(
        book: AchievementDao,
        settings: UserSettings = UserSettings(),
    ): SessionRepository {
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(flowOf(settings))
        return SessionRepository(
            sessionDao = mockDao,
            achievementDao = book,
            settingsRepository = mockSettingsRepo,
        )
    }

    @Test
    fun `a Stage whose requirement is a judgement asks the record book nothing`() = runTest {
        val bookDao: AchievementDao = mock()

        assertNull(repositoryReading(bookDao).bestInHistoryFlow(requirement = null).first())
        verifyNoInteractions(bookDao)
    }

    @Test
    fun `the bar already beaten is the quickest in the book at the requirement's distance`() =
        runTest {
            val bookDao: AchievementDao = mock()
            val quickest =
                HistoryBestEffort(seconds = 1_661.0, runStartedAtMillis = 1_781_434_800_000L)
            whenever(bookDao.getQuickestInHistoryFlow(RecordType.FASTEST_5K))
                .thenReturn(flowOf(quickest))

            val best = repositoryReading(bookDao).bestInHistoryFlow(
                BestEffortRequirement(RecordType.FASTEST_5K, 1_799)
            ).first()

            assertEquals(quickest, best)
            verify(bookDao).getQuickestInHistoryFlow(RecordType.FASTEST_5K)
        }

    @Test
    fun `naming an already-beaten bar writes nothing`() = runTest {
        val bookDao: AchievementDao = mock()
        whenever(bookDao.getQuickestInHistoryFlow(any())).thenReturn(
            flowOf(HistoryBestEffort(seconds = 1_200.0, runStartedAtMillis = 1_781_434_800_000L))
        )

        repositoryReading(bookDao)
            .bestInHistoryFlow(BestEffortRequirement(RecordType.FASTEST_5K, 1_799))
            .first()

        // Forwards only (ADR 0016): the card says the bar was beaten and the app grants nothing on
        // the strength of it — no Stage advanced, no message written, nothing.
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `nothing is said while testing mode is on`() = runTest {
        val bookDao: AchievementDao = mock()
        whenever(bookDao.getQuickestInHistoryFlow(any())).thenReturn(
            flowOf(HistoryBestEffort(seconds = 1_200.0, runStartedAtMillis = 1_781_434_800_000L))
        )

        // "Run one now and it counts" is the one promise the rule will not keep under testing mode,
        // which refuses to grant at all — so the card says nothing rather than something untrue.
        val best = repositoryReading(bookDao, UserSettings(testingModeEnabled = true))
            .bestInHistoryFlow(BestEffortRequirement(RecordType.FASTEST_5K, 1_799))
            .first()

        assertNull(best)
    }

    @Test
    fun `the bar is read over all of history, Open Runs included and Walks never`() = runTest {
        // End to end through the record book the app actually writes, because the three edges the
        // card has to get right are the book's and not this rule's: an Open Run counts, a Walk is
        // worth nothing at all, and a treadmill claim stands like a measured effort.
        val statedDao: StatedBestEffortDao = mock()
        val openRun = aRunTold(id = 1, fiveKSeconds = 1_661, statedDao = statedDao)
            .copy(isRunWalkMode = false, startTime = 1_781_434_800_000L)
        val walk = aRunTold(id = 2, fiveKSeconds = 1_200, statedDao = statedDao, isWalk = true)
        whenever(mockDao.getAllSessions()).thenReturn(listOf(openRun, walk))
        whenever(statedDao.getAll()).thenReturn(
            listOf(
                StatedBestEffort(sessionId = 1, type = RecordType.FASTEST_5K, seconds = 1_661),
                StatedBestEffort(sessionId = 2, type = RecordType.FASTEST_5K, seconds = 1_200),
            )
        )
        val book = BookInMemory()
        val repo = SessionRepository(
            sessionDao = mockDao,
            achievementDao = book,
            statedBestEffortDao = statedDao,
            settingsRepository = mockSettingsRepo,
        )
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = false)))

        repo.seedRecordsFromHistory()
        val best = repo.bestInHistoryFlow(
            BestEffortRequirement(RecordType.FASTEST_5K, 1_799)
        ).first()

        // The Walk's 20:00 would be the quickest thing in history if a Walk held a Best Effort at
        // all. It does not, so the bar the card names is the Open Run's 27:41.
        assertEquals(1_661.0, best?.seconds)
    }

    // --- A failed Test states the gap and changes nothing else (#292) --------------------------

    @Test
    fun `a Test that misses the bar is told how far off it was`() = runTest {
        val statedDao: StatedBestEffortDao = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
            aiCoachClient = mockCoach,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_25_peak"))
        )
        stubTheCoachsReads()
        // 27:41 against stage 3's bar of 24:59, run as the Stage's own Test.
        val run = aRunTold(id = 7, fiveKSeconds = 1_661, statedDao = statedDao)
            .copy(ranUnderWorkoutId = "w3_s2")

        repo.settleStageAfterRun("sub_25_peak", RunType.QUALITY, run)

        verify(mockSettingsRepo).setLatestDebrief(
            "You ran 5 km in 27:41. 2:42 off the bar.",
            DebriefAuthor.APP,
            CoachWriteScope("5k_sub_25", "sub_25_peak")
        )
        // It reaches into nothing else: no graduation, and the coach is never asked about a
        // Quality Run at all (ADR 0006).
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockCoach, never()).evaluateProgress(any())
    }

    @Test
    fun `an ordinary Run short of the bar is told nothing`() = runTest {
        // Any Run can hold a Best Effort short of the requirement. Only a Test was an attempt, and
        // "2:42 off the bar" after an easy Tuesday is a verdict on a run nobody offered.
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_25_peak"))
        )
        stubTheCoachsReads()
        val run = aRunTold(id = 7, fiveKSeconds = 1_661, statedDao = statedDao)
            .copy(ranUnderWorkoutId = "w3_s1")

        repo.settleStageAfterRun("sub_25_peak", runType = null, finalizedRun = run)

        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a Test that clears the bar is congratulated and not measured against it`() = runTest {
        val statedDao: StatedBestEffortDao = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_25_peak"))
        )
        stubTheCoachsReads()
        val run = aRunTold(id = 7, fiveKSeconds = 1_463, statedDao = statedDao)
            .copy(ranUnderWorkoutId = "w3_s2")

        repo.settleStageAfterRun("sub_25_peak", RunType.QUALITY, run)

        // Stage 3 is the last of the plan, so the congratulation travels with the completion it is
        // about rather than on its own (#294).
        verify(mockSettingsRepo).completePlan(
            any(),
            eq(
                "You ran 5 km in 24:23. Stage 3: Sub-25 Peak complete. " +
                    "That's the whole plan: 5K to Sub-25 Progressive Plan, done."
            ),
            eq(CoachWriteScope("5k_sub_25", "sub_25_peak"))
        )
    }

    @Test
    fun `a Test with no 5K to read says nothing at all`() = runTest {
        // A treadmill Test whose console was never typed in holds no Best Effort, so there is no
        // number to state and nothing to be off the bar by.
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(7L)).thenReturn(emptyList())
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            statedBestEffortDao = statedDao,
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_25_peak"))
        )
        stubTheCoachsReads()
        val run = aTreadmillRun(id = 7, seconds = 1_900).copy(ranUnderWorkoutId = "w3_s2")

        repo.settleStageAfterRun("sub_25_peak", RunType.QUALITY, run)

        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a Best Effort stated after the Run graduates the Stage too`() = runTest {
        // A treadmill 5K is read off the console once the Run has ended, so a rule that only ever
        // looked at the finish would accept a measured 5K and silently refuse a stated one — the
        // app disagreeing with ADR 0015 (#290).
        //
        // Its Stage is settled, which is what "after the Run" means: the claim is being typed on the
        // Run's own page, long after the finish sheet closed and the Run was put to the Plan (#297).
        val run = aTreadmillRun(id = 42, seconds = 1_900)
            .copy(distanceKm = 5.0, ranUnderStageId = "sub_30_bridge", stageSettled = true)
        whenever(mockDao.getSessionById(42L)).thenReturn(run)
        val claim = StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_5K, seconds = 1_700)
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(emptyList(), listOf(claim))
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
        )
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repo.stateBestEffort(42L, RecordType.FASTEST_5K, seconds = 1_700)

        verify(mockSettingsRepo).advanceStageAndClearPrescriptions(eq("sub_25_peak"), any())
    }

    @Test
    fun `a Mile stated today does not cash in a 5K the Run has held all along`() = runTest {
        // The Run already holds a qualifying 5K — stated before any of this existed, and so never
        // asked of the rule. An unrelated claim typed today must not graduate the Stage off it:
        // that is the pass over history the rule refuses to make (#290).
        // Settled, so the refusal below is the rule declining an unrelated claim and not the
        // settlement this Run is still owed declining to be jumped (#297).
        val run = aTreadmillRun(id = 42, seconds = 1_900)
            .copy(distanceKm = 5.0, ranUnderStageId = "sub_30_bridge", stageSettled = true)
        whenever(mockDao.getSessionById(42L)).thenReturn(run)
        val oldFiveK = StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_5K, seconds = 1_700)
        val newMile = StatedBestEffort(sessionId = 42, type = RecordType.FASTEST_MILE, seconds = 500)
        val statedDao: StatedBestEffortDao = mock()
        whenever(statedDao.getForSession(42L)).thenReturn(
            listOf(oldFiveK),
            listOf(oldFiveK, newMile)
        )
        val mockAchievementDao: AchievementDao = mock()
        whenever(mockAchievementDao.getAllAchievements()).thenReturn(emptyList())
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
        )
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            achievementDao = mockAchievementDao,
            statedBestEffortDao = statedDao,
        )

        repo.stateBestEffort(42L, RecordType.FASTEST_MILE, seconds = 500)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a Stage with no Run of its own is not graduated, whatever the coach says`() = runTest {
        // The coach is told in as many words that an empty list is no evidence, but a graduation
        // cannot be taken back, so the place that acts on one refuses it rather than trusting the
        // telling (#234). What the coach said still reaches the runner.
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = null,
                graduatedToNextStage = true,
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        // Refused, so this is an ordinary evaluation: the next Long Run is prescribed as one, and
        // what the coach said reaches the runner beside those numbers (#156).
        verify(mockPrescriptions).prescribe(
            any(),
            any(),
            eq("Stage complete."),
            any(),
            eq(CoachWriteScope("5k_sub_25", "base_builder"))
        )
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a Stage whose only Runs are Walks is not graduated either`() = runTest {
        // The prompt says a Walk is never evidence for a requirement, and a sentence in a prompt is
        // a promise the code has to keep — so the one place a graduation is acted on refuses one
        // resting on Walks alone, exactly as it refuses one resting on nothing (#275).
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
        // Three Runs recorded under the Stage, every one of them marked a Walk afterwards — the
        // post-lifting week the ticket is about.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            (1L..3L).map {
                aTreadmillRun(id = it, seconds = 1_800).copy(isWalk = true, isRunWalkMode = true)
            }
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = null,
                graduatedToNextStage = true,
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a Stage whose last three are Walks plus an Open Run is not graduated either`() = runTest {
        // The prompt makes the same promise about an unplanned Open Run as it does about a Walk:
        // neither progresses a Stage, because neither completed the structure the Stage asks for.
        // A Walk beside an Open Run leaves the evidence list empty, so the refusal stands (#275).
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
        // Two Walks and one open-ended jog recorded under the Stage — the week that looks busy and
        // answers nothing.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_800).copy(isWalk = true, isRunWalkMode = true),
                aTreadmillRun(id = 2, seconds = 1_800).copy(isWalk = true),
                aTreadmillRun(id = 3, seconds = 1_800),
            )
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = null,
                graduatedToNextStage = true,
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a graduation read off a Walk is refused though a real Run was shown beside it`() = runTest {
        // The hole #287 was written for. The existence of a Run that *could* answer the Stage is not
        // a link to the Run the graduation actually rests on: shown one old structured Run that
        // plainly failed the requirement and a two-hour Walk, the coach can read the requirement as
        // met from the Walk's numbers, and a guard that only asks "was there a qualifying Run"
        // waves it through — one eligible Run switching the check off for everything beside it.
        //
        // So the coach names the Run it graduated on, and the name has to be one of the Runs the app
        // agrees could answer the Stage. Naming the Walk refuses itself.
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 600)
                    .copy(isRunWalkMode = true, startTime = 1_000_000L),
                aTreadmillRun(id = 2, seconds = 7_200)
                    .copy(isWalk = true, startTime = 2_000_000L),
            )
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = null,
                graduatedToNextStage = true,
                // The two hours of walking, named as the thing that met the requirement.
                graduationEvidenceRunTimestamps = listOf(2_000_000L),
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a requirement no single Run can meet is graduated on the Runs that together met it`() = runTest {
        // The first stage of the beginner plan asks for "4 weeks of consistent Zone 2 training", and
        // no single Run has ever met that or ever could. A rule demanding the coach name exactly one
        // Run would have left this stage — the one Chris is actually on — impossible to finish: the
        // obedient answer to "name the one run that met it" is "there isn't one", forever.
        //
        // So the evidence is however many Runs it took, and the check is unchanged in kind: every
        // name still has to be a Run the app agrees could answer the Stage.
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 1_000_000L),
                aTreadmillRun(id = 2, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 2_000_000L),
                aTreadmillRun(id = 3, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 3_000_000L),
            )
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = null,
                graduatedToNextStage = true,
                // The consistency, named run by run.
                graduationEvidenceRunTimestamps = listOf(1_000_000L, 2_000_000L, 3_000_000L),
                coachMessage = "Four consistent weeks. Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        val activeScope = CoachWriteScope("5k_sub_25", "base_builder")
        verify(mockSettingsRepo).advanceStageAndClearPrescriptions("sub_30_bridge", activeScope)
        verify(mockSettingsRepo).setLatestDebrief(
            "Four consistent weeks. Stage complete.",
            // The coach's own words about a requirement that is a judgement — its name goes on it.
            DebriefAuthor.COACH,
            activeScope
        )
    }

    @Test
    fun `one Walk among the Runs named refuses the whole graduation`() = runTest {
        // Naming several Runs does not loosen anything, which is the thing to be sure of before
        // allowing several at all: a graduation resting on two Runs and a Walk is a graduation
        // resting on a Walk. Keeping the names that resolved and dropping the one that did not
        // would grant it on less evidence than the coach itself thought it needed — the same
        // substitution #287 refuses, read from the other end.
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 1_000_000L),
                aTreadmillRun(id = 2, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 2_000_000L),
                aTreadmillRun(id = 3, seconds = 7_200)
                    .copy(isWalk = true, startTime = 3_000_000L),
            )
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = null,
                graduatedToNextStage = true,
                // Two real Runs and the two hours of walking, counted as one consistent stretch.
                graduationEvidenceRunTimestamps = listOf(1_000_000L, 2_000_000L, 3_000_000L),
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a graduation that names no Run at all is refused`() = runTest {
        // A Stage with a perfectly good structured Run under it, and a coach that said "graduated"
        // without saying what on. Nothing to check, so nothing is granted (#287): the reply is
        // treated as an ordinary evaluation, which is the same ending every other refusal has.
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 1_000_000L)
            )
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = null,
                graduatedToNextStage = true,
                graduationEvidenceRunTimestamps = null,
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockPrescriptions).prescribe(
            any(),
            any(),
            eq("Stage complete."),
            any(),
            eq(CoachWriteScope("5k_sub_25", "base_builder"))
        )
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a graduation naming a Run that was never shown is refused`() = runTest {
        // A timestamp that matches nothing — a model that invented one, or reworked the number it
        // was given. There is no Run behind it to check the Stage against, so it is worth no more
        // than naming nothing at all (#287).
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 1_000_000L)
            )
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = null,
                graduatedToNextStage = true,
                graduationEvidenceRunTimestamps = listOf(999_999L),
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `two Runs starting at the same instant name each other, so neither can be the evidence`() = runTest {
        // A timestamp is a name only while it points at one Run. Two Runs sharing one — which no
        // clock this app reads should ever produce — leave the coach's answer ambiguous, and an
        // ambiguous name is not a name: refused, in the direction every other doubt here is settled
        // in, because a graduation cannot be taken back (#287).
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 1_000_000L),
                aTreadmillRun(id = 2, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 1_000_000L),
            )
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        whenever(mockCoach.evaluateProgress(any())).thenReturn(
            AiCoachResponse(
                nextRunDurationSeconds = 360,
                nextWalkDurationSeconds = 60,
                nextRepeats = 5,
                nextTargetZone = null,
                graduatedToNextStage = true,
                graduationEvidenceRunTimestamps = listOf(1_000_000L),
                coachMessage = "Stage complete."
            )
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
    }

    @Test
    fun `a run deleted while the coach was thinking has its graduation refused whole`() = runTest {
        // The third ending of an evaluation, refused for the reason the other two are (#156) — and
        // the one that matters most, because it is the one nothing can take back. A Prescription
        // records the Runs it stood on and a later delete unwinds it; a graduation records nothing
        // and only writes forward, so a Stage granted on evidence that has gone stays granted.
        //
        // One of the two Runs the coach was shown leaves history during the round trip, which on a
        // requirement answered by a single Run or a pair of them can be the whole basis. Refused on
        // the partial delete, which errs towards graduating late rather than twice.
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 1_000_000L),
                aTreadmillRun(id = 2, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 2_000_000L),
            )
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        mockCoach.stub {
            onBlocking { evaluateProgress(any()) }.doSuspendableAnswer {
                // Run 2 leaves history while the coach is still thinking about it.
                mockDao.stub {
                    onBlocking { getAiEligibleIdsIn(any()) }
                        .thenAnswer { asked -> asked.getArgument<List<Long>>(0).filter { it != 2L } }
                }
                AiCoachResponse(
                    nextRunDurationSeconds = 360,
                    nextWalkDurationSeconds = 60,
                    nextRepeats = 5,
                    nextTargetZone = 3,
                    graduatedToNextStage = true,
                    // The Run that is about to leave history, named as the evidence — so what is
                    // being tested is the delete, not a graduation that named nothing (#287).
                    graduationEvidenceRunTimestamps = listOf(2_000_000L),
                    coachMessage = "Stage complete."
                )
            }
        }

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        // A refusal, not an evaluation that never happened: the coach was asked, said the Stage was
        // finished, and history was asked a second time about the Runs that finished it.
        verify(mockCoach).evaluateProgress(any())
        verify(mockDao).getAiEligibleIdsIn(listOf(1L, 2L))
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(anyOrNull(), any())
        // The message goes with it: "you have finished this stage" is not true if the Run that
        // finished it has gone, and left behind it would stand about a Stage the runner is still in
        // with nothing under it and nothing to take it back.
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
        // Nor is the graduation quietly downgraded to a Prescription: the whole evaluation is
        // refused, not the graduation alone.
        verify(mockPrescriptions, never()).prescribe(any(), any(), any(), any(), any())
    }

    @Test
    fun `a delete cannot land between the coach's last look at history and its graduation`() = runTest {
        // What the lock is for on this ending, and the one thing a second read on its own cannot do
        // (#156). The evaluation asks history again after the round trip and is told both Runs are
        // there — true when the query ran. A delete landing after that answer and before the write
        // takes back whatever stood, and the graduation lands behind it on a Run the delete has
        // already removed: a Stage advanced on evidence nobody has, which nothing writes backwards.
        //
        // So the proof is the order the writes land in, not their contents. Under the lock the
        // graduation is written and *then* the delete runs; without it the delete gets all the way
        // through first and the graduation is written over the top of it.
        val testScope = this
        val order = mutableListOf<String>()
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = mockPrescriptions,
            aiCoachClient = mockCoach
        )
        mockSettingsRepo.stub {
            onBlocking { setLatestDebrief(any(), any(), any()) }.doSuspendableAnswer { order += "message" }
            onBlocking { advanceStageAndClearPrescriptions(anyOrNull(), any()) }
                .doSuspendableAnswer { order += "advance" }
        }
        mockPrescriptions.stub {
            onBlocking { forgetWorkFedBy(any()) }.doSuspendableAnswer { order += "take back" }
        }
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "base_builder"))
        )
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 1_000_000L),
                aTreadmillRun(id = 2, seconds = 1_500)
                    .copy(isRunWalkMode = true, startTime = 2_000_000L),
            )
        )
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
                // Run 2, which is one of the two the coach was shown and is the one that leaves
                // history mid-thought — so the refusal under test is the delete's, not #287's.
                graduationEvidenceRunTimestamps = listOf(2_000_000L),
                coachMessage = "Stage complete."
            )
        )
        val goneFromHistory = mutableSetOf<Long>()
        var deleting: Job? = null
        mockDao.stub {
            onBlocking { deleteSessionById(any()) }
                .doSuspendableAnswer { goneFromHistory += it.getArgument<Long>(0) }
            onBlocking { getAiEligibleIdsIn(any()) }.doSuspendableAnswer { asked ->
                // The answer is settled here, while the rows are still there — a query cannot see
                // a delete that has not happened yet, and pretending otherwise would be the test
                // doing the lock's job for it.
                val answer = asked.getArgument<List<Long>>(0).filterNot { it in goneFromHistory }
                if (deleting == null) {
                    // The runner deletes run 2 from the history screen in the instant between the
                    // evaluation's last look at history and its write. Started here rather than
                    // waited on: waiting would deadlock against the very lock under test, whereas
                    // yielding lets the delete take every step it is allowed to take — which,
                    // unlocked, is all of them.
                    deleting = testScope.launch { repo.deleteSession(2L) }
                    repeat(200) { yield() }
                }
                answer
            }
        }

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)
        deleting?.join()

        assertEquals(listOf("message", "advance", "take back"), order)
    }

    @Test
    fun `a Run whose Stage the plan has left is not evaluated at all`() = runTest {
        // The plan moved on while this Run was still going — an earlier Run's evaluation graduated
        // it (#234). The Run is evidence about the Stage it was run under, and a verdict on that
        // Stage could only graduate one the runner has already left or prescribe into a Stage this
        // Run never ran, so the coach is not asked at all.
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = mockPrescriptions,
            aiCoachClient = mockCoach
        )
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "sub_30_bridge"))
        )

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockCoach, never()).evaluateProgress(any())
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(any(), any())
        verify(mockPrescriptions, never()).prescribe(any(), any(), any(), any(), any())
        verify(mockPrescriptions, never()).amendStanding(any(), any(), any())
    }

    @Test
    fun `a Run under a plan that never named its Stage is still evaluated`() = runTest {
        // Storage may hold no Stage at all — activating a plan is the only thing that writes one,
        // and an archive restored without one leaves it empty. The runner is in the plan's first
        // Stage all the same, which is what the Run was stamped with at START (#234). Read as
        // stored on one side and resolved on the other, every Run such a runner records would be
        // thrown away as evidence for a Stage they had left.
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = mockPrescriptions,
            aiCoachClient = mockCoach
        )
        val storedPreferences = mutablePreferencesOf(PreferencesKeys.ACTIVE_PLAN_ID to "5k_sub_25")
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(userSettingsOf(storedPreferences)))
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any()))
            .thenReturn(listOf(aTreadmillRun(id = 1, seconds = 1_500)))
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

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        val activeScope = CoachWriteScope("5k_sub_25", "base_builder")
        verify(mockCoach).evaluateProgress(any())
        verify(mockPrescriptions).prescribe(eq(RunType.LONG), any(), any(), any(), eq(activeScope))
        // And the write is not refused at the door either: the guard re-reads the preference as it
        // stands, which is still the empty one this runner started with.
        assertTrue(
            coachWriteAllowed(
                testingModeEnabled = false,
                activePlanId = storedPreferences[PreferencesKeys.ACTIVE_PLAN_ID],
                activeStageId = storedPreferences[PreferencesKeys.ACTIVE_STAGE_ID],
                scope = activeScope
            )
        )
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
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
        verify(mockPrescriptions, never()).prescribe(any(), any(), any(), any(), any())
        verify(mockPrescriptions, never()).amendStanding(any(), any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
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
        verify(mockPrescriptions, never()).prescribe(any(), any(), any(), any(), any())
        verify(mockPrescriptions, never()).amendStanding(any(), any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
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
        verify(mockPrescriptions, never()).prescribe(any(), any(), any(), any(), any())
        verify(mockPrescriptions, never()).amendStanding(any(), any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
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
        verify(mockPrescriptions, never()).prescribe(any(), any(), any(), any(), any())
        verify(mockPrescriptions, never()).amendStanding(any(), any(), any())
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
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
        verify(mockPrescriptions).prescribe(
            eq(RunType.LONG),
            prescribed.capture(),
            eq("Good session."),
            any(),
            eq(activeScope)
        )
        assertEquals(660, prescribed.firstValue.runDurationSeconds)
        assertEquals(60, prescribed.firstValue.walkDurationSeconds)
        assertEquals(4, prescribed.firstValue.totalRepeats)
        assertEquals(3, prescribed.firstValue.targetZone)
        // The debrief travels with the numbers it explains, in the one write (#156) — so the
        // settings door is not the one it goes through on an ordinary evaluation.
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(anyOrNull(), any())
        verify(mockSettingsRepo, never()).setCoachingEnabled(any())
        verify(mockSettingsRepo, never()).setTargetZone(any())
        verify(mockSettingsRepo, never()).setStatedHeartRates(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a run deleted while the coach was thinking has its reply refused whole`() = runTest {
        // The evidence is read before a network round trip that takes seconds, and the runner can
        // spend them on the history screen (#156). The delete rolls back whatever stood at the
        // time — the coach's *previous* answer — so a reply written afterwards would name a Run
        // nobody has, and no later delete could ever take it back.
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(aTreadmillRun(id = 1, seconds = 1_500), aTreadmillRun(id = 2, seconds = 1_500))
        )
        whenever(mockDao.getMaxSessionLoadLast30Days(any())).thenReturn(
            MaxSessionLoad30dProjection(maxDistanceKm = 0.0, maxDurationSeconds = 0L)
        )
        mockCoach.stub {
            onBlocking { evaluateProgress(any()) }.doSuspendableAnswer {
                // Run 2 leaves history while the coach is still thinking about it.
                mockDao.stub {
                    onBlocking { getAiEligibleIdsIn(any()) }
                        .thenAnswer { asked -> asked.getArgument<List<Long>>(0).filter { it != 2L } }
                }
                AiCoachResponse(
                    nextRunDurationSeconds = 660,
                    nextWalkDurationSeconds = 60,
                    nextRepeats = 4,
                    nextTargetZone = 3,
                    graduatedToNextStage = false,
                    coachMessage = "Good session."
                )
            }
        }

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        // A refusal, not an evaluation that never happened: the coach was asked, the reply came
        // back, and history was asked a second time about the Runs it was reasoned from.
        verify(mockCoach).evaluateProgress(any())
        verify(mockDao).getAiEligibleIdsIn(listOf(1L, 2L))
        // Refused whole rather than stored with one Run of its three struck out: the numbers were
        // reasoned from all three, and the debrief explains numbers that are not being written. The
        // debrief travels inside `prescribe`, so nothing reaching the store at all is the whole of
        // it — text and intervals cannot come apart if neither was written.
        verifyNoInteractions(mockPrescriptions)
    }

    @Test
    fun `a reply about runs that are all still there is stored`() = runTest {
        // The other side of the refusal above: asking history again must not turn every ordinary
        // evaluation into a refused one.
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(aTreadmillRun(id = 1, seconds = 1_500), aTreadmillRun(id = 2, seconds = 1_500))
        )
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

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockPrescriptions).prescribe(
            eq(RunType.LONG),
            any(),
            eq("Good session."),
            eq(setOf(1L, 2L)),
            eq(CoachWriteScope("5k_sub_25", "base_builder"))
        )
    }

    @Test
    fun `a delete cannot land between the coach's last look at history and its write`() = runTest {
        // What the lock is for, and the one thing a second read on its own cannot do (#156).
        //
        // The evaluation asks history again after the round trip and is told all three Runs are
        // there — which was true when the query ran. A delete landing *after* that answer and
        // before the write rolls back whatever stood, and then this write lands behind it naming a
        // Run the delete has already taken out: provenance no later delete can ever answer for,
        // because a Run cannot be deleted twice. The re-read is honest and still too early; only
        // holding the read and the write together keeps the delete out.
        //
        // So the proof is the order the two writes land in, not their contents. Under the lock the
        // Prescription is stored and *then* taken back by the delete queued behind it, and the
        // runner ends up with the rollback the delete promised. Without it the rollback happens
        // first and the Prescription is written over the top of it, standing on run 2 for good.
        val testScope = this
        val order = mutableListOf<String>()
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = SessionRepository(
            sessionDao = mockDao,
            settingsRepository = mockSettingsRepo,
            coachPrescriptionRepository = mockPrescriptions,
            aiCoachClient = mockCoach
        )
        mockPrescriptions.stub {
            onBlocking { prescribe(any(), any(), any(), any(), any()) }
                .doSuspendableAnswer { order += "prescribe" }
            onBlocking { forgetWorkFedBy(any()) }.doSuspendableAnswer { order += "take back" }
        }
        whenever(mockSettingsRepo.userSettingsFlow).thenReturn(
            flowOf(UserSettings(activePlanId = "5k_sub_25", activeStageId = "base_builder"))
        )
        whenever(mockDao.getMostRecentFinalizedSession()).thenReturn(
            RunnerSession(startTime = 0L, isRunWalkMode = true, includeInAiTraining = true)
        )
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(aTreadmillRun(id = 1, seconds = 1_500), aTreadmillRun(id = 2, seconds = 1_500))
        )
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
        val goneFromHistory = mutableSetOf<Long>()
        var deleting: Job? = null
        mockDao.stub {
            onBlocking { deleteSessionById(any()) }
                .doSuspendableAnswer { goneFromHistory += it.getArgument<Long>(0) }
            onBlocking { getAiEligibleIdsIn(any()) }.doSuspendableAnswer { asked ->
                // The answer is settled here, while the rows are still there — a query cannot see
                // a delete that has not happened yet, and pretending otherwise would be the test
                // doing the lock's job for it.
                val answer = asked.getArgument<List<Long>>(0).filterNot { it in goneFromHistory }
                if (deleting == null) {
                    // The runner deletes run 2 from the history screen in the instant between the
                    // evaluation's last look at history and its write. Started here rather than
                    // waited on: waiting would deadlock against the very lock under test, whereas
                    // yielding lets the delete take every step it is allowed to take — which,
                    // unlocked, is all of them.
                    deleting = testScope.launch { repo.deleteSession(2L) }
                    repeat(200) { yield() }
                }
                answer
            }
        }

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)
        deleting?.join()

        assertEquals(listOf("prescribe", "take back"), order)
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
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
        verify(mockPrescriptions).prescribe(eq(RunType.LONG), prescribed.capture(), any(), any(), any())
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
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
        verify(mockPrescriptions).prescribe(eq(RunType.LONG), prescribed.capture(), any(), any(), any())
        assertEquals(600, prescribed.firstValue.runDurationSeconds)
        assertEquals(120, prescribed.firstValue.walkDurationSeconds)
        assertEquals(3, prescribed.firstValue.totalRepeats)
        // The debrief is untouched: the hold is about the intervals, not about what was said, so it
        // is written exactly as the coach said it, beside the held numbers (#156).
        verify(mockPrescriptions).prescribe(
            any(),
            any(),
            eq("Not a week to be adding work to."),
            any(),
            any()
        )
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
        verify(mockPrescriptions).amendStanding(
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
        verify(mockSettingsRepo, never()).setLatestDebrief(any(), any(), any())
        verify(mockSettingsRepo, never()).advanceStageAndClearPrescriptions(anyOrNull(), any())
    }

    @Test
    fun `a run deleted while the coach was unreachable refuses the hold too`() = runTest {
        // The hold is a smaller act than a Prescription and the same act (#248, #156): it says the
        // standing coaching is still the runner's coaching, only quieter — keeping its debrief, its
        // date and the Runs it stood on. Once one of those Runs has left history that is no longer
        // something this evaluation is in a position to say, and the delete has already decided
        // what stands: rolled the slot back to the coach's previous Prescription, or taken it away
        // altogether. Amending anyway would put last week's numbers over whatever the delete left,
        // under a provenance naming Runs those numbers were never reasoned from.
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = fatiguedRunnerEvaluating(mockPrescriptions, mockCoach)
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(aTreadmillRun(id = 1, seconds = 1_500), aTreadmillRun(id = 2, seconds = 1_500))
        )
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
        whenever(mockCoach.evaluateProgress(any())).thenAnswer {
            // Run 2 leaves history while the coach is failing to answer.
            mockDao.stub {
                onBlocking { getAiEligibleIdsIn(any()) }
                    .thenAnswer { asked -> asked.getArgument<List<Long>>(0).filter { it != 2L } }
            }
            null
        }

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)

        verify(mockPrescriptions, never()).amendStanding(any(), any(), any())
        // Refused before what stands was even read, which is what makes it a refusal rather than a
        // hold that happened to find nothing: there is no window here for a delete to land in.
        verify(mockPrescriptions, never()).prescriptionsFlow
    }

    @Test
    fun `a delete cannot land between the hold's look at history and its amend`() = runTest {
        // The hold's half of the same defect. `amendStanding` goes through `editCoachWrite`, which
        // answers for the plan and the stage and nothing else — so a delete landing between the
        // look at history and the write would roll the slot back to the coach's previous
        // Prescription and have the amend put last week's numbers straight over it, under a
        // provenance naming Runs those numbers were never reasoned from. Held together, the amend
        // lands first and the delete takes it back, which is the order the runner is owed.
        val testScope = this
        val order = mutableListOf<String>()
        val mockPrescriptions: CoachPrescriptionRepository = mock()
        val mockCoach: AiCoachClient = mock()
        val repo = fatiguedRunnerEvaluating(mockPrescriptions, mockCoach)
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(
            listOf(aTreadmillRun(id = 1, seconds = 1_500), aTreadmillRun(id = 2, seconds = 1_500))
        )
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
        mockPrescriptions.stub {
            onBlocking { amendStanding(any(), any(), any()) }.doSuspendableAnswer { order += "amend" }
            onBlocking { forgetWorkFedBy(any()) }.doSuspendableAnswer { order += "take back" }
        }
        whenever(mockCoach.evaluateProgress(any())).thenReturn(null)
        val goneFromHistory = mutableSetOf<Long>()
        var deleting: Job? = null
        mockDao.stub {
            onBlocking { deleteSessionById(any()) }
                .doSuspendableAnswer { goneFromHistory += it.getArgument<Long>(0) }
            onBlocking { getAiEligibleIdsIn(any()) }.doSuspendableAnswer { asked ->
                val answer = asked.getArgument<List<Long>>(0).filterNot { it in goneFromHistory }
                if (deleting == null) {
                    deleting = testScope.launch { repo.deleteSession(2L) }
                    repeat(200) { yield() }
                }
                answer
            }
        }

        repo.evaluateAndAdjustPlan("base_builder", RunType.LONG)
        deleting?.join()

        assertEquals(listOf("amend", "take back"), order)
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

        verify(mockPrescriptions, never()).prescribe(any(), any(), any(), any(), any())
        verify(mockPrescriptions, never()).amendStanding(any(), any(), any())
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

            verify(mockPrescriptions, never()).prescribe(any(), any(), any(), any(), any())
            verify(mockPrescriptions, never()).amendStanding(any(), any(), any())
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

        verify(mockPrescriptions, never()).prescribe(any(), any(), any(), any(), any())
        verify(mockPrescriptions, never()).amendStanding(any(), any(), any())
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
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
    fun `the coach is shown the Stage's own Runs and no others`() = runTest {
        // #234: a Stage is graduated on evidence, and evidence belongs to the Stage it was recorded
        // under. Asked for the last three Runs full stop, the first evaluation after a graduation
        // read the work of the Stage just left — enough, on a Stage asking for a time, to graduate
        // the runner twice on one Stage's running.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(eq("sub_30_bridge")))
            .thenReturn(listOf(aTreadmillRun(id = 9, seconds = 1_500)))

        val context = repository.getAiTrainingContext("sub_30_bridge")

        assertEquals(1, context.recentRuns.size)
        verify(mockDao).getLast3AiEligibleRunsOfStage("sub_30_bridge")
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(outdoorRun))
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(treadmillRun))
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(treadmillRun))

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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any()))
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
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
        assertEquals(listOf(700, null), state.weeklyEfforts.map { it.score })
    }

    @Test
    fun `a Run that wore no Strap is not inside the numbers the coach is given`() = runTest {
        // No heart rate is no Effort Score, so the curves cannot see the Run that just finished —
        // and told nothing, a coach reads a hard strapless hour as an hour of rest (#66).
        val strapless = aTreadmillRun(id = 9, seconds = 3_600).copy(effortScore = null)
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(strapless))
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(scored))
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(scoredButAhead))
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
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
        assertEquals(listOf(100, 0), state.weeklyEfforts.map { it.score })
    }

    @Test
    fun `a week holding both kinds of Run reaches the coach marked as measured in part`() = runTest {
        // The #247 week: one Run wore a Strap and one did not, so the total is a floor under the
        // week. Sent unmarked it reads as the whole of it, and reads low every time.
        val scored = ScoredRunProjection(startTime = DAY_MILLIS_2026_01_05, effortScore = 100)
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
        whenever(mockDao.getScoredRunsFlow()).thenReturn(flowOf(listOf(scored)))
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(
                listOf(
                    volumeRow(startTime = DAY_MILLIS_2026_01_05, effortScore = 100),
                    volumeRow(startTime = DAY_MILLIS_2026_01_05 + ONE_DAY_MILLIS, effortScore = null),
                )
            )
        )

        val weeks = repository.getAiTrainingContext(
            "sub_30_bridge",
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 1, 18)
        ).fitnessAndForm!!.weeklyEfforts

        assertEquals(listOf(100, 0), weeks.map { it.score })
        // And only that week: the week nobody ran in was not measured in part, it was not run.
        assertEquals(listOf(true, false), weeks.map { it.partlyMeasured })
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
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
        whenever(mockDao.getScoredRunsFlow()).thenReturn(flowOf(weekly))
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(weekly.map { volumeRow(startTime = it.startTime, effortScore = it.effortScore) })
        )

        val state = repository.getAiTrainingContext(
            "sub_30_bridge",
            zone = ZoneOffset.UTC,
            today = LocalDate.of(2026, 2, 15)
        ).fitnessAndForm!!

        assertEquals(listOf(30, 40, 50, 60), state.weeklyEfforts.map { it.score })
    }

    @Test
    fun `with no scored Run in history the coach is told nothing about fatigue`() = runTest {
        // A new phone, or a runner who has never run with a Strap: there is no curve to read, and
        // zeroes would read as six weeks of doing nothing.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
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

    // --- Scoring the Runs whose scoring was missed (#210) ---------------------------------------

    @Test
    fun `the launch pass scores a finished Run the book never measured, and marks it`() = runTest {
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory(seeded = true)
        whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(7L))
        whenever(mockDao.getSessionById(7L)).thenReturn(aTreadmillRun(id = 7, seconds = 1_800))

        repositoryWithRecords.scoreMissedRecords()

        val book = argumentCaptor<List<Achievement>>()
        verify(mockAchievementDao).insertAchievements(book.capture())
        assertEquals(listOf(7L to Medal.GOLD), book.firstValue.map { it.sessionId to it.medal })
        verify(mockDao).setRecordsScored(7L)
    }

    @Test
    fun `a Run whose scoring cannot be written stays owed rather than being marked`() = runTest {
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory(seeded = true)
        whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(7L))
        whenever(mockDao.getSessionById(7L)).thenReturn(aTreadmillRun(id = 7, seconds = 1_800))
        whenever(mockAchievementDao.insertAchievements(any())).thenThrow(RuntimeException("disk full"))

        // Does not throw: the launch scope has no handler behind it.
        repositoryWithRecords.scoreMissedRecords()

        verify(mockDao, never()).setRecordsScored(any())
    }

    @Test
    fun `one Run the pass cannot score costs the next one nothing`() = runTest {
        val (repositoryWithRecords, _) = repositoryWithUnseededHistory(seeded = true)
        whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(7L, 8L))
        whenever(mockDao.getSessionById(7L)).thenThrow(RuntimeException("unreadable row"))
        whenever(mockDao.getSessionById(8L)).thenReturn(aTreadmillRun(id = 8, seconds = 600))

        repositoryWithRecords.scoreMissedRecords()

        verify(mockDao, never()).setRecordsScored(7L)
        verify(mockDao).setRecordsScored(8L)
    }

    @Test
    fun `nothing is scored one at a time while history is still owed a seeding`() = runTest {
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory(seeded = false)

        repositoryWithRecords.scoreMissedRecords()

        // The seeding pass is about to measure all of it anyway, and its book is the better one:
        // it can fill a hole below the stored top three, which scoring a Run at a time cannot.
        verify(mockDao, never()).getSessionIdsMissingRecordScoring()
        verify(mockAchievementDao, never()).insertAchievements(any())
    }

    @Test
    fun `seeding settles the debt of every Run that was owing when it started`() = runTest {
        whenever(mockDao.getAllSessions()).thenReturn(
            listOf(
                aTreadmillRun(id = 1, seconds = 600),
                aTreadmillRun(id = 2, seconds = 1_800),
                session(id = 9, endTime = 0L),
            )
        )
        // Run 9 is still being recorded, so it is not on the list: it will score itself when it
        // finishes, and marking it here would let a scoring missed at that finish go unnoticed.
        whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(1L, 2L))
        val (repositoryWithRecords, _) = repositoryWithUnseededHistory()

        repositoryWithRecords.seedRecordsFromHistory()

        verify(mockDao).setRecordsScoredForSessions(listOf(1L, 2L))
    }

    @Test
    fun `seeding a history longer than one query can carry marks all of it`() = runTest {
        // Every id is a bound variable, and SQLite takes a bounded number of them.
        val history = (1L..1_200L).map { aTreadmillRun(id = it, seconds = it) }
        whenever(mockDao.getAllSessions()).thenReturn(history)
        whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(history.map { it.id })
        val (repositoryWithRecords, _) = repositoryWithUnseededHistory()

        repositoryWithRecords.seedRecordsFromHistory()

        val marked = argumentCaptor<List<Long>>()
        verify(mockDao, times(3)).setRecordsScoredForSessions(marked.capture())
        assertEquals((1L..1_200L).toList(), marked.allValues.flatten())
        assertTrue(marked.allValues.all { it.size <= 999 })
    }

    @Test
    fun `seeding reads what is owing before it measures, so a Run finishing mid-pass stays owed`() =
        runTest {
            val (repositoryWithRecords, _) = repositoryWithUnseededHistory()
            // Run 8 finishes while history is being measured. It is measured by the rebuild, but it
            // scores itself too — and if that scoring is missed, only its own debt can find it.
            whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(1L))
            whenever(mockDao.getAllSessions()).then {
                runBlocking {
                    whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(1L, 8L))
                }
                listOf(aTreadmillRun(id = 1, seconds = 600), aTreadmillRun(id = 8, seconds = 900))
            }

            repositoryWithRecords.seedRecordsFromHistory()

            verify(mockDao).setRecordsScoredForSessions(listOf(1L))
        }

    @Test
    fun `a seeding pass that declines the seeded mark marks no Run either`() = runTest {
        whenever(mockDao.getAllSessions()).thenReturn(listOf(aTreadmillRun(id = 1, seconds = 600)))
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory()
        whenever(mockAchievementDao.insertAchievements(any())).thenThrow(RuntimeException("disk full"))

        repositoryWithRecords.seedRecordsFromHistory()

        // The pass is owed again, and so is every Run in it: a mark here would be a debt cancelled
        // by a book that was never written.
        verify(mockSettingsRepo, never()).setHistoryRecordsSeeded()
        verify(mockDao, never()).setRecordsScoredForSessions(any())
    }

    @Test
    fun `scoring Runs one at a time reaches the same book as a rebuild over the same history`() =
        runTest {
            val history = listOf(
                aTreadmillRun(id = 1, seconds = 600),
                aTreadmillRun(id = 2, seconds = 3_600),
                aTreadmillRun(id = 3, seconds = 1_200),
                aTreadmillRun(id = 4, seconds = 2_400),
                aTreadmillRun(id = 5, seconds = 900),
            )
            whenever(mockDao.getAllSessions()).thenReturn(history)
            history.forEach { whenever(mockDao.getSessionById(it.id)).thenReturn(it) }
            whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(history.map { it.id })

            val oneAtATime = BookInMemory()
            whenever(mockSettingsRepo.userSettingsFlow)
                .thenReturn(flowOf(UserSettings(historyRecordsSeeded = true)))
            SessionRepository(
                sessionDao = mockDao,
                achievementDao = oneAtATime,
                settingsRepository = mockSettingsRepo
            ).scoreMissedRecords()

            val allAtOnce = BookInMemory()
            whenever(mockSettingsRepo.userSettingsFlow)
                .thenReturn(flowOf(UserSettings(historyRecordsSeeded = false)))
            SessionRepository(
                sessionDao = mockDao,
                achievementDao = allAtOnce,
                settingsRepository = mockSettingsRepo
            ).seedRecordsFromHistory()

            assertEquals(allAtOnce.standings(), oneAtATime.standings())
            assertEquals(
                listOf(2L to Medal.GOLD, 4L to Medal.SILVER, 3L to Medal.BRONZE),
                oneAtATime.standings().map { it.first to it.second },
            )
        }

    @Test
    fun `running the launch pass twice leaves the same book, with no Run racing itself`() = runTest {
        // The mark is written after the scoring, so a process that dies in between costs a Run one
        // redundant re-score. This is what that re-score has to be worth: nothing at all.
        val run = aTreadmillRun(id = 7, seconds = 1_800)
        whenever(mockDao.getSessionById(7L)).thenReturn(run)
        whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(7L))
        whenever(mockSettingsRepo.userSettingsFlow)
            .thenReturn(flowOf(UserSettings(historyRecordsSeeded = true)))
        val book = BookInMemory()
        val repositoryWithRecords = SessionRepository(
            sessionDao = mockDao,
            achievementDao = book,
            settingsRepository = mockSettingsRepo
        )

        repositoryWithRecords.scoreMissedRecords()
        val afterOnce = book.standings()
        repositoryWithRecords.scoreMissedRecords()

        assertEquals(listOf(Triple(7L, Medal.GOLD, RecordType.LONGEST_DURATION)), afterOnce)
        assertEquals(afterOnce, book.standings())
    }

    @Test
    fun `a Run whose distance is corrected while it is being measured is not written to the book`() =
        runTest {
            val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory(seeded = true)
            whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(7L))
            val measured = aTreadmillRun(id = 7, seconds = 1_800).copy(distanceKm = 9.0)
            // The runner corrects the number on the console while the pass is working its way
            // through history: the correction scores itself and mends the book behind it, so the
            // effort measured before it is no longer this Run's own.
            whenever(mockDao.getSessionById(7L))
                .thenReturn(measured, measured.copy(distanceKm = 4.0))

            repositoryWithRecords.scoreMissedRecords()

            verify(mockAchievementDao, never()).insertAchievements(any())
            // And still owing, so the next launch measures it against the corrected number.
            verify(mockDao, never()).setRecordsScored(any())
        }

    @Test
    fun `a Run deleted while it is being measured is not written to the book`() = runTest {
        val (repositoryWithRecords, mockAchievementDao) = repositoryWithUnseededHistory(seeded = true)
        whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(7L))
        whenever(mockDao.getSessionById(7L))
            .thenReturn(aTreadmillRun(id = 7, seconds = 1_800), null)

        repositoryWithRecords.scoreMissedRecords()

        // A medal for a Run that no longer exists, standing over the record it took.
        verify(mockAchievementDao, never()).insertAchievements(any())
        verify(mockDao, never()).setRecordsScored(any())
    }

    @Test
    fun `a Run whose Effort Score lands mid-measure is scored anyway`() = runTest {
        // The Effort backfill runs at the same launch and writes to every Run in history. It cannot
        // move a distance or a duration, so it is not a reason to abandon a scoring.
        val (repositoryWithRecords, _) = repositoryWithUnseededHistory(seeded = true)
        whenever(mockDao.getSessionIdsMissingRecordScoring()).thenReturn(listOf(7L))
        val measured = aTreadmillRun(id = 7, seconds = 1_800)
        whenever(mockDao.getSessionById(7L))
            .thenReturn(measured, measured.copy(effortScore = 42, sessionNote = "hard"))

        repositoryWithRecords.scoreMissedRecords()

        verify(mockDao).setRecordsScored(7L)
    }

    /** The record book as rows in memory, for the two passes that have to arrive at the same one. */
    private class BookInMemory : AchievementDao {
        private val rows = mutableListOf<Achievement>()

        override suspend fun insertAchievements(achievements: List<Achievement>) {
            rows += achievements
        }

        override suspend fun getAllAchievements(): List<Achievement> = rows.toList()

        override fun getAchievementsForSessionFlow(sessionId: Long) =
            flowOf(rows.filter { it.sessionId == sessionId })

        override fun getMedalCountsFlow() = flowOf(emptyList<SessionMedalCount>())

        override suspend fun getAchievementsForSessions(sessionIds: List<Long>) =
            rows.filter { it.sessionId in sessionIds }

        override suspend fun deleteAchievementsOfTypes(types: List<RecordType>) {
            rows.removeAll { it.type in types }
        }

        // The real query joins the Run in for its start time; this book holds no Runs, so it
        // answers with the effort alone.
        override fun getQuickestInHistoryFlow(type: RecordType) = flowOf(
            rows.filter { it.type == type }.minByOrNull { it.value }
                ?.let { HistoryBestEffort(seconds = it.value, runStartedAtMillis = 0L) }
        )

        /** The book with its row ids dropped, ordered, so two of them can be compared. */
        fun standings(): List<Triple<Long, Medal, RecordType>> =
            rows.map { Triple(it.sessionId, it.medal, it.type) }
                .sortedWith(compareBy({ it.third }, { it.second }))
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

    // --- What the Run felt like, and what the runner is aiming at (#83) ---

    @Test
    fun `how a Run felt, what was written about it and the weather it was run in reach the coach`() =
        runTest {
            val felt = aTreadmillRun(id = 9, seconds = 1_500).copy(
                runMode = "outdoor",
                perceivedEffort = 9,
                sessionNote = "Legs like lead the whole way.",
                weatherTempC = 3.6,
                weatherFeelsLikeC = -0.4,
                weatherHumidityPercent = 88,
                weatherWindSpeedKmh = 29.5,
                weatherConditionCode = 65
            )
            whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(felt))

            val recentRun = repository.getAiTrainingContext("sub_30_bridge").recentRuns.single()

            assertEquals(9, recentRun.perceivedEffort)
            assertEquals("Legs like lead the whole way.", recentRun.note)
            // The runner's own words, verbatim: the whole value of a note is the words they chose.
            assertEquals(
                "Heavy rain, 4°C, feels like 0°C, 88% humidity, 30 km/h wind",
                recentRun.weather
            )
        }

    @Test
    fun `a Run nobody said anything about says nothing, rather than saying nothing happened`() =
        runTest {
            // A treadmill Run with the sheet walked past — the ordinary case, and the one where a
            // nought would read as a run that felt like nothing on a still, mild day.
            whenever(mockDao.getLast3AiEligibleRunsOfStage(any()))
                .thenReturn(listOf(aTreadmillRun(id = 9, seconds = 1_500)))

            val recentRun = repository.getAiTrainingContext("sub_30_bridge").recentRuns.single()

            assertNull(recentRun.perceivedEffort)
            assertNull(recentRun.note)
            assertNull(recentRun.weather)
        }

    @Test
    fun `a note the runner cleared is nothing written, not something written`() = runTest {
        // The finish sheet leaves the column null when it is walked past, but the edit path writes
        // a cleared note through as an empty string (#80) — so the emptiness arrives both ways and
        // has to be answered once, here.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any()))
            .thenReturn(listOf(aTreadmillRun(id = 9, seconds = 1_500).copy(sessionNote = "   ")))

        assertNull(repository.getAiTrainingContext("sub_30_bridge").recentRuns.single().note)
    }

    @Test
    fun `a note short enough to send reaches the coach untouched`() {
        val written = "Legs like lead the whole way, but the last mile came back to me."

        assertEquals(written, noteForCoach(written))
    }

    @Test
    fun `a note exactly as long as the coach is sent is not marked as cut`() {
        val written = "a".repeat(MAX_COACH_NOTE_CHARS)

        assertEquals(written, noteForCoach(written))
    }

    @Test
    fun `a note too long to send is cut, and says it was cut`() {
        // One character over is enough: the bound is where the cut starts, not where it is worth
        // making.
        val written = "a".repeat(MAX_COACH_NOTE_CHARS) + "b"

        assertEquals("a".repeat(MAX_COACH_NOTE_CHARS) + "…", noteForCoach(written))
    }

    @Test
    fun `a pasted essay reaches the coach bounded, not whole`() = runTest {
        // The runner's own row keeps every word — only the copy handed to the coach is bounded, or
        // a long enough note would push the request past the model's limit and the whole debrief
        // and plan adjustment would be lost with nothing on screen to say why (#83).
        val essay = "I ran and ran. ".repeat(500)
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any()))
            .thenReturn(listOf(aTreadmillRun(id = 9, seconds = 1_500).copy(sessionNote = essay)))

        val note = repository.getAiTrainingContext("sub_30_bridge").recentRuns.single().note

        assertEquals(MAX_COACH_NOTE_CHARS + 1, note?.length)
        assertEquals(essay.take(MAX_COACH_NOTE_CHARS) + "…", note)
    }

    @Test
    fun `a note typed while the Run is being judged cannot join its own Run's evaluation`() =
        runTest {
            // The same rule as the stated distance (#231): the sheet is on screen while this read
            // happens, and a Run is judged on what it was when it ended.
            val asFinalized = aTreadmillRun(id = 8, seconds = 1_500)
            whenever(mockDao.getLast3AiEligibleRunsOfStage(any()))
                .thenReturn(listOf(asFinalized.copy(perceivedEffort = 2, sessionNote = "Easy day.")))

            val recentRun = repository
                .getAiTrainingContext("sub_30_bridge", asFinalized = asFinalized)
                .recentRuns
                .single()

            assertNull(recentRun.perceivedEffort)
            assertNull(recentRun.note)
        }

    // --- The weather of the Run the coach is being asked about (#79, #83) ---

    /** An outdoor Run with a fix to place it, and nothing fetched for it yet. */
    private fun anOutdoorRun(id: Long, latitude: Double = 51.5) =
        aTreadmillRun(id = id, seconds = 1_500).copy(
            runMode = "outdoor",
            startTime = DAY_MILLIS_2026_01_05,
            startLatitude = latitude,
            startLongitude = -0.12
        )

    private val fetchedSnapshot = WeatherSnapshot(
        temperatureC = 3.6,
        feelsLikeC = -0.4,
        humidityPercent = 88,
        windSpeedKmh = 29.5,
        conditionCode = 65
    )

    /** What [fetchedSnapshot] looks like once it is on the row it was fetched for. */
    private fun RunnerSession.withFetchedWeather() = copy(
        weatherTempC = fetchedSnapshot.temperatureC,
        weatherFeelsLikeC = fetchedSnapshot.feelsLikeC,
        weatherHumidityPercent = fetchedSnapshot.humidityPercent,
        weatherWindSpeedKmh = fetchedSnapshot.windSpeedKmh,
        weatherConditionCode = fetchedSnapshot.conditionCode
    )

    /** A weather service that says what it was asked, and what it answered. */
    private class RecordingWeatherClient(
        private val snapshot: WeatherSnapshot?,
        private val unreachable: Boolean = false
    ) : WeatherClient {
        val asked = mutableListOf<Triple<Double, Double, Long>>()

        override suspend fun fetchWeather(
            latitude: Double,
            longitude: Double,
            atEpochMillis: Long
        ): WeatherSnapshot? {
            asked += Triple(latitude, longitude, atEpochMillis)
            if (unreachable) throw IllegalStateException("no network")
            return snapshot
        }
    }

    private fun repositoryFetchingWeather(client: WeatherClient) = SessionRepository(
        sessionDao = mockDao,
        settingsRepository = mockSettingsRepo,
        weatherClient = client
    )

    @Test
    fun `the weather of the Run just finished is fetched before the coach is asked about it`() =
        runTest {
            // The after-run worker books the whole Downloads snapshot ahead of its fetch, so on an
            // outdoor finish the settle path gets to the coach first — and a Run is asked about
            // once and never again, so a debrief sent while the fetch is in flight is a debrief
            // that never mentions the headwind (#79, #83).
            val asFinalized = anOutdoorRun(id = 8)
            whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(asFinalized))
            whenever(mockDao.getSessionById(8L)).thenReturn(asFinalized.withFetchedWeather())
            val client = RecordingWeatherClient(fetchedSnapshot)

            val recentRun = repositoryFetchingWeather(client)
                .getAiTrainingContext("sub_30_bridge", asFinalized = asFinalized)
                .recentRuns
                .single()

            assertEquals(listOf(Triple(51.5, -0.12, DAY_MILLIS_2026_01_05)), client.asked)
            verify(mockDao).updateWeather(
                sessionId = 8L,
                tempC = 3.6,
                feelsLikeC = -0.4,
                humidityPercent = 88,
                windSpeedKmh = 29.5,
                conditionCode = 65
            )
            assertEquals(
                "Heavy rain, 4°C, feels like 0°C, 88% humidity, 30 km/h wind",
                recentRun.weather
            )
        }

    @Test
    fun `a Run whose weather is already stored is not fetched a second time`() = runTest {
        val asFinalized = anOutdoorRun(id = 8).withFetchedWeather()
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(asFinalized))
        val client = RecordingWeatherClient(fetchedSnapshot)

        val recentRun = repositoryFetchingWeather(client)
            .getAiTrainingContext("sub_30_bridge", asFinalized = asFinalized)
            .recentRuns
            .single()

        assertEquals(emptyList<Triple<Double, Double, Long>>(), client.asked)
        assertEquals(
            "Heavy rain, 4°C, feels like 0°C, 88% humidity, 30 km/h wind",
            recentRun.weather
        )
    }

    @Test
    fun `a Run with no weather to fetch asks for none`() = runTest {
        // A treadmill Run was run in no weather at all, and an outdoor Run that never got a fix
        // cannot be placed — both are silence rather than a fetch worth making.
        val treadmill = aTreadmillRun(id = 8, seconds = 1_500)
        val unplaced = treadmill.copy(runMode = "outdoor")
        val client = RecordingWeatherClient(fetchedSnapshot)
        val repo = repositoryFetchingWeather(client)

        for (finalized in listOf(treadmill, unplaced)) {
            whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(finalized))

            val recentRun = repo
                .getAiTrainingContext("sub_30_bridge", asFinalized = finalized)
                .recentRuns
                .single()

            assertNull(recentRun.weather)
        }
        assertEquals(emptyList<Triple<Double, Double, Long>>(), client.asked)
    }

    @Test
    fun `a weather service that cannot be reached still lets the debrief go out`() = runTest {
        // Offline degrades exactly as it always has: the field stays empty and the coach is asked
        // anyway. A run out of signal must not cost the runner their debrief.
        val asFinalized = anOutdoorRun(id = 8)
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(asFinalized))
        whenever(mockDao.getSessionById(8L)).thenReturn(asFinalized)
        val client = RecordingWeatherClient(snapshot = null, unreachable = true)

        val context = repositoryFetchingWeather(client)
            .getAiTrainingContext("sub_30_bridge", asFinalized = asFinalized)

        assertEquals(1, client.asked.size)
        assertNull(context.recentRuns.single().weather)
    }

    @Test
    fun `weather missing from the Runs before this one is missing for good`() = runTest {
        // Those are settled history: nothing is still on its way for them, and the launch retry is
        // what mends a row nobody could fetch. Only the Run that has just finished is fetched here.
        val asFinalized = anOutdoorRun(id = 8).withFetchedWeather()
        val older = anOutdoorRun(id = 7, latitude = 55.9)
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(listOf(asFinalized, older))
        val client = RecordingWeatherClient(fetchedSnapshot)

        val recentRuns = repositoryFetchingWeather(client)
            .getAiTrainingContext("sub_30_bridge", asFinalized = asFinalized)
            .recentRuns

        assertEquals(emptyList<Triple<Double, Double, Long>>(), client.asked)
        assertNull(recentRuns.last().weather)
    }

    @Test
    fun `a context asked for outside a finish fetches nothing`() = runTest {
        // The Progress screen and every other reader: there is no Run just finished, so there is
        // nothing still on its way.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any()))
            .thenReturn(listOf(anOutdoorRun(id = 8)))
        val client = RecordingWeatherClient(fetchedSnapshot)

        val recentRun = repositoryFetchingWeather(client)
            .getAiTrainingContext("sub_30_bridge")
            .recentRuns
            .single()

        assertEquals(emptyList<Triple<Double, Double, Long>>(), client.asked)
        assertNull(recentRun.weather)
    }

    /** Wednesday of the week beginning Monday 5 January 2026, in the zone these tests read. */
    private val goalsToday = LocalDate.of(2026, 1, 7)

    private fun repositoryWithGoals(vararg goals: GoalRow): SessionRepository {
        val mockGoalDao: GoalDao = mock()
        whenever(mockGoalDao.getAllGoalsFlow()).thenReturn(flowOf(goals.toList()))
        return SessionRepository(
            sessionDao = mockDao,
            goalDao = mockGoalDao,
            settingsRepository = mockSettingsRepo
        )
    }

    private fun goalRow(id: Long, period: GoalPeriod, metric: GoalMetric, target: Double) = GoalRow(
        id = id,
        period = period,
        metric = metric,
        target = target,
        createdAtMillis = DAY_MILLIS_2026_01_05
    )

    @Test
    fun `the runner's goals and where they stand reach the coach`() = runTest {
        // Two 5 km Runs in the week the runner is in: 10 of 40 km, and 2 of 3 runs.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(
                listOf(
                    volumeRow(startTime = DAY_MILLIS_2026_01_05, effortScore = null),
                    volumeRow(startTime = DAY_MILLIS_2026_01_05 + ONE_DAY_MILLIS, effortScore = null)
                )
            )
        )
        val repo = repositoryWithGoals(
            goalRow(1, GoalPeriod.WEEK, GoalMetric.DISTANCE, target = 40.0),
            goalRow(2, GoalPeriod.WEEK, GoalMetric.COUNT, target = 3.0)
        )

        val goals = repo
            .getAiTrainingContext("sub_30_bridge", zone = ZoneOffset.UTC, today = goalsToday)
            .goals

        assertEquals(
            listOf(
                AiGoal(period = "This week", metric = "Distance", done = "10", target = "40", unit = "km"),
                AiGoal(period = "This week", metric = "Runs", done = "2", target = "3", unit = "runs")
            ),
            goals
        )
    }

    @Test
    fun `only the period the runner is in now is counted towards a goal`() = runTest {
        // Last week is over, and a coach comparing this Monday's nothing to last week's 40 km would
        // read a fresh period as a collapse.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
        whenever(mockDao.getRunVolumesFlow()).thenReturn(
            flowOf(listOf(volumeRow(startTime = DAY_MILLIS_2026_01_05 - 3 * ONE_DAY_MILLIS, effortScore = null)))
        )
        val repo = repositoryWithGoals(goalRow(1, GoalPeriod.WEEK, GoalMetric.DISTANCE, target = 40.0))

        val goal = repo
            .getAiTrainingContext("sub_30_bridge", zone = ZoneOffset.UTC, today = goalsToday)
            .goals
            .single()

        assertEquals("0", goal.done)
    }

    @Test
    fun `a runner who has set no goals sends the coach no goals block`() = runTest {
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())
        val repo = repositoryWithGoals()

        val context = repo
            .getAiTrainingContext("sub_30_bridge", zone = ZoneOffset.UTC, today = goalsToday)

        assertEquals(emptyList<AiGoal>(), context.goals)
        // And history is not walked to find that out: with no goal to measure there is nothing for
        // the read to answer.
        verify(mockDao, never()).getRunVolumesFlow()
    }

    @Test
    fun `with no goals wired at all the coach is told nothing about them`() = runTest {
        // The archive's read-only container and every test that does not care: a missing goalDao is
        // the same silence as a runner who has set none.
        whenever(mockDao.getLast3AiEligibleRunsOfStage(any())).thenReturn(emptyList())

        assertEquals(emptyList<AiGoal>(), repository.getAiTrainingContext("sub_30_bridge").goals)
    }

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
