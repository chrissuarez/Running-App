package com.example.runningapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCoachClientTest {

    @Test
    fun `buildEvaluationPrompt includes graded interval guidance and anti-overclaim rule`() {
        val context = AiTrainingContext(
            currentStageTitle = "Base Builder",
            graduationRequirement = "Complete run-walk sessions consistently",
            recentRuns = listOf(
                AiRecentRun(
                    durationSeconds = 1800,
                    avgHr = 125,
                    sessionType = "Run/Walk",
                    timestamp = 1_742_000_000_000,
                    runWalkMetrics = AiRunWalkMetrics(
                        severeBreakdownRatePercent = 0,
                        poorToleranceRatePercent = 20,
                        strainedCompletionRatePercent = 50,
                        strongCompletionRatePercent = 30,
                        cleanIntervalRatePercent = 10,
                        hrDriftSlopeBpmPerInterval = 0.4,
                        intervalCompletionRatioPercent = 82,
                        avgRecoverySecondsAfterTrigger = 12.0,
                        avgHrAtTrigger = 136.0
                    )
                )
            )
        )

        val prompt = buildEvaluationPrompt(context)

        assertTrue(prompt.contains("severeBreakdownRatePercent"))
        assertTrue(prompt.contains("poorToleranceRatePercent"))
        assertTrue(prompt.contains("strainedCompletionRatePercent"))
        assertTrue(prompt.contains("strongCompletionRatePercent"))
        assertTrue(prompt.contains("cleanIntervalRatePercent"))
        assertTrue(prompt.contains("not merely that severe breakdown is zero"))
        assertTrue(prompt.contains("Do not describe a session as perfect, stellar, or textbook"))
        assertTrue(prompt.contains("\"strongCompletionRatePercent\":30"))
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
