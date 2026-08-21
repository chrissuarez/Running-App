package com.example.runningapp.ui

import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.RecordEffortRow
import com.example.runningapp.data.RecordFillDao
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
 * The other half of the rule is what the gate must *not* do: a Run finishing owes a scoring of its
 * own for a second or two, and blanking the runner's records for a moment after every Run, for ever,
 * would be worse than the bug. What tells the two apart is that a wholesale fill is a fact the
 * database holds while one is under way, rather than a number of debts to be counted — an ordinary
 * Run's scoring never raises it. `one run waiting on its own scoring does not hide the records` is
 * what pins that down; see [SessionRepository.recordsBeingMeasuredFlow] for the argument.
 *
 * **And a third state, which is neither of those (#75):** the efforts have not come back from Room
 * at all yet. `record_fill` is one small row and answers at once, the efforts are a join over the
 * whole of history and answer frames later, so there is always a moment on a cold open where
 * nothing is being measured and nothing has been read. Handing a screen an empty history in that
 * moment is a statement — seven slots reading "Not run yet", and an "you have never run this"
 * message on a Record the runner just tapped a time on. The tests from
 * `the grid says nothing at all until the efforts come back` down are what hold the three apart, and
 * they stage exactly that order: the fill answers false first, and the efforts do not answer at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecordsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone: ZoneId = ZoneId.of("Europe/London")

    private val sessionDao: SessionDao = mock()
    private val runEffortDao: RunEffortDao = mock()
    private val recordFillDao: RecordFillDao = mock()

    /** Whether a wholesale fill is outstanding — a var, because the pass that fills hands it back. */
    private val fillOwed = MutableStateFlow(false)
    private val efforts = MutableStateFlow(emptyList<RecordEffortRow>())

    /**
     * The effort join before it has answered — a stream that has emitted nothing at all (#75).
     *
     * A shared flow rather than a state, because that absence is the point: a `MutableStateFlow`
     * always holds a value and so can only ever stage a table that has already answered, which is
     * precisely the state this fault was hiding behind. Replay of one so that a collector arriving
     * after the answer still sees it, as Room's own query does.
     */
    private val unansweredEfforts = MutableSharedFlow<List<RecordEffortRow>>(replay = 1)
    private val zoneChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        whenever(recordFillDao.wholesaleFillOwedFlow()).thenReturn(fillOwed)
        whenever(runEffortDao.getRecordEffortsFlow()).thenReturn(efforts)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a history part-way through being measured shows no records at all`() = runTest(dispatcher) {
        // The picture the first launch after the upgrade paints: the pass has scored two runs of a
        // long history so far, and those two claims are all the table holds.
        fillOwed.value = true
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
        fillOwed.value = true
        val viewModel = viewModel()
        watch(viewModel)
        advanceUntilIdle()
        assertTrue(viewModel.grid.value.measuring)

        // The launch pass works through the history and the table fills.
        efforts.value = listOf(effort(sessionId = 1L, seconds = 1_500.0), effort(sessionId = 2L, seconds = 1_700.0))
        fillOwed.value = false
        advanceUntilIdle()

        assertFalse(viewModel.grid.value.measuring)
        assertEquals(RecordType.entries.size, viewModel.grid.value.slots?.size)
        val fiveK = viewModel.grid.value.slots.orEmpty().first { it.type == RecordType.FASTEST_5K }
        assertEquals("25:00", fiveK.best?.valueLabel)
    }

    @Test
    fun `one run waiting on its own scoring does not hide the records`() = runTest(dispatcher) {
        // What every ordinary Run looks like for the second or two between STOP and its scoring
        // landing: that Run owes a scoring, and nothing has raised a wholesale fill — the table is
        // whole apart from that one Run.
        fillOwed.value = false
        efforts.value = listOf(effort(sessionId = 1L, seconds = 1_500.0))

        val viewModel = viewModel()
        watch(viewModel)
        advanceUntilIdle()

        // What stands is every claim but the newest, which was the right answer a moment ago and
        // becomes the right answer as of now the moment that Run is scored. Hiding it here would
        // blank the runner's records after every Run they ever do.
        assertFalse(viewModel.grid.value.measuring)
        assertEquals(
            "25:00",
            viewModel.grid.value.slots.orEmpty().first { it.type == RecordType.FASTEST_5K }.best?.valueLabel,
        )
        assertFalse(viewModel.detail(RecordType.FASTEST_5K).first().measuring)
    }

    @Test
    fun `the page a record is opened on says nothing until the efforts come back`() {
        // The frame between the tap and Room answering (#75). The runner has just tapped a cell
        // reading "25:00", so the one thing this page must not say is that they have never run 5k.
        val opening = recordDetailNotReadYet(RecordType.FASTEST_5K)
        assertEquals(null, opening.top)
        assertEquals(null, recordDetailMessage(opening))

        // The same page once the read has answered, and answered "none": now the message is owed,
        // because now it is true.
        val answeredEmpty = RecordDetailUi(
            type = RecordType.FASTEST_5K,
            top = emptyList(),
            trend = emptyList(),
            effortCount = 0,
        )
        assertEquals(recordEmptyMessage(RecordType.FASTEST_5K), recordDetailMessage(answeredEmpty))

        // And the third bare page: the read answered, deliberately with nothing, because history is
        // still being measured against the book.
        assertEquals(
            RECORDS_MEASURING_MESSAGE,
            recordDetailMessage(answeredEmpty.copy(measuring = true)),
        )
    }

    @Test
    fun `a reading taken while history is being measured is an answer, not a page still opening`() =
        runTest(dispatcher) {
            // The two bare pages told apart at the view model rather than only in the wording (#75).
            // Being measured means the table *was* read and the answer is deliberately nothing, so
            // what comes back is an empty top ten with the flag raised — never the null that means
            // nothing has been asked yet, which is the state of the test below this one.
            fillOwed.value = true
            val viewModel = viewModel()
            watch(viewModel)
            advanceUntilIdle()
            assertEquals(emptyList<RecordRankedEffortUi>(), viewModel.detail(RecordType.FASTEST_5K).first().top)

            fillOwed.value = false
            efforts.value = listOf(effort(sessionId = 1L, seconds = 1_500.0))
            advanceUntilIdle()
            val read = viewModel.detail(RecordType.FASTEST_5K).first()
            assertEquals(1, read.top?.size)
            assertEquals(null, recordDetailMessage(read))
        }

    @Test
    fun `the grid says nothing at all until the efforts come back`() = runTest(dispatcher) {
        // The exact order a cold open of the Progress screen happens in (#75): `record_fill` is one
        // small row and answers at once with "nothing owed", while the efforts are a join over the
        // whole of history and land frames later. Before this was one fact, that gap was enough for
        // the grid to be handed seven slots read off no rows at all — a runner with years of runs
        // shown "Not run yet" seven times over, for as long as the join took.
        fillOwed.value = false
        whenever(runEffortDao.getRecordEffortsFlow()).thenReturn(unansweredEfforts)

        val viewModel = viewModel()
        watch(viewModel)
        advanceUntilIdle()

        // Not seven empty slots, and not a measuring section either — nothing has been asked yet,
        // so the section is not drawn at all until there is something true to draw.
        assertEquals(recordsGridNotReadYet(), viewModel.grid.value)
        assertEquals(null, viewModel.grid.value.slots)
        assertFalse(viewModel.grid.value.measuring)

        // The same fact, said the same way, on the page behind a cell. The screen opens itself on
        // [recordDetailNotReadYet]; what the view model hands it first must not undo that.
        val opening = viewModel.detail(RecordType.FASTEST_5K).first()
        assertEquals(recordDetailNotReadYet(RecordType.FASTEST_5K), opening)
        assertEquals(null, opening.top)
        assertEquals(null, recordDetailMessage(opening))
        assertFalse(opening.measuring)
    }

    @Test
    fun `a runner with no history is told so, but only once the efforts have answered`() =
        runTest(dispatcher) {
            // The other half of the rule, and the reason the unread state cannot simply be silence
            // for ever: a genuinely empty table is a real answer and the runner is owed the words.
            fillOwed.value = false
            whenever(runEffortDao.getRecordEffortsFlow()).thenReturn(unansweredEfforts)

            val viewModel = viewModel()
            watch(viewModel)
            advanceUntilIdle()
            assertEquals(null, viewModel.grid.value.slots)

            // Room answers, and the answer is "none".
            unansweredEfforts.emit(emptyList())
            advanceUntilIdle()

            assertEquals(RecordType.entries.size, viewModel.grid.value.slots?.size)
            assertTrue(viewModel.grid.value.slots.orEmpty().all { it.best == null })
            val answered = viewModel.detail(RecordType.FASTEST_5K).first()
            assertEquals(emptyList<RecordRankedEffortUi>(), answered.top)
            assertEquals(recordEmptyMessage(RecordType.FASTEST_5K), recordDetailMessage(answered))
        }

    @Test
    fun `a record that has been run is never called unrun on the way in`() = runTest(dispatcher) {
        // The whole point, followed through: the runner taps a cell reading 25:00 and the page must
        // go from silence to that time, without passing through "you have not covered 5 km in a run
        // yet" on the way.
        fillOwed.value = false
        whenever(runEffortDao.getRecordEffortsFlow()).thenReturn(unansweredEfforts)

        val viewModel = viewModel()
        watch(viewModel)
        val seen = mutableListOf<String?>()
        backgroundScope.launch(dispatcher) {
            viewModel.detail(RecordType.FASTEST_5K).collect { seen += recordDetailMessage(it) }
        }
        advanceUntilIdle()

        unansweredEfforts.emit(listOf(effort(sessionId = 1L, seconds = 1_500.0)))
        advanceUntilIdle()

        // Every message the page was ever handed: silence, then silence again because there is a
        // time to print. The empty-record sentence never appears.
        assertEquals(listOf<String?>(null, null), seen)
        assertEquals("25:00", viewModel.detail(RecordType.FASTEST_5K).first().top?.first()?.effort?.valueLabel)
    }

    @Test
    fun `a history with nothing owed shows its records`() = runTest(dispatcher) {
        fillOwed.value = false
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
        SessionRepository(
            sessionDao = sessionDao,
            runEffortDao = runEffortDao,
            recordFillDao = recordFillDao,
        ),
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
