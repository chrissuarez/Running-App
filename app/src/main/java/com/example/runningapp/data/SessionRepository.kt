package com.example.runningapp.data

import android.util.Log
import com.example.runningapp.SettingsRepository
import com.example.runningapp.TrainingPlanProvider
import kotlin.math.roundToInt

private const val SESSION_TYPE_RUN_WALK = "Run/Walk"

data class AiRunWalkMetrics(
    val earlyBreakdownRatePercent: Int,
    val hrDriftSlopeBpmPerInterval: Double?,
    val intervalCompletionRatioPercent: Int,
    val avgRecoverySecondsAfterTrigger: Double?,
    val avgHrAtTrigger: Double?
)

data class AiRecentRun(
    val durationSeconds: Long,
    val avgHr: Int,
    val walkBreaksCount: Int,
    val sessionType: String,
    val timestamp: Long,
    val runWalkMetrics: AiRunWalkMetrics? = null
)

data class AiTrainingContext(
    val currentStageTitle: String,
    val graduationRequirement: String,
    val recentRuns: List<AiRecentRun>
)

class SessionRepository(
    private val sessionDao: SessionDao,
    private val runWalkIntervalStatDao: RunWalkIntervalStatDao? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val aiCoachClient: AiCoachClient? = null
) {
    suspend fun deleteSession(sessionId: Long) {
        sessionDao.deleteSessionById(sessionId)
    }

    suspend fun deleteSessions(sessionIds: List<Long>) {
        if (sessionIds.isEmpty()) return
        sessionDao.deleteSessionsByIds(sessionIds)
    }

    suspend fun getAiTrainingContext(stageId: String): AiTrainingContext {
        val stage = TrainingPlanProvider
            .getAllPlans()
            .asSequence()
            .flatMap { it.stages.asSequence() }
            .firstOrNull { it.id == stageId }
            ?: throw IllegalArgumentException("Stage not found for id: $stageId")

        val recentRuns = sessionDao.getLast3AiEligibleCompletedSessions().map { session ->
            val runWalkMetrics = if (session.sessionType == SESSION_TYPE_RUN_WALK) {
                buildRunWalkMetrics(session.id)
            } else {
                null
            }
            AiRecentRun(
                durationSeconds = session.durationSeconds,
                avgHr = session.avgBpm,
                walkBreaksCount = session.walkBreaksCount,
                sessionType = session.sessionType,
                timestamp = session.startTime,
                runWalkMetrics = runWalkMetrics
            )
        }

        return AiTrainingContext(
            currentStageTitle = stage.title,
            graduationRequirement = stage.graduationRequirementText,
            recentRuns = recentRuns
        )
    }

    suspend fun evaluateAndAdjustPlan(stageId: String) {
        val settingsRepo = settingsRepository ?: return
        val coachClient = aiCoachClient ?: return

        try {
            val latestFinalizedSession = sessionDao.getMostRecentFinalizedSession()
            if (latestFinalizedSession?.includeInAiTraining == false) {
                Log.d(
                    "AiCoach",
                    "Skipping AI evaluation: latest session is excluded from AI training. stageId=$stageId"
                )
                return
            }
            if (latestFinalizedSession?.sessionType != SESSION_TYPE_RUN_WALK) {
                Log.d(
                    "AiCoach",
                    "Skipping AI evaluation: latestSessionType=${latestFinalizedSession?.sessionType ?: "none"} stageId=$stageId"
                )
                return
            }

            Log.d("AiCoach", "Starting AI evaluation for stage: $stageId")
            val context = getAiTrainingContext(stageId)
            Log.d("AiCoach", "Sending prompt to Gemini with ${context.recentRuns.size} recent runs.")
            val response = coachClient.evaluateProgress(context)
            Log.d(
                "AiCoach",
                "Gemini response received! Adjusted intervals: ${response.nextRunDurationSeconds}s Run / " +
                    "${response.nextWalkDurationSeconds}s Walk. Message: ${response.coachMessage}"
            )

            settingsRepo.setAiAdjustments(
                latestCoachMessage = response.coachMessage,
                aiRunIntervalSeconds = response.nextRunDurationSeconds,
                aiWalkIntervalSeconds = response.nextWalkDurationSeconds,
                aiRepeats = response.nextRepeats
            )

            if (response.graduatedToNextStage) {
                val plan = TrainingPlanProvider
                    .getAllPlans()
                    .firstOrNull { currentPlan -> currentPlan.stages.any { it.id == stageId } }

                val nextStageId = plan
                    ?.stages
                    ?.indexOfFirst { it.id == stageId }
                    ?.takeIf { it >= 0 }
                    ?.let { index -> plan.stages.getOrNull(index + 1)?.id }

                settingsRepo.advanceStageAndClearAiIntervals(nextStageId)
            }
        } catch (e: Exception) {
            Log.e("AiCoach", "Failed to evaluate progress", e)
        }
    }

    private suspend fun buildRunWalkMetrics(sessionId: Long): AiRunWalkMetrics? {
        val intervalDao = runWalkIntervalStatDao ?: return null
        val stats = intervalDao.getIntervalStatsForSession(sessionId)
        if (stats.isEmpty()) {
            Log.w("AiCoach", "No interval stats available for Run/Walk sessionId=$sessionId")
            return null
        }

        val totalIntervals = stats.size
        val earlyBreakdownCount = stats.count { stat ->
            val firstTrigger = stat.timeIntoIntervalWhenHrExceededCapSeconds ?: return@count false
            firstTrigger.toDouble() < stat.plannedDurationSeconds.toDouble() * 0.30
        }
        val earlyBreakdownRatePercent = ((earlyBreakdownCount.toDouble() / totalIntervals.toDouble()) * 100.0).roundToInt()

        val completionRatioPercent = (
            stats.map { stat ->
                if (stat.plannedDurationSeconds <= 0) {
                    0.0
                } else {
                    (stat.actualRunningDurationBeforeHrTriggerSeconds.toDouble() / stat.plannedDurationSeconds.toDouble())
                        .coerceAtMost(1.0)
                }
            }.average() * 100.0
            ).roundToInt()

        val avgHrAtTriggerValues = stats.mapNotNull { it.avgHrAtTriggerInInterval }
        val avgRecoveryValues = stats.mapNotNull { it.avgRecoverySecondsAfterTriggerInInterval }
        val avgHrAtTrigger = avgHrAtTriggerValues.averageOrNull()
        val avgRecoverySeconds = avgRecoveryValues.averageOrNull()
        val hrDriftSlope = calculateLinearRegressionSlope(
            stats.mapNotNull { stat ->
                val triggerHr = stat.avgHrAtTriggerInInterval ?: return@mapNotNull null
                stat.intervalIndex.toDouble() to triggerHr
            }
        )

        if (hrDriftSlope == null) {
            Log.w("AiCoach", "Sparse drift data for sessionId=$sessionId; slope unavailable")
        }

        Log.d(
            "AiCoach",
            "Run metrics sessionId=$sessionId early=${earlyBreakdownRatePercent}% " +
                "completion=${completionRatioPercent}% avgTriggerHr=${avgHrAtTrigger?.roundToInt()} " +
                "avgRecovery=${avgRecoverySeconds?.roundToInt()}s driftSlope=${hrDriftSlope ?: "null"}"
        )

        return AiRunWalkMetrics(
            earlyBreakdownRatePercent = earlyBreakdownRatePercent,
            hrDriftSlopeBpmPerInterval = hrDriftSlope,
            intervalCompletionRatioPercent = completionRatioPercent,
            avgRecoverySecondsAfterTrigger = avgRecoverySeconds,
            avgHrAtTrigger = avgHrAtTrigger
        )
    }

    private fun calculateLinearRegressionSlope(points: List<Pair<Double, Double>>): Double? {
        if (points.size < 2) return null
        val meanX = points.map { it.first }.average()
        val meanY = points.map { it.second }.average()
        var numerator = 0.0
        var denominator = 0.0
        for ((x, y) in points) {
            val dx = x - meanX
            numerator += dx * (y - meanY)
            denominator += dx * dx
        }
        if (denominator == 0.0) return null
        return numerator / denominator
    }

    private fun List<Double>.averageOrNull(): Double? {
        if (isEmpty()) return null
        return average()
    }
}
