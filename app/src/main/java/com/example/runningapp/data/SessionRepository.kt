package com.example.runningapp.data

import android.util.Log
import com.example.runningapp.SettingsRepository
import com.example.runningapp.TrainingPlanProvider

data class AiRecentRun(
    val durationSeconds: Long,
    val avgHr: Int,
    val walkBreaksCount: Int,
    val timestamp: Long
)

data class AiTrainingContext(
    val currentStageTitle: String,
    val graduationRequirement: String,
    val recentRuns: List<AiRecentRun>
)

class SessionRepository(
    private val sessionDao: SessionDao,
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

        val recentRuns = sessionDao.getLast3CompletedSessions().map { session ->
            AiRecentRun(
                durationSeconds = session.durationSeconds,
                avgHr = session.avgBpm,
                walkBreaksCount = session.walkBreaksCount,
                timestamp = session.startTime
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
}
