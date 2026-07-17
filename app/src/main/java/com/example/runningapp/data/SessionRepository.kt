package com.example.runningapp.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.runningapp.SettingsRepository
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.recording.SessionRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.floor
import kotlin.math.roundToInt

// The historical session-type label kept on the `sessions` table (#107 retired the concept but the
// column stays for history). AI training context still keys interval metrics off it for past runs.
private const val HISTORICAL_RUN_WALK_LABEL = "Run/Walk"

data class AiRunWalkMetrics(
    val severeBreakdownRatePercent: Int,
    val poorToleranceRatePercent: Int,
    val strainedCompletionRatePercent: Int,
    val strongCompletionRatePercent: Int,
    val cleanIntervalRatePercent: Int,
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

data class Max30dLoad(
    val maxDistanceKm: Double,
    val maxDurationSeconds: Long
)

class SessionRepository(
    private val sessionDao: SessionDao,
    private val runWalkIntervalStatDao: RunWalkIntervalStatDao? = null,
    private val trackPointDao: TrackPointDao? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val aiCoachClient: AiCoachClient? = null,
    private val weatherClient: WeatherClient? = null
) {
    suspend fun deleteSession(sessionId: Long) {
        sessionDao.deleteSessionById(sessionId)
    }

    /**
     * Track points accepted for map drawing (#38): BACKFILL points are historical breadcrumbs with
     * no recorded GPS accuracy and are always kept; GPS points must meet the same
     * [SessionRecorder.ACCURACY_THRESHOLD_METERS] bar applied live during recording, so what the
     * runner heard mid-run matches what they see on the map afterward.
     */
    suspend fun getTrackPointsForMap(sessionId: Long): List<TrackPoint> {
        val dao = trackPointDao ?: return emptyList()
        return dao.getTrackPointsForSessionOnce(sessionId).filter { it.isAcceptedForMap() }
    }

    /**
     * Live version of [getTrackPointsForMap] (#40): the in-run map card's trail redraws as new
     * points are recorded, filtered by the same #38 accuracy rule.
     */
    fun getTrackPointsForMapFlow(sessionId: Long): Flow<List<TrackPoint>> {
        val dao = trackPointDao ?: return flowOf(emptyList())
        return dao.getTrackPointsForSession(sessionId).map { points -> points.filter { it.isAcceptedForMap() } }
    }

    private fun TrackPoint.isAcceptedForMap(): Boolean = when (source) {
        TrackPointSource.BACKFILL -> true
        else -> horizontalAccuracyMeters != null && SessionRecorder.isAccuracyAccepted(horizontalAccuracyMeters)
    }

    /**
     * Fetches and persists the weather snapshot for a session. Never throws — a failed or
     * unreachable weather service must not affect the run save it runs after (#79). Failures are
     * picked up later by [retryMissingWeather] on a subsequent app launch.
     */
    suspend fun fetchAndSaveWeather(sessionId: Long, latitude: Double, longitude: Double, atEpochMillis: Long) {
        val client = weatherClient ?: return
        val snapshot = try {
            client.fetchWeather(latitude, longitude, atEpochMillis)
        } catch (e: Exception) {
            Log.e("Weather", "Weather fetch failed for sessionId=$sessionId", e)
            null
        } ?: return

        sessionDao.updateWeather(
            sessionId = sessionId,
            tempC = snapshot.temperatureC,
            feelsLikeC = snapshot.feelsLikeC,
            humidityPercent = snapshot.humidityPercent,
            windSpeedKmh = snapshot.windSpeedKmh,
            conditionCode = snapshot.conditionCode
        )
    }

    /** Retries weather for outdoor sessions that finished without it — called once per app launch. */
    suspend fun retryMissingWeather() {
        if (weatherClient == null) return
        val sessions = sessionDao.getOutdoorSessionsMissingWeather()
        for (session in sessions) {
            val latitude = session.startLatitude ?: continue
            val longitude = session.startLongitude ?: continue
            fetchAndSaveWeather(session.id, latitude, longitude, session.startTime)
        }
    }

    /**
     * Persists the post-run "How did that feel?" feedback. The service finalizes the
     * session row asynchronously after stop with a full-row update, so this waits until
     * that write has landed (endTime > 0) before touching the row.
     */
    suspend fun saveFeelFeedback(
        sessionId: Long,
        effort: Int?,
        note: String?,
        finalizeWaitStepMillis: Long = 250L
    ) {
        if (effort == null && note.isNullOrBlank()) return
        repeat(20) {
            val session = sessionDao.getSessionById(sessionId) ?: return
            if (session.endTime > 0) {
                sessionDao.updateFeelFeedback(sessionId, effort, note?.trim()?.ifEmpty { null })
                return
            }
            kotlinx.coroutines.delay(finalizeWaitStepMillis)
        }
        // Finalize never landed (should not happen) — save the user's input rather than drop it.
        sessionDao.updateFeelFeedback(sessionId, effort, note?.trim()?.ifEmpty { null })
    }

    suspend fun deleteSessions(sessionIds: List<Long>) {
        if (sessionIds.isEmpty()) return
        sessionDao.deleteSessionsByIds(sessionIds)
    }

    suspend fun getMaxSessionLoadLast30Days(
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Max30dLoad {
        val cutoffEpochMillis = nowEpochMillis - (30L * 24 * 60 * 60 * 1000)
        val projection = sessionDao.getMaxSessionLoadLast30Days(cutoffEpochMillis)
        return Max30dLoad(
            maxDistanceKm = projection.maxDistanceKm ?: 0.0,
            maxDurationSeconds = projection.maxDurationSeconds ?: 0L
        )
    }

    suspend fun getAiTrainingContext(stageId: String): AiTrainingContext {
        val stage = TrainingPlanProvider
            .getAllPlans()
            .asSequence()
            .flatMap { it.stages.asSequence() }
            .firstOrNull { it.id == stageId }
            ?: throw IllegalArgumentException("Stage not found for id: $stageId")

        val recentRuns = sessionDao.getLast3AiEligibleCompletedSessions().map { session ->
            val runWalkMetrics = if (session.sessionType == HISTORICAL_RUN_WALK_LABEL) {
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
            val settings = settingsRepo.userSettingsFlow.first()
            if (settings.testingModeEnabled) {
                Log.d("AiCoach", "Skipping AI evaluation: testing mode enabled")
                return
            }
            val latestFinalizedSession = sessionDao.getMostRecentFinalizedSession()
            if (latestFinalizedSession?.includeInAiTraining == false) {
                Log.d(
                    "AiCoach",
                    "Skipping AI evaluation: latest session is excluded from AI training. stageId=$stageId"
                )
                return
            }
            // Keep interval-based AI prescriptions scoped to structured Run/Walk only.
            if (latestFinalizedSession?.sessionType != HISTORICAL_RUN_WALK_LABEL) {
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
            // Warm-up/cool-down now live on the workout (#107); the load clamp accounts for the
            // active workout's envelope so the estimated total stays comparable to real sessions.
            val activeWorkout = settings.activePlanId?.let { planId ->
                TrainingPlanProvider.getPlanById(planId)?.let { plan ->
                    (plan.stages.firstOrNull { it.id == settings.activeStageId } ?: plan.stages.firstOrNull())
                        ?.workouts?.firstOrNull()
                }
            }
            val clampedResponse = clampAiResponseByRecentLoad(
                response,
                warmUpSeconds = activeWorkout?.warmUpSeconds ?: 0,
                coolDownSeconds = activeWorkout?.coolDownSeconds ?: 0
            )
            Log.d(
                "AiCoach",
                "Gemini response received! Adjusted intervals: ${clampedResponse.nextRunDurationSeconds}s Run / " +
                    "${clampedResponse.nextWalkDurationSeconds}s Walk. Message: ${clampedResponse.coachMessage}"
            )

            settingsRepo.setAiAdjustments(
                latestCoachMessage = clampedResponse.coachMessage,
                aiRunIntervalSeconds = clampedResponse.nextRunDurationSeconds,
                aiWalkIntervalSeconds = clampedResponse.nextWalkDurationSeconds,
                aiRepeats = clampedResponse.nextRepeats
            )

            if (clampedResponse.graduatedToNextStage) {
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

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun clampAiResponseByRecentLoad(
        response: AiCoachResponse,
        warmUpSeconds: Int,
        coolDownSeconds: Int
    ): AiCoachResponse {
        val warmupSeconds = warmUpSeconds.coerceAtLeast(0)
        val cooldownSeconds = coolDownSeconds.coerceAtLeast(0)
        val max30d = getMaxSessionLoadLast30Days()
        if (max30d.maxDurationSeconds <= 0L) return response

        val safeWalkSeconds = response.nextWalkDurationSeconds.coerceAtLeast(0)
        val safeRepeats = response.nextRepeats.coerceAtLeast(1)
        val safeRunSeconds = response.nextRunDurationSeconds.coerceAtLeast(1)

        val allowedTotalSeconds = floor(max30d.maxDurationSeconds.toDouble() * 1.10).toLong()
        val proposedTotalSeconds = computePlannedTotalSeconds(
            runSeconds = safeRunSeconds,
            walkSeconds = safeWalkSeconds,
            repeats = safeRepeats,
            warmupSeconds = warmupSeconds,
            cooldownSeconds = cooldownSeconds
        )

        if (proposedTotalSeconds <= allowedTotalSeconds) {
            return response.copy(
                nextRunDurationSeconds = safeRunSeconds,
                nextWalkDurationSeconds = safeWalkSeconds,
                nextRepeats = safeRepeats
            )
        }

        val mainBudgetSeconds = (allowedTotalSeconds - warmupSeconds.toLong() - cooldownSeconds.toLong())
            .coerceAtLeast(0L)
        val walkTotalSeconds = safeWalkSeconds.toLong() * safeRepeats.toLong()
        val runBudgetSeconds = (mainBudgetSeconds - walkTotalSeconds).coerceAtLeast(0L)
        var clampedRunSeconds = (runBudgetSeconds / safeRepeats.toLong()).toInt()
        var clampedRepeats = safeRepeats

        if (clampedRunSeconds < 1) {
            val perRepeatMinimum = (safeWalkSeconds + 1).coerceAtLeast(1)
            clampedRepeats = (mainBudgetSeconds / perRepeatMinimum.toLong()).toInt().coerceAtLeast(1)
            val adjustedRunBudget = (mainBudgetSeconds - (safeWalkSeconds.toLong() * clampedRepeats.toLong()))
                .coerceAtLeast(0L)
            clampedRunSeconds = (adjustedRunBudget / clampedRepeats.toLong()).toInt().coerceAtLeast(1)
        }

        return response.copy(
            nextRunDurationSeconds = clampedRunSeconds,
            nextWalkDurationSeconds = safeWalkSeconds,
            nextRepeats = clampedRepeats
        )
    }

    private fun computePlannedTotalSeconds(
        runSeconds: Int,
        walkSeconds: Int,
        repeats: Int,
        warmupSeconds: Int,
        cooldownSeconds: Int
    ): Long {
        val mainSetSeconds = (runSeconds.toLong() + walkSeconds.toLong()) * repeats.toLong()
        return warmupSeconds.toLong() + mainSetSeconds + cooldownSeconds.toLong()
    }

    private suspend fun buildRunWalkMetrics(sessionId: Long): AiRunWalkMetrics? {
        val intervalDao = runWalkIntervalStatDao ?: return null
        val stats = intervalDao.getIntervalStatsForSession(sessionId)
        if (stats.isEmpty()) {
            Log.w("AiCoach", "No interval stats available for Run/Walk sessionId=$sessionId")
            return null
        }

        val analytics = computeRunWalkIntervalAnalytics(stats)

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
            "Run metrics sessionId=$sessionId severe=${analytics.severeBreakdownPercent}% " +
                "poor=${analytics.poorTolerancePercent}% strained=${analytics.strainedCompletionPercent}% " +
                "strong=${analytics.strongCompletionPercent}% clean=${analytics.cleanPercent}% " +
                "completion=${analytics.completionRatioPercent}% avgTriggerHr=${avgHrAtTrigger?.roundToInt()} " +
                "avgRecovery=${avgRecoverySeconds?.roundToInt()}s driftSlope=${hrDriftSlope ?: "null"}"
        )

        return AiRunWalkMetrics(
            severeBreakdownRatePercent = analytics.severeBreakdownPercent,
            poorToleranceRatePercent = analytics.poorTolerancePercent,
            strainedCompletionRatePercent = analytics.strainedCompletionPercent,
            strongCompletionRatePercent = analytics.strongCompletionPercent,
            cleanIntervalRatePercent = analytics.cleanPercent,
            hrDriftSlopeBpmPerInterval = hrDriftSlope,
            intervalCompletionRatioPercent = analytics.completionRatioPercent,
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
