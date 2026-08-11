package com.example.runningapp.ui

import com.example.runningapp.SettingsRepository
import com.example.runningapp.UserSettings
import com.example.runningapp.data.GoalDao
import com.example.runningapp.data.GoalRow
import com.example.runningapp.data.RunVolumeProjection
import com.example.runningapp.data.SampleDao
import com.example.runningapp.data.ScoredRunProjection
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.training.Goal
import com.example.runningapp.training.GoalMetric
import com.example.runningapp.training.GoalPeriod
import com.example.runningapp.training.ProgressRange
import com.example.runningapp.training.WeeklyMeasure
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * What the Progress screen is handed (#63): today's numbers, and the stretch of curve the chosen
 * range shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone: ZoneId = ZoneId.of("Europe/London")
    private val today: LocalDate = LocalDate.of(2026, 8, 5)

    private val scoredRuns = MutableStateFlow<List<ScoredRunProjection>>(emptyList())
    private val runVolumes = MutableStateFlow<List<RunVolumeProjection>>(emptyList())
    private val sessionDao: SessionDao = mock()
    private val sampleDao: SampleDao = mock()
    private val settingsRepository: SettingsRepository = mock()
    private val goalDao: GoalDao = mock()

    /** The goals the runner has set, as the table would hand them back. */
    private val goalRows = MutableStateFlow<List<GoalRow>>(emptyList())

    /** A runner on the untouched placeholder maximum with a resting heart rate stated — Chris. */
    private val settings = MutableStateFlow(UserSettings(restingHr = 60))

    /** Every heart rate this screen stated, in the order it stated them. */
    private val stated = mutableListOf<Pair<Int?, Int?>>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(sessionDao.getScoredRunsFlow()).thenReturn(scoredRuns)
        whenever(sessionDao.getRunVolumesFlow()).thenReturn(runVolumes)
        whenever(goalDao.getAllGoalsFlow()).thenReturn(goalRows)
        whenever(settingsRepository.userSettingsFlow).thenReturn(settings)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a history with no scored runs has no numbers to show`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.state.value.today)
        assertEquals(emptyList<Any>(), viewModel.state.value.curve)
    }

    @Test
    fun `today's numbers are the last day of the curve`() = runTest(dispatcher) {
        scoredRuns.value = listOf(runOn(today.minusDays(1), 100))

        val viewModel = viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(today, state.today?.date)
        assertEquals(state.curve.last(), state.today)
        // Yesterday's hard run is still being carried today, so Form is under water.
        assertTrue("form after a hard day: ${state.today?.form}", (state.today?.form ?: 0.0) < 0.0)
    }

    @Test
    fun `three months is the range the screen opens on`() = runTest(dispatcher) {
        scoredRuns.value = listOf(runOn(today.minusYears(1), 100), runOn(today.minusDays(1), 100))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(ProgressRange.THREE_MONTHS, viewModel.state.value.range)
        assertEquals(today.minusMonths(3), viewModel.state.value.curve.first().date)
    }

    @Test
    fun `choosing a longer range shows more of the same curve`() = runTest(dispatcher) {
        scoredRuns.value = listOf(runOn(today.minusYears(1), 100), runOn(today.minusDays(1), 100))
        val viewModel = viewModel()
        advanceUntilIdle()
        val threeMonths = viewModel.state.value.curve

        viewModel.rangeChosen(ProgressRange.ONE_YEAR)
        advanceUntilIdle()

        val year = viewModel.state.value.curve
        assertEquals(today.minusYears(1), year.first().date)
        // The same days, still saying the same thing — a range is a window, not a recomputation.
        assertEquals(threeMonths, year.takeLast(threeMonths.size))
    }

    @Test
    fun `a score arriving while the screen is open redraws the curve`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNull(viewModel.state.value.today)

        // What the backfill does to a history it is still working through (#62).
        scoredRuns.value = listOf(runOn(today.minusDays(2), 80))
        advanceUntilIdle()

        assertEquals(today, viewModel.state.value.today?.date)
    }

    @Test
    fun `distance is the measure the weekly bars open on`() = runTest(dispatcher) {
        runVolumes.value = listOf(volumeOn(today.minusDays(1), km = 10.0))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(WeeklyMeasure.DISTANCE, viewModel.state.value.measure)
    }

    @Test
    fun `switching measure leaves the same weeks in place`() = runTest(dispatcher) {
        runVolumes.value = listOf(volumeOn(today.minusDays(1), km = 10.0, seconds = 3_600))
        val viewModel = viewModel()
        advanceUntilIdle()
        val weeks = viewModel.state.value.weeks

        viewModel.measureChosen(WeeklyMeasure.TIME)
        advanceUntilIdle()

        assertEquals(WeeklyMeasure.TIME, viewModel.state.value.measure)
        assertEquals(weeks, viewModel.state.value.weeks)
    }

    @Test
    fun `the range picker moves the weeks and the curve together`() = runTest(dispatcher) {
        scoredRuns.value = listOf(runOn(today.minusYears(1), 100), runOn(today.minusDays(1), 100))
        runVolumes.value = listOf(
            volumeOn(today.minusYears(1), km = 10.0),
            volumeOn(today.minusDays(1), km = 10.0),
        )
        val viewModel = viewModel()
        advanceUntilIdle()
        val threeMonths = viewModel.state.value

        viewModel.rangeChosen(ProgressRange.ONE_YEAR)
        advanceUntilIdle()

        val year = viewModel.state.value
        assertTrue(year.curve.size > threeMonths.curve.size)
        assertTrue(year.weeks.size > threeMonths.weeks.size)
        // Both windows end where the runner is now, so the newest week and the newest day are the
        // same ones whichever range is showing.
        assertEquals(threeMonths.weeks.last(), year.weeks.last())
        assertEquals(threeMonths.curve.last(), year.curve.last())
    }

    @Test
    fun `a run with no Effort Score still gives the week its distance`() = runTest(dispatcher) {
        // No scored runs at all: nothing for the curves, but a week of training all the same.
        runVolumes.value = listOf(volumeOn(today.minusDays(1), km = 12.0, score = null))

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.state.value.today)
        assertEquals(12.0, viewModel.state.value.weeks.last().distanceKm, 0.001)
    }

    @Test
    fun `with no scored runs the range is still measured back from today`() = runTest(dispatcher) {
        // Wednesday 5 August 2026, and no Scores at all, so the weeks have to carry the day they
        // were totalled through themselves. Measured back from the last week's Monday instead, three
        // months would start on the Sunday and let in the week beginning 4 May.
        runVolumes.value = listOf(
            volumeOn(today.minusYears(1), km = 10.0),
            volumeOn(today.minusDays(1), km = 10.0),
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.state.value.today)
        assertEquals(LocalDate.of(2026, 5, 11), viewModel.state.value.weeks.first().startingOn)
    }

    // --- The one-time Max HR confirmation (#65, #103) ---

    @Test
    fun `a runner who has never stated a maximum is asked, and offered their own`() = runTest(dispatcher) {
        whenever(sampleDao.getHighestSustainedBpm(any())).thenReturn(181)

        val viewModel = viewModel()
        advanceUntilIdle()

        val card = viewModel.state.value.maxHrCard
        assertEquals(MaxHrCardState(currentMaxHr = 190, restingHr = 60, suggestedMaxHr = 181), card)
    }

    @Test
    fun `a phone with no recorded heart rate falls back to asking an age`() = runTest(dispatcher) {
        whenever(sampleDao.getHighestSustainedBpm(any())).thenReturn(null)

        val viewModel = viewModel()
        advanceUntilIdle()

        // The card is still asked — it is the number every figure on the screen hangs off. It has
        // nothing of the runner's own to offer, which is what the age input is for.
        assertNull(viewModel.state.value.maxHrCard?.suggestedMaxHr)
        assertEquals(190, viewModel.state.value.maxHrCard?.currentMaxHr)
    }

    @Test
    fun `nothing is asked until the runner's own evidence has been read`() = runTest(dispatcher) {
        // Before the read comes back there is no card at all, rather than the age-fallback one: a
        // runner tapping in that moment would be asked their age with their own maximum unread.
        val viewModel = viewModel()

        assertNull(viewModel.state.value.maxHrCard)
    }

    @Test
    fun `a runner who has already stated a maximum is not asked`() = runTest(dispatcher) {
        settings.value = UserSettings(maxHr = 181, maxHrEverSet = true, restingHr = 60)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.state.value.maxHrCard)
    }

    @Test
    fun `a card put away stays away`() = runTest(dispatcher) {
        settings.value = UserSettings(maxHrCardDismissed = true)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertNull(viewModel.state.value.maxHrCard)
    }

    @Test
    fun `a retired card costs no history read at all`() = runTest(dispatcher) {
        // The peak is a sort over the whole of hr_samples. Once the question is answered or put
        // away it can never be asked again, so paying for that read on every visit to this screen
        // buys a card that will not be drawn.
        listOf(
            UserSettings(maxHr = 181, maxHrEverSet = true),
            UserSettings(maxHrCardDismissed = true),
        ).forEach { retired ->
            settings.value = retired

            viewModel()
            advanceUntilIdle()
        }

        verify(sampleDao, never()).getHighestSustainedBpm(any())
    }

    @Test
    fun `confirming states the number and takes the card off the screen`() = runTest(dispatcher) {
        whenever(sampleDao.getHighestSustainedBpm(any())).thenReturn(181)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.maxHrConfirmed(181)
        advanceUntilIdle()

        assertEquals(listOf(181 to null), stated)
        assertNull(viewModel.state.value.maxHrCard)
    }

    @Test
    fun `an answer that never lands leaves the question askable`() = runTest(dispatcher) {
        // A statement can be dropped on its way through the queue. Retiring the card against one
        // would leave the runner on a maximum nobody chose, unasked and unaskable — so confirming
        // hides the card for this visit only, and what retires it is the flag the statement sets.
        whenever(sampleDao.getHighestSustainedBpm(any())).thenReturn(181)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.maxHrConfirmed(181)
        advanceUntilIdle()

        verify(settingsRepository, never()).setMaxHrCardDismissed()
        // The next visit, with the statement never having arrived: asked again.
        val nextVisit = viewModel()
        advanceUntilIdle()
        assertNotNull(nextVisit.state.value.maxHrCard)
    }

    @Test
    fun `keeping the current value is a statement too`() = runTest(dispatcher) {
        // #103: the card *is* the first set, so "keep 190" is the runner saying 190 is right — and
        // history is re-worked against it exactly as a typed number would be.
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.maxHrConfirmed(190)
        advanceUntilIdle()

        assertEquals(listOf(190 to null), stated)
    }

    @Test
    fun `closing the card states nothing`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.maxHrCardDismissed()
        advanceUntilIdle()

        assertEquals(emptyList<Pair<Int?, Int?>>(), stated)
        assertNull(viewModel.state.value.maxHrCard)
        // Recorded, unlike a confirmation: nothing else will ever record it, and a runner who
        // declined to answer must not be asked again for having declined.
        verify(settingsRepository).setMaxHrCardDismissed()
    }

    @Test
    fun `the card leaves the screen as soon as the statement lands`() = runTest(dispatcher) {
        whenever(sampleDao.getHighestSustainedBpm(any())).thenReturn(181)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertNotNull(viewModel.state.value.maxHrCard)

        // What the repository publishes once the statement has been applied.
        settings.value = UserSettings(maxHr = 181, maxHrEverSet = true, restingHr = 60)
        advanceUntilIdle()

        assertNull(viewModel.state.value.maxHrCard)
    }

    @Test
    fun `goals are measured against the period the runner is in`() = runTest(dispatcher) {
        // Today is Wednesday 5 August 2026, so this week began on the Monday.
        val monday = LocalDate.of(2026, 8, 3)
        runVolumes.value = listOf(
            volumeOn(monday.minusDays(1), km = 12.0),
            volumeOn(monday, km = 10.0),
            volumeOn(today, km = 4.0),
        )
        goalRows.value = listOf(
            GoalRow(id = 7, period = GoalPeriod.WEEK, metric = GoalMetric.DISTANCE, target = 40.0, createdAtMillis = 1),
        )
        val viewModel = viewModel()
        advanceUntilIdle()

        val progress = viewModel.state.value.goals.single()
        assertEquals(7L, progress.goal.id)
        assertEquals(monday, progress.periodStart)
        assertEquals(14.0, progress.done, 0.0001)
    }

    @Test
    fun `a runner with no goals is handed none`() = runTest(dispatcher) {
        runVolumes.value = listOf(volumeOn(today, km = 10.0))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(emptyList<Any>(), viewModel.state.value.goals)
    }

    @Test
    fun `setting a goal writes it, stamped with when it was set`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.goalSet(GoalPeriod.MONTH, GoalMetric.TIME, 12.0)
        advanceUntilIdle()

        verify(goalDao).setGoal(
            GoalRow(
                period = GoalPeriod.MONTH,
                metric = GoalMetric.TIME,
                target = 12.0,
                createdAtMillis = setAt,
            )
        )
    }

    @Test
    fun `editing a goal keeps it the same goal, in the same place`() = runTest(dispatcher) {
        val standing = GoalRow(
            id = 7,
            period = GoalPeriod.WEEK,
            metric = GoalMetric.DISTANCE,
            target = 40.0,
            createdAtMillis = 1_600_000_000_000L,
        )
        whenever(goalDao.goalFor(GoalPeriod.WEEK, GoalMetric.DISTANCE)).thenReturn(standing)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.goalSet(GoalPeriod.WEEK, GoalMetric.DISTANCE, 45.0)
        advanceUntilIdle()

        // The same row and the same day it was first set: a corrected target must not send the goal
        // to the bottom of the card for having been corrected.
        verify(goalDao).setGoal(standing.copy(target = 45.0))
    }

    @Test
    fun `removing a goal removes that goal and nothing else`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.goalRemoved(
            Goal(id = 7, period = GoalPeriod.WEEK, metric = GoalMetric.DISTANCE, target = 40.0)
        )
        advanceUntilIdle()

        verify(goalDao).deleteGoal(7L)
    }

    /** When a goal set in these tests is stamped as having been set. */
    private val setAt = 1_700_000_000_000L

    private fun viewModel() = ProgressViewModel(
        SessionRepository(sessionDao = sessionDao, sampleDao = sampleDao),
        settingsRepository = settingsRepository,
        stateHeartRates = { maxHr, restingHr -> stated += maxHr to restingHr },
        goalDao = goalDao,
        zone = zone,
        today = { today },
        now = { setAt },
        curveDispatcher = dispatcher,
    )

    private fun runOn(date: LocalDate, score: Int) = ScoredRunProjection(
        startTime = date.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli(),
        effortScore = score,
    )

    private fun volumeOn(
        date: LocalDate,
        km: Double = 0.0,
        seconds: Long = 0,
        score: Int? = null,
    ) = RunVolumeProjection(
        startTime = date.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli(),
        distanceKm = km,
        durationSeconds = seconds,
        movingTimeSeconds = null,
        effortScore = score,
    )
}
