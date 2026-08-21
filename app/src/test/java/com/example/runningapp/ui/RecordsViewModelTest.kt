package com.example.runningapp.ui

import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.RecordEffortRow
import com.example.runningapp.data.RunEffortDao
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * What the Records section is handed while history is still being measured against the book (#75).
 *
 * `run_efforts` is filled a Run at a time, and the upgrade that created it cleared every Run's
 * scoring mark so that the whole of history would be re-measured — minutes of work at one launch.
 * A grid or a top ten read off the slice that pass has reached so far is not an all-time anything:
 * it would hand gold to whichever Run happened to be measured first and put runs in fourth place
 * that never placed at all. So the section says what it is doing until the table is whole.
 *
 * The other half of the rule is what the gate must *not* do: a Run finishing raises the same debt
 * on itself for as long as its own scoring takes, and blanking the runner's records for a moment
 * after every Run, for ever, would be worse than the bug. The last two tests here are what pin that
 * down — see [SessionRepository.recordsBeingMeasuredFlow] for the argument.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone: ZoneId = ZoneId.of("Europe/London")

    private val sessionDao: SessionDao = mock()
    private val runEffortDao: RunEffortDao = mock()

    /** How many finished Runs are owed a scoring right now — a var, because a pass pays them off. */
    private val owedScorings = MutableStateFlow(0)
    private val efforts = MutableStateFlow(emptyList<RecordEffortRow>())
    private val zoneChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(sessionDao.countSessionsMissingRecordScoringFlow()).thenReturn(owedScorings)
        whenever(runEffortDao.getRecordEffortsFlow()).thenReturn(efforts)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a history part-way through being measured shows no records at all`() = runTest(dispatcher) {
        // The picture the first launch after the upgrade paints: the pass has scored two runs of a
        // long history so far, and those two claims are all the table holds.
        owedScorings.value = 40
        efforts.value = listOf(effort(sessionId = 1L, seconds = 1_800.0), effort(sessionId = 2L, seconds = 1_700.0))

        val viewModel = viewModel()
        val grid = viewModel.grid
        watch(viewModel)
        advanceUntilIdle()

        // Not "28:20 is your all-time best 5k" — the runner's real best may be any of the thirty-
        // eight runs nobody has measured yet.
        assertTrue(grid.value.measuring)
        assertEquals(emptyList<RecordSlotUi>(), grid.value.slots)
        // And the Record's own page, which is the one that prints places down to tenth, says the
        // same rather than ranking two runs one and two.
        val detail = viewModel.detail(RecordType.FASTEST_5K).first()
        assertTrue(detail.measuring)
        assertEquals(emptyList<RecordRankedEffortUi>(), detail.top)
        assertEquals(emptyList<RecordTrendPoint>(), detail.trend)
        assertEquals(0, detail.effortCount)
    }

    @Test
    fun `the records come back by themselves once the measuring finishes`() = runTest(dispatcher) {
        owedScorings.value = 40
        val viewModel = viewModel()
        watch(viewModel)
        advanceUntilIdle()
        assertTrue(viewModel.grid.value.measuring)

        // The launch pass works through the history and the table fills.
        efforts.value = listOf(effort(sessionId = 1L, seconds = 1_500.0), effort(sessionId = 2L, seconds = 1_700.0))
        owedScorings.value = 0
        advanceUntilIdle()

        assertFalse(viewModel.grid.value.measuring)
        assertEquals(RecordType.entries.size, viewModel.grid.value.slots.size)
        val fiveK = viewModel.grid.value.slots.first { it.type == RecordType.FASTEST_5K }
        assertEquals("25:00", fiveK.best?.valueLabel)
    }

    @Test
    fun `one run waiting on its own scoring does not hide the records`() = runTest(dispatcher) {
        // What every ordinary Run looks like for the second or two between STOP and its scoring
        // landing: one debt, and a table that is whole apart from that Run.
        owedScorings.value = 1
        efforts.value = listOf(effort(sessionId = 1L, seconds = 1_500.0))

        val viewModel = viewModel()
        watch(viewModel)
        advanceUntilIdle()

        // What stands is every claim but the newest, which was the right answer a moment ago and
        // becomes the right answer as of now the moment that Run is scored. Hiding it here would
        // blank the runner's records after every Run they ever do.
        assertFalse(viewModel.grid.value.measuring)
        assertEquals("25:00", viewModel.grid.value.slots.first { it.type == RecordType.FASTEST_5K }.best?.valueLabel)
        assertFalse(viewModel.detail(RecordType.FASTEST_5K).first().measuring)
    }

    @Test
    fun `a history with nothing owed shows its records`() = runTest(dispatcher) {
        owedScorings.value = 0
        efforts.value = listOf(effort(sessionId = 1L, seconds = 1_500.0))

        val viewModel = viewModel()
        watch(viewModel)
        advanceUntilIdle()

        assertFalse(viewModel.grid.value.measuring)
        assertEquals(1, viewModel.detail(RecordType.FASTEST_5K).first().effortCount)
    }

    /**
     * Somebody looking at the Records section, which is what makes the grid a live answer: it is
     * shared while the screen is subscribed ([kotlinx.coroutines.flow.SharingStarted.WhileSubscribed])
     * and holds its last value when nobody is, exactly as it does on the phone.
     */
    private fun TestScope.watch(viewModel: RecordsViewModel) {
        backgroundScope.launch(dispatcher) { viewModel.grid.collect { } }
    }

    private fun viewModel() = RecordsViewModel(
        SessionRepository(sessionDao = sessionDao, runEffortDao = runEffortDao),
        zone = { zone },
        zoneChanges = zoneChanges,
        recordsDispatcher = dispatcher,
    )

    private fun effort(sessionId: Long, seconds: Double) = RecordEffortRow(
        sessionId = sessionId,
        type = RecordType.FASTEST_5K,
        value = seconds,
        startTime = 1_700_000_000_000L + sessionId * 86_400_000L,
        ranAtUtcOffsetSeconds = 0,
    )
}
