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
                timestamp = 1_742_000_000_000
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
    fun `a Stage asking for a distance cannot be graduated from data that has none`() {
        val prompt = buildEvaluationPrompt(
            oneRunWalkSession.copy(graduationRequirement = "Successfully complete a 5K under 30 minutes.")
        )

        assertTrue(prompt.contains("carries no distance or pace"))
        assertTrue(prompt.contains("whole run including its warm-up and cool-down"))
        assertTrue(
            prompt.contains(
                "If the stage requirement asks for a distance, a pace, or a distance within a time, " +
                    "you CANNOT verify it from this data. Set graduatedToNextStage to false"
            )
        )
    }

    @Test
    fun `duration, average heart rate and Stage still reach the coach`() {
        val prompt = buildEvaluationPrompt(oneRunWalkSession)

        assertTrue(prompt.contains("Base Builder"))
        assertTrue(prompt.contains("Complete run-walk sessions consistently"))
        assertTrue(prompt.contains("\"durationSeconds\":1800"))
        assertTrue(prompt.contains("\"avgHr\":125"))
        assertTrue(prompt.contains("\"sessionType\":\"Run/Walk\""))
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
                        timestamp = 1_742_000_000_000
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
