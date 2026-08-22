package com.example.runningapp.data

import com.example.runningapp.SettingsRepository
import com.example.runningapp.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Who a Run's summary may be written for, and when it is safe to write one (#76).
 *
 * Two rules, and the repository is where both of them live. **Consent** is asked of the Run and of
 * the switch as it stands now, and both have to say yes. **Settledness** is the guard that keeps a
 * summary — written once and kept for ever — from describing a Run that is still being measured.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunSummaryTest {

    private val summaries = mock<RunSummaryDao>()

    private fun finishedRun(includeInAiTraining: Boolean = true) = RunnerSession(
        id = 7,
        startTime = 1_786_514_400_000L,
        endTime = 1_786_514_400_000L + 1_650_000,
        durationSeconds = 1_650,
        includeInAiTraining = includeInAiTraining,
    )

    /** A client with a key in it, saying [text] — or nothing at all. */
    private fun modelSaying(text: String?) = mock<AiCoachClient> {
        on { canBeAsked } doReturn true
        onBlocking { summariseRun(any()) } doReturn text
    }

    private fun repository(
        session: RunnerSession? = finishedRun(),
        settings: UserSettings = UserSettings(aiDataSharingEnabled = true, testingModeEnabled = false),
        modelSays: String? = "You were quick today.",
    ) = SessionRepository(
        sessionDao = mock<SessionDao> { onBlocking { getSessionById(7) } doReturn session },
        runSummaryDao = summaries,
        settingsRepository = mock<SettingsRepository> {
            on { userSettingsFlow } doReturn flowOf(settings)
        },
        aiCoachClient = modelSaying(modelSays),
    )

    // --- Consent ---

    @Test
    fun `a run recorded while sharing was on, with sharing still on, is summarised`() = runTest {
        val outcome = repository().writeRunSummary(7, "a prompt")

        assertEquals(RunSummaryOutcome.WRITTEN, outcome)
        val written = argumentCaptor<RunSummaryRow>()
        verify(summaries).put(written.capture())
        assertEquals(7L, written.firstValue.sessionId)
        assertEquals("You were quick today.", written.firstValue.text)
        assertTrue(written.firstValue.writtenAtMillis > 0)
    }

    @Test
    fun `the prompt the caller built is the prompt that is sent`() = runTest {
        val client = modelSaying("words")
        val repository = SessionRepository(
            sessionDao = mock<SessionDao> { onBlocking { getSessionById(7) } doReturn finishedRun() },
            runSummaryDao = summaries,
            settingsRepository = mock<SettingsRepository> {
                on { userSettingsFlow } doReturn flowOf(UserSettings(aiDataSharingEnabled = true))
            },
            aiCoachClient = client,
        )

        repository.writeRunSummary(7, "THE RUN\n- Distance: 5.00 km")

        verify(client).summariseRun("THE RUN\n- Distance: 5.00 km")
    }

    @Test
    fun `a run recorded under an opt-out is never sent, whatever the switch says now`() = runTest {
        val client = modelSaying("words")
        val repository = SessionRepository(
            sessionDao = mock<SessionDao> {
                onBlocking { getSessionById(7) } doReturn finishedRun(includeInAiTraining = false)
            },
            runSummaryDao = summaries,
            settingsRepository = mock<SettingsRepository> {
                on { userSettingsFlow } doReturn flowOf(UserSettings(aiDataSharingEnabled = true))
            },
            aiCoachClient = client,
        )

        assertEquals(RunSummaryOutcome.REFUSED, repository.writeRunSummary(7, "a prompt"))
        verify(client, never()).summariseRun(any())
    }

    @Test
    fun `sharing switched off now refuses a run that was recorded while it was on`() = runTest {
        val outcome = repository(
            settings = UserSettings(aiDataSharingEnabled = false)
        ).writeRunSummary(7, "a prompt")

        assertEquals(RunSummaryOutcome.REFUSED, outcome)
        verify(summaries, never()).put(any())
    }

    @Test
    fun `testing mode refuses it too`() = runTest {
        val outcome = repository(
            settings = UserSettings(aiDataSharingEnabled = true, testingModeEnabled = true)
        ).writeRunSummary(7, "a prompt")

        assertEquals(RunSummaryOutcome.REFUSED, outcome)
    }

    @Test
    fun `a run still being recorded is refused`() = runTest {
        val stillRunning = RunnerSession(id = 7, startTime = 1_786_514_400_000L, endTime = 0)

        assertEquals(RunSummaryOutcome.REFUSED, repository(session = stillRunning).writeRunSummary(7, "a prompt"))
    }

    // --- Settledness: a summary is written once, so it must not describe a half-measured Run ---

    private fun settled(
        session: RunnerSession?,
        shaped: Boolean = true,
        historyBeingMeasured: Boolean = false,
        recordScoringOwedSomewhere: Boolean = false,
        segmentWalkOwedSomewhere: Boolean = false,
        segmentHistoryWalkOwedSomewhere: Boolean = false,
        shapeOwedSomewhere: Boolean = false,
    ) = SessionRepository(
        sessionDao = mock<SessionDao> {
            on { getSessionByIdFlow(7) } doReturn flowOf(session)
            on { anyRecordScoringOwedFlow() } doReturn flowOf(recordScoringOwedSomewhere)
            on { anySegmentTimingOwedFlow() } doReturn flowOf(segmentWalkOwedSomewhere)
            on { anyRunShapeOwedFlow() } doReturn flowOf(shapeOwedSomewhere)
        },
        segmentDao = mock<SegmentDao> {
            on { anySegmentHistoryWalkOwedFlow() } doReturn flowOf(segmentHistoryWalkOwedSomewhere)
        },
        runShapeDao = mock<RunShapeDao> { on { isShapedFlow(7) } doReturn flowOf(shaped) },
        recordFillDao = mock<RecordFillDao> {
            on { wholesaleFillOwedFlow() } doReturn flowOf(historyBeingMeasured)
        },
    )

    private fun measuredRun(recordsScored: Boolean = true, segmentsTimed: Boolean = true) = RunnerSession(
        id = 7,
        startTime = 1_786_514_400_000L,
        endTime = 1_786_514_400_000L + 1_650_000,
        durationSeconds = 1_650,
        recordsScored = recordsScored,
        segmentsTimed = segmentsTimed,
    )

    @Test
    fun `a run measured against everything is settled`() = runTest {
        assertTrue(settled(measuredRun()).runSummaryFactsSettledFlow(7).first())
    }

    @Test
    fun `a run still owing its record scoring is not settled`() = runTest {
        assertFalse(settled(measuredRun(recordsScored = false)).runSummaryFactsSettledFlow(7).first())
    }

    @Test
    fun `a run still owing its segment walk is not settled`() = runTest {
        assertFalse(settled(measuredRun(segmentsTimed = false)).runSummaryFactsSettledFlow(7).first())
    }

    @Test
    fun `a run whose shape has not been taken is not settled`() = runTest {
        assertFalse(settled(measuredRun(), shaped = false).runSummaryFactsSettledFlow(7).first())
    }

    /**
     * Its own marks say the measuring *of it* is done; only the fill says the measuring of everything
     * it is ranked against is. A Run through the pass can still be demoted by one the pass has yet to
     * reach, and these words are kept for ever.
     */
    @Test
    fun `a run is not settled while the whole of history is being re-measured`() = runTest {
        assertFalse(
            settled(measuredRun(), historyBeingMeasured = true).runSummaryFactsSettledFlow(7).first()
        )
    }

    /**
     * A process that died part-way through the launch scoring pass leaves finished Runs owing a
     * scoring with no wholesale fill outstanding at all — so this Run can hold every one of its own
     * marks while the rest of that pass is still rewriting the standings around it.
     */
    @Test
    fun `a run is not settled while any other run still owes the record book a scoring`() = runTest {
        assertFalse(
            settled(measuredRun(), recordScoringOwedSomewhere = true)
                .runSummaryFactsSettledFlow(7).first()
        )
    }

    /**
     * An upgrade hands a migrated Run the timed mark while the launch pass is still walking the rest
     * of history, so this Run can look measured a whole minute before a Segment effort timed for
     * another Run takes its medal away.
     */
    @Test
    fun `a run is not settled while any other run still owes the segments a walk`() = runTest {
        assertFalse(
            settled(measuredRun(), segmentWalkOwedSomewhere = true)
                .runSummaryFactsSettledFlow(7).first()
        )
    }

    /**
     * The same launch pass seen from the other end. A Segment cut before it was walked — one an
     * upgrade left owing, or one whose minutes-long walk a dying process cut short — holds no
     * efforts at all, so every Run in history can carry its own timed mark while the ground itself
     * has never been measured. The walk that follows can hand this Run efforts and medals it did
     * not hold when the words were written.
     */
    @Test
    fun `a run is not settled while any segment still owes history a walk`() = runTest {
        assertFalse(
            settled(measuredRun(), segmentHistoryWalkOwedSomewhere = true)
                .runSummaryFactsSettledFlow(7).first()
        )
    }

    /**
     * A Run's shape is taken the moment the pass reaches it, and the pass reaches the open Run
     * first. Its group is every Run shaped like it, so the shapes still owed would move the count.
     */
    @Test
    fun `a run is not settled while any other run is still owed a shape`() = runTest {
        assertFalse(
            settled(measuredRun(), shapeOwedSomewhere = true)
                .runSummaryFactsSettledFlow(7).first()
        )
    }

    @Test
    fun `a run still being recorded is not settled`() = runTest {
        val stillRunning = RunnerSession(id = 7, startTime = 1_786_514_400_000L, endTime = 0)

        assertFalse(settled(stillRunning).runSummaryFactsSettledFlow(7).first())
    }

    @Test
    fun `a run that is gone is not settled`() = runTest {
        assertFalse(settled(null).runSummaryFactsSettledFlow(7).first())
    }

    // --- What the model said ---

    @Test
    fun `a build with no model to ask refuses rather than failing`() = runTest {
        val keyless = mock<AiCoachClient> { on { canBeAsked } doReturn false }
        val repository = SessionRepository(
            sessionDao = mock<SessionDao> { onBlocking { getSessionById(7) } doReturn finishedRun() },
            runSummaryDao = summaries,
            settingsRepository = mock<SettingsRepository> {
                on { userSettingsFlow } doReturn flowOf(UserSettings(aiDataSharingEnabled = true))
            },
            aiCoachClient = keyless,
        )

        // A retry button is worth offering only where trying again could work, and no amount of
        // trying puts a key in the build.
        assertEquals(RunSummaryOutcome.REFUSED, repository.writeRunSummary(7, "a prompt"))
    }

    @Test
    fun `a model that said nothing leaves the run holding no summary`() = runTest {
        val outcome = repository(modelSays = null).writeRunSummary(7, "a prompt")

        assertEquals(RunSummaryOutcome.FAILED, outcome)
        verify(summaries, never()).put(any())
    }
}
