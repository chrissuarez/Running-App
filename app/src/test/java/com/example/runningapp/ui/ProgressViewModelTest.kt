package com.example.runningapp.ui

import com.example.runningapp.data.ScoredRunProjection
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.training.ProgressRange
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
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
    private val sessionDao: SessionDao = mock()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(sessionDao.getScoredRunsFlow()).thenReturn(scoredRuns)
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

    private fun viewModel() = ProgressViewModel(
        SessionRepository(sessionDao = sessionDao),
        zone = zone,
        today = { today },
        curveDispatcher = dispatcher,
    )

    private fun runOn(date: LocalDate, score: Int) = ScoredRunProjection(
        startTime = date.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant().toEpochMilli(),
        effortScore = score,
    )
}
