package com.example.runningapp.ui

import com.example.runningapp.SettingsRepository
import com.example.runningapp.UserSettings
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.data.Achievement
import com.example.runningapp.data.AchievementDao
import com.example.runningapp.data.AiCoachClient
import com.example.runningapp.data.RunSummaryDao
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.SessionDao
import com.example.runningapp.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
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
    )

    private fun viewModelOver(
        client: AiCoachClient,
        medals: List<Achievement> = emptyList(),
    ) = SessionDetailViewModel(
        SessionRepository(
            sessionDao = mock<SessionDao> { onBlocking { getSessionById(7) } doReturn finishedRun },
            achievementDao = mock<AchievementDao> {
                onBlocking { getAchievementsForSessions(listOf(7)) } doReturn medals
            },
            runSummaryDao = mock<RunSummaryDao>(),
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

        assertEquals(7L, viewModel.summaryFailed.value)
        assertNull(viewModel.summaryWriting.value)

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
        assertEquals(7L, viewModel.summaryFailed.value)

        viewModel.regenerateRunSummary(7)
        assertNull(viewModel.summaryFailed.value)
        assertEquals(7L, viewModel.summaryWriting.value)
    }

    @Test
    fun `a refusal is not a failure — there is nothing for the runner to retry`() = runTest(dispatcher) {
        // Sharing switched off: the app declines to ask at all.
        val client = modelSaying("words")
        val viewModel = SessionDetailViewModel(
            SessionRepository(
                sessionDao = mock<SessionDao> { onBlocking { getSessionById(7) } doReturn finishedRun },
                runSummaryDao = mock<RunSummaryDao>(),
                settingsRepository = mock<SettingsRepository> {
                    on { userSettingsFlow } doReturn flowOf(UserSettings(aiDataSharingEnabled = false))
                },
                aiCoachClient = client,
            )
        )

        viewModel.requestRunSummary(7)
        advanceUntilIdle()

        assertNull(viewModel.summaryFailed.value)
        assertNull(viewModel.summaryWriting.value)
        // Said rather than swallowed: the runner pressing the button must not watch nothing happen.
        assertEquals(7L, viewModel.summaryRefused.value)
    }
}
