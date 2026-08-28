package com.example.runningapp.ui

import com.example.runningapp.SettingsRepository
import com.example.runningapp.UserSettings
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.Achievement
import com.example.runningapp.data.AchievementDao
import com.example.runningapp.data.AiCoachClient
import com.example.runningapp.data.RunSummaryDao
import com.example.runningapp.data.RunSummaryRow
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * When the page asks for a summary, and — more to the point — when it stops asking (#76).
 *
 * The first open of a Run's page asks without the runner doing anything, from an effect watching
 * state the ask itself moves. A phone with no signal is therefore the case worth pinning: without a
 * record of having tried, the failure that cleared the spinner would set the whole thing off again,
 * for as long as the page stayed open, at the price of a network call each time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelRunSummaryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val finishedRun = RunnerSession(
        id = 7,
        startTime = 1_786_514_400_000L,
        endTime = 1_786_514_400_000L + 1_650_000,
        durationSeconds = 1_650,
        // Measured against the book and walked against the Segments: a Run whose facts have
        // settled, which is the state every ask below is allowed to happen in.
        recordsScored = true,
        segmentsTimed = true,
    )

    /** The second Run, for the case where two of them are in play at once. */
    private val otherFinishedRun = finishedRun.copy(id = 9)

    /**
     * A store holding both Runs, finished and fully measured.
     *
     * [shapesOwed] is the one debt left loose, because it is the cheapest way to hold a Run's facts
     * unsettled: it is history's debt rather than the Run's, so the Run itself can be whole while
     * there is still something to find out about what it is worth.
     */
    private fun sessionDaoOverBothRuns(shapesOwed: Flow<Boolean> = flowOf(false)) =
        mock<SessionDao> {
            onBlocking { getSessionById(7) } doReturn finishedRun
            onBlocking { getSessionById(9) } doReturn otherFinishedRun
            on { getSessionByIdFlow(7) } doReturn flowOf(finishedRun)
            on { getSessionByIdFlow(9) } doReturn flowOf(otherFinishedRun)
            on { anyRecordScoringOwedFlow() } doReturn flowOf(false)
            on { anySegmentTimingOwedFlow() } doReturn flowOf(false)
            on { anyRunShapeOwedFlow() } doReturn shapesOwed
        }

    private fun viewModelOver(
        client: AiCoachClient,
        medals: List<Achievement> = emptyList(),
        alreadyWritten: RunSummaryRow? = null,
        shapesOwed: Flow<Boolean> = flowOf(false),
    ) = SessionDetailViewModel(
        SessionRepository(
            sessionDao = sessionDaoOverBothRuns(shapesOwed),
            achievementDao = mock<AchievementDao> {
                onBlocking { getAchievementsForSessions(listOf(7)) } doReturn medals
            },
            runSummaryDao = mock<RunSummaryDao> {
                onBlocking { summary(7) } doReturn alreadyWritten
            },
            settingsRepository = mock<SettingsRepository> {
                on { userSettingsFlow } doReturn flowOf(UserSettings(aiDataSharingEnabled = true))
            },
            aiCoachClient = client,
        )
    )

    private fun modelSaying(text: String?) = mock<AiCoachClient> {
        on { canBeAsked } doReturn true
        onBlocking { summariseRun(any()) } doReturn text
    }

    @Test
    fun `the page asks once, however many times the effect re-runs`() = runTest(dispatcher) {
        val client = modelSaying("You were quick today.")
        val viewModel = viewModelOver(client)

        repeat(4) { viewModel.requestRunSummary(7) }
        advanceUntilIdle()

        verify(client, times(1)).summariseRun(any())
    }

    /**
     * The race the settled gate exists to close, pinned.
     *
     * The prompt is gathered *inside* the ask, from a one-shot read. Built instead out of the
     * screen's watched reads — which begin empty and fill a frame later — a Run could be told for
     * ever that it won nothing, on a page showing the medal it won.
     */
    @Test
    fun `the medals the run holds are in the prompt that is sent`() = runTest(dispatcher) {
        val client = modelSaying("words")
        val viewModel = viewModelOver(
            client,
            medals = listOf(
                Achievement(sessionId = 7, type = RecordType.FASTEST_5K, medal = Medal.GOLD, value = 1_471.0)
            ),
        )

        viewModel.requestRunSummary(7)
        advanceUntilIdle()

        val sent = argumentCaptor<String>()
        verify(client).summariseRun(sent.capture())
        assertTrue(sent.firstValue.contains("Gold at Fastest 5 km (24:31)"))
    }

    @Test
    fun `a failure is reported against the run that asked, and is not retried by itself`() = runTest(dispatcher) {
        val client = modelSaying(null)
        val viewModel = viewModelOver(client)

        viewModel.requestRunSummary(7)
        advanceUntilIdle()

        assertEquals(setOf(7L), viewModel.summaryFailed.value)
        assertTrue(viewModel.summaryWriting.value.isEmpty())

        viewModel.requestRunSummary(7)
        advanceUntilIdle()
        verify(client, times(1)).summariseRun(any())
    }

    @Test
    fun `the runner asking again does ask again`() = runTest(dispatcher) {
        val client = modelSaying(null)
        val viewModel = viewModelOver(client)

        viewModel.requestRunSummary(7)
        advanceUntilIdle()
        viewModel.regenerateRunSummary(7)
        advanceUntilIdle()

        verify(client, times(2)).summariseRun(any())
    }

    @Test
    fun `asking again clears the last failure before it knows how this one ends`() = runTest(dispatcher) {
        val viewModel = viewModelOver(modelSaying(null))

        viewModel.requestRunSummary(7)
        advanceUntilIdle()
        assertEquals(setOf(7L), viewModel.summaryFailed.value)

        viewModel.regenerateRunSummary(7)
        assertTrue(viewModel.summaryFailed.value.isEmpty())
        assertEquals(setOf(7L), viewModel.summaryWriting.value)
    }

    /**
     * The write-once promise, held against the moment the page cannot see past.
     *
     * The page's watch of the stored words begins as null, and a Run already written about holds
     * that null until the store answers. If the ask trusted the page's null, opening a Run for the
     * second time would send it away again and write new words over the kept ones — for ever, every
     * time, on the runs the runner has read the most.
     */
    @Test
    fun `a run that has already been written about is never sent again`() = runTest(dispatcher) {
        val client = modelSaying("new words")
        val viewModel = viewModelOver(
            client,
            alreadyWritten = RunSummaryRow(sessionId = 7, text = "kept words", writtenAtMillis = 1L),
        )

        viewModel.requestRunSummary(7)
        advanceUntilIdle()

        verify(client, never()).summariseRun(any())
        assertTrue(viewModel.summaryWriting.value.isEmpty())
        assertTrue(viewModel.summaryFailed.value.isEmpty())
        assertTrue(viewModel.summaryRefused.value.isEmpty())
    }

    /** The runner's own word still replaces what is there — that is what the button is for. */
    @Test
    fun `the runner can write over words that are already kept`() = runTest(dispatcher) {
        val client = modelSaying("new words")
        val viewModel = viewModelOver(
            client,
            alreadyWritten = RunSummaryRow(sessionId = 7, text = "kept words", writtenAtMillis = 1L),
        )

        viewModel.regenerateRunSummary(7)
        advanceUntilIdle()

        verify(client, times(1)).summariseRun(any())
    }

    @Test
    fun `a refusal is not a failure — there is nothing for the runner to retry`() = runTest(dispatcher) {
        // Sharing switched off: the app declines to ask at all.
        val client = modelSaying("words")
        val viewModel = SessionDetailViewModel(
            SessionRepository(
                sessionDao = sessionDaoOverBothRuns(),
                runSummaryDao = mock<RunSummaryDao>(),
                settingsRepository = mock<SettingsRepository> {
                    on { userSettingsFlow } doReturn flowOf(UserSettings(aiDataSharingEnabled = false))
                },
                aiCoachClient = client,
            )
        )

        viewModel.requestRunSummary(7)
        advanceUntilIdle()

        assertTrue(viewModel.summaryFailed.value.isEmpty())
        assertTrue(viewModel.summaryWriting.value.isEmpty())
        // Said rather than swallowed: the runner pressing the button must not watch nothing happen.
        assertEquals(setOf(7L), viewModel.summaryRefused.value)
    }

    /**
     * Finding A (#76): "write it again" is held to the settled-facts rule the first ask is.
     *
     * The words it writes are kept exactly as long as the first ones, so an ask made while history
     * still owes a shape — a launch pass working through old Runs — would freeze "no records, run
     * once" onto a Run that is about to be told otherwise. The card does not offer the button
     * before then; this is the sliver between the offer and the press, where the spinner is up and
     * the ask simply waits.
     */
    @Test
    fun `the runner's own ask waits for the facts to settle`() = runTest(dispatcher) {
        val shapesOwed = MutableStateFlow(true)
        val client = modelSaying("words")
        val viewModel = viewModelOver(client, shapesOwed = shapesOwed)

        viewModel.regenerateRunSummary(7)
        advanceUntilIdle()

        verify(client, never()).summariseRun(any())
        // Waiting, not refusing: the runner is looking at a card that says it is working on it.
        assertEquals(setOf(7L), viewModel.summaryWriting.value)

        shapesOwed.value = false
        advanceUntilIdle()

        verify(client, times(1)).summariseRun(any())
        assertTrue(viewModel.summaryWriting.value.isEmpty())
    }

    /**
     * #350: the rebuild after the model answers waits for the same all-clear the first build did.
     *
     * The repository builds the prompt again once the words land, to see whether the Run changed
     * while it was being written about. The changes that make it change — a Walk marked, a treadmill
     * distance corrected, a rival Run finished — all raise a measurement debt and repay it, so the
     * moment of the rebuild is the moment a pass is most likely to be mid-flight. Building then
     * would send a half-measured Run and keep the answer for ever, which is the whole thing the
     * first ask waits to avoid.
     */
    @Test
    fun `the rebuild after the words land waits for the facts to settle again`() = runTest(dispatcher) {
        val shapesOwed = MutableStateFlow(false)
        // Answering raises a debt, exactly as a pass kicked off by the runner's own edit would.
        val client = mock<AiCoachClient> {
            on { canBeAsked } doReturn true
            onBlocking { summariseRun(any()) } doSuspendableAnswer {
                shapesOwed.value = true
                "words"
            }
        }
        val summaries = mock<RunSummaryDao>()
        val viewModel = SessionDetailViewModel(
            SessionRepository(
                sessionDao = sessionDaoOverBothRuns(shapesOwed),
                achievementDao = mock<AchievementDao> {
                    onBlocking { getAchievementsForSessions(listOf(7)) } doReturn emptyList()
                },
                runSummaryDao = summaries,
                settingsRepository = mock<SettingsRepository> {
                    on { userSettingsFlow } doReturn flowOf(UserSettings(aiDataSharingEnabled = true))
                },
                aiCoachClient = client,
            )
        )

        viewModel.requestRunSummary(7)
        advanceUntilIdle()

        // The model has spoken and nothing has been kept: history is being measured again.
        verify(client, times(1)).summariseRun(any())
        verify(summaries, never()).put(any())
        assertEquals(setOf(7L), viewModel.summaryWriting.value)

        shapesOwed.value = false
        advanceUntilIdle()

        verify(summaries, times(1)).put(any())
        assertTrue(viewModel.summaryWriting.value.isEmpty())
    }

    /** And the button is not there to press until then, which is the half the runner can see. */
    @Test
    fun `the button is not offered while the run is still being measured`() {
        val stillMeasuring = RunSummaryUi(text = "words", isWriting = false, failed = false, factsSettled = false)
        assertFalse(stillMeasuring.canAskAgain)
        assertTrue(stillMeasuring.copy(factsSettled = true).canAskAgain)
    }

    /**
     * Finding D (#76): words already written plus sharing switched off offers nothing to press.
     *
     * This is the case no refusal covers. A Run written about while sharing was on is never asked
     * about again — the ask reads the store and returns — so switching sharing off afterwards
     * leaves it with its words, no refusal, and a button whose only possible outcome is being
     * turned down. Pressing it would put "no summary for this run" under the summary that is
     * plainly there.
     */
    @Test
    fun `words already written are not offered a re-ask once sharing is switched off`() {
        val writtenThenSwitchedOff = RunSummaryUi(
            text = "You were quick today.",
            isWriting = false,
            failed = false,
            sharingAllowed = false,
        )

        assertFalse(writtenThenSwitchedOff.canAskAgain)
        // And there is still a card, showing the words: switching the switch takes away the offer,
        // not the runner's summary.
        assertTrue(writtenThenSwitchedOff.hasSomethingToSay)
        assertFalse(writtenThenSwitchedOff.refused)

        // Switch back on, button back.
        assertTrue(writtenThenSwitchedOff.copy(sharingAllowed = true).canAskAgain)
    }

    /**
     * And the switch the card is told about is the switch itself, watched (#76).
     *
     * The setting can move while the Run's page is open. Read once at construction it would go on
     * offering a button the repository would only refuse; the ViewModel therefore holds the live
     * answer, and defaults to offering the button where nothing supplies the setting at all.
     */
    @Test
    fun `the card is told the live sharing setting, and told yes where nothing supplies one`() =
        runTest(dispatcher) {
            val sharingOn = MutableStateFlow(true)
            val viewModel = viewModelWatching(sharingOn, modelSaying("words"))
            advanceUntilIdle()
            assertTrue(viewModel.summariesAllowed.value)

            sharingOn.value = false
            advanceUntilIdle()
            assertFalse(viewModel.summariesAllowed.value)

            sharingOn.value = true
            advanceUntilIdle()
            assertTrue(viewModel.summariesAllowed.value)

            // Nothing supplying the setting must not hide the button for ever.
            assertTrue(viewModelOver(modelSaying("words")).summariesAllowed.value)
        }

    /**
     * Finding B (#76): one Run's failure does not rub out another's.
     *
     * This ViewModel lives as long as the activity, so a runner underground with two Runs open in
     * turn holds two failures at once. Kept as a single id, the second would erase the first —
     * and going back to the first would show no failure, no button, and no fresh ask, because the
     * once-per-launch guard remembers it perfectly well. That Run would be stuck until the app was
     * restarted.
     */
    @Test
    fun `a failure on one run leaves another run's failure standing`() = runTest(dispatcher) {
        val client = modelSaying(null)
        val viewModel = viewModelOver(client)

        viewModel.requestRunSummary(7)
        advanceUntilIdle()
        viewModel.requestRunSummary(9)
        advanceUntilIdle()

        assertEquals(setOf(7L, 9L), viewModel.summaryFailed.value)
        assertTrue(viewModel.summaryWriting.value.isEmpty())
    }

    /**
     * A viewModel over one Run whose settings can move under it, and which is told when they do.
     *
     * [sharingOn] is the same switch twice over: it is what the repository reads when it decides
     * whether to ask, and what this ViewModel watches so a refusal it caused stops standing once it
     * is moved back — the two must be one switch, or the test proves nothing about the app.
     */
    private fun viewModelWatching(
        sharingOn: MutableStateFlow<Boolean>,
        client: AiCoachClient,
        session: RunnerSession = finishedRun,
    ) = SessionDetailViewModel(
        SessionRepository(
            sessionDao = mock<SessionDao> {
                onBlocking { getSessionById(7) } doReturn session
                on { getSessionByIdFlow(7) } doReturn flowOf(session)
                on { anyRecordScoringOwedFlow() } doReturn flowOf(false)
                on { anySegmentTimingOwedFlow() } doReturn flowOf(false)
                on { anyRunShapeOwedFlow() } doReturn flowOf(false)
            },
            achievementDao = mock<AchievementDao> {
                onBlocking { getAchievementsForSessions(listOf(7)) } doReturn emptyList()
            },
            runSummaryDao = mock<RunSummaryDao>(),
            settingsRepository = mock<SettingsRepository> {
                on { userSettingsFlow } doReturn sharingOn.map { UserSettings(aiDataSharingEnabled = it) }
            },
            aiCoachClient = client,
        ),
        aiSummariesAllowed = sharingOn,
    )

    /**
     * Finding C (#76): a refusal the runner has just made obsolete does not outlive the switch.
     *
     * Sharing off, open the Run, read the line saying why. Turn sharing on in Settings and come
     * back to the same Run — the same activity, so the same ViewModel, which remembers both the
     * refusal and the fact that it has already asked. Held on to, those two together would hide the
     * line's replacement *and* the button for as long as the app stayed running.
     */
    @Test
    fun `turning sharing back on lets a refused run be asked about again`() = runTest(dispatcher) {
        val sharingOn = MutableStateFlow(false)
        val client = modelSaying("You were quick today.")
        val viewModel = viewModelWatching(sharingOn, client)

        viewModel.requestRunSummary(7)
        advanceUntilIdle()
        assertEquals(setOf(7L), viewModel.summaryRefused.value)
        verify(client, never()).summariseRun(any())

        sharingOn.value = true
        advanceUntilIdle()
        // The refusal is no longer true, so it is no longer said: the card is back to the quiet
        // nothing it shows for a Run that has not been written about yet.
        assertTrue(viewModel.summaryRefused.value.isEmpty())

        // Coming back to the Run — the page's own ask, exactly as on a first open.
        viewModel.requestRunSummary(7)
        advanceUntilIdle()

        verify(client, times(1)).summariseRun(any())
        assertTrue(viewModel.summaryRefused.value.isEmpty())
        assertTrue(viewModel.summaryFailed.value.isEmpty())
    }

    /**
     * And the consent given at START is not undone by a switch moved afterwards.
     *
     * A Run recorded while sharing was off is stamped as one that is never sent, whatever the
     * switch says today. Turning sharing on gets it re-asked — which is the cheap half of leaving
     * the rule with the repository — and it is refused again, having reached nothing.
     */
    @Test
    fun `a run recorded under an opt-out is still refused after sharing comes back`() = runTest(dispatcher) {
        val sharingOn = MutableStateFlow(false)
        val client = modelSaying("words")
        val viewModel = viewModelWatching(
            sharingOn,
            client,
            session = finishedRun.copy(includeInAiTraining = false),
        )

        viewModel.requestRunSummary(7)
        advanceUntilIdle()
        assertEquals(setOf(7L), viewModel.summaryRefused.value)

        sharingOn.value = true
        advanceUntilIdle()
        viewModel.requestRunSummary(7)
        advanceUntilIdle()

        // Refused again, and nothing about this Run ever left the phone.
        assertEquals(setOf(7L), viewModel.summaryRefused.value)
        assertTrue(viewModel.summaryFailed.value.isEmpty())
        verify(client, never()).summariseRun(any())
    }
}
