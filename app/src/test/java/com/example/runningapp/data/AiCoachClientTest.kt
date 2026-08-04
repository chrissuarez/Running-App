package com.example.runningapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoachClientTest {

    private val oneRunWalkSession = AiTrainingContext(
        currentStageTitle = "Base Builder",
        graduationRequirement = "Complete run-walk sessions consistently",
        recentRuns = listOf(
            AiRecentRun(
                durationSeconds = 1800,
                avgHr = 125,
                sessionType = "Run/Walk",
                timestamp = 1_742_000_000_000,
                runMode = "outdoor",
                distanceKm = 5.4,
                fastest5kSeconds = 1620
            )
        )
    )

    @Test
    fun `no Interval-quality metric reaches the coach, in the data or in the reading of it`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession)

        listOf(
            "severeBreakdown",
            "poorTolerance",
            "strainedCompletion",
            "strongCompletion",
            "cleanInterval",
            "hrDrift",
            "intervalCompletionRatio",
            "avgRecoverySecondsAfterTrigger",
            "avgHrAtTrigger",
            "runWalkMetrics"
        ).forEach { metric ->
            assertFalse("prompt still mentions $metric", prompt.contains(metric, ignoreCase = true))
        }
    }

    @Test
    fun `no Run is described to the coach as a breakdown, a tolerance failure or a strain`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession)

        // The words CONTEXT.md bans for a Trigger, not the loose stems: "strain" alone would fail on
        // an innocent "constraint" one day and the failure would read as a real finding.
        listOf("breakdown", "poor tolerance", "strained").forEach { word ->
            assertFalse("prompt still mentions $word", prompt.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun `a Stage asking for a 5K time is judged from the measured 5K and nothing else`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(graduationRequirement = "Successfully complete a 5K under 30 minutes.")
        )

        assertTrue(prompt.contains("\"fastest5kSeconds\":1620"))
        assertTrue(prompt.contains("whole run including its warm-up and cool-down, so it is NOT a 5K time"))
        assertTrue(
            prompt.contains(
                "If the stage requirement asks for a 5K in a time, judge it ONLY from fastest5kSeconds"
            )
        )
    }

    @Test
    fun `a treadmill Run's stated distance can answer a requirement of exactly that distance`() {
        // The one thing two numbers settle (#231, ADR 0008): a stated distance and a whole-Run
        // duration are a time over the whole Run. 5 km in 24:30 graduates a 5K in 24:59.
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                graduationRequirement = "Run a 5K in 24:59 or faster.",
                recentRuns = oneRunWalkSession.recentRuns.map {
                    it.copy(runMode = "treadmill", distanceKm = 5.0, fastest5kSeconds = null, durationSeconds = 1470)
                }
            )
        )

        assertTrue(prompt.contains("\"runMode\":\"treadmill\""))
        assertTrue(prompt.contains("\"distanceKm\":5.0"))
        assertTrue(
            prompt.contains(
                "its distanceKm and durationSeconds establish a time for the WHOLE run and nothing shorter"
            )
        )
    }

    @Test
    fun `a treadmill Run longer than the requirement is declined rather than guessed at`() {
        // 6 km in 30:00 may hold a sub-25 5K and may not, and the splits to say which were never
        // handed over. Ruling either way is deriving a best effort from an average pace, which is
        // what ADR 0008 refuses — with a graduation that cannot be taken back behind it.
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                graduationRequirement = "Run a 5K in 24:59 or faster.",
                recentRuns = oneRunWalkSession.recentRuns.map {
                    it.copy(runMode = "treadmill", distanceKm = 6.0, fastest5kSeconds = null, durationSeconds = 1800)
                }
            )
        )

        assertTrue(
            prompt.contains(
                "If that treadmill run went FURTHER than the requirement's distance, you cannot tell " +
                    "how fast the requirement's distance alone was covered"
            )
        )
        assertTrue(
            prompt.contains("Never divide a distance by a duration to estimate a pace or a shorter-distance time.")
        )
    }

    @Test
    fun `a 5K that was never measured cannot graduate a Stage, and the coach is told why`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(
                graduationRequirement = "Run a 5K in 24:59 or faster.",
                recentRuns = oneRunWalkSession.recentRuns.map {
                    it.copy(runMode = "treadmill", distanceKm = null, fastest5kSeconds = null)
                }
            )
        )

        // Sent as an explicit null rather than left out: a field that is simply missing is a field
        // the model can read as an oversight, and this one is the whole of the evidence.
        assertTrue(prompt.contains("\"fastest5kSeconds\":null"))
        assertTrue(prompt.contains("\"distanceKm\":null"))
        assertTrue(prompt.contains("If fastest5kSeconds is null, set graduatedToNextStage to false"))
        // A treadmill Run has no distance to be given, an outdoor one does — so the run mode is sent
        // and the coach says which of the two it is looking at rather than guessing.
        assertTrue(prompt.contains("treadmill run with no distance recorded when runMode is 'treadmill'"))
        assertTrue(prompt.contains("\"runMode\":\"treadmill\""))
    }

    @Test
    fun `a requirement the data cannot answer is still refused`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(graduationRequirement = "Run 10K at 5:00 /km.")
        )

        assertTrue(
            prompt.contains(
                "If the stage requirement asks for any other distance or pace that fastest5kSeconds " +
                    "does not answer, set graduatedToNextStage to false"
            )
        )
    }

    @Test
    fun `duration, average heart rate, distance and Stage all reach the coach`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession)

        assertTrue(prompt.contains("Base Builder"))
        assertTrue(prompt.contains("Complete run-walk sessions consistently"))
        assertTrue(prompt.contains("\"durationSeconds\":1800"))
        assertTrue(prompt.contains("\"avgHr\":125"))
        assertTrue(prompt.contains("\"sessionType\":\"Run/Walk\""))
        assertTrue(prompt.contains("\"runMode\":\"outdoor\""))
        assertTrue(prompt.contains("\"distanceKm\":5.4"))
    }

    @Test
    fun `the walk-break count is neither sent nor asked about`() {
        val prompt = buildEvaluationPrompt(
            AiTrainingContext(
                currentStageTitle = "Base Builder",
                graduationRequirement = "Complete run-walk sessions consistently",
                recentRuns = listOf(
                    AiRecentRun(
                        durationSeconds = 1800,
                        avgHr = 125,
                        sessionType = "Run/Walk",
                        timestamp = 1_742_000_000_000,
                        runMode = "outdoor",
                        distanceKm = 5.4,
                        fastest5kSeconds = 1620
                    )
                )
            )
        )

        assertFalse(prompt.contains("walkBreak", ignoreCase = true))
        assertFalse(prompt.contains("HR-triggered", ignoreCase = true))
    }

    @Test
    fun `the schema offers the target zone as the coach's, and only as an option`() {
        val prompt = buildEvaluationPrompt(
            AiTrainingContext(
                currentStageTitle = "Base Builder",
                graduationRequirement = "Complete run-walk sessions consistently",
                recentRuns = emptyList()
            )
        )

        assertTrue(prompt.contains("\"nextTargetZone\": Int (optional, 1-5)"))
        assertTrue(prompt.contains("Omit nextTargetZone to leave the workout's own target zone alone."))
    }
}
