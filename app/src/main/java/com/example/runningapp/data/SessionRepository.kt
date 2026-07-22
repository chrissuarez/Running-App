package com.example.runningapp.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.runningapp.CoachPrescription
import com.example.runningapp.CoachPrescriptionRepository
import com.example.runningapp.HrZone
import com.example.runningapp.SettingsRepository
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.effectiveMaxHr
import com.example.runningapp.tallyZoneSeconds
import com.example.runningapp.recording.SessionRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.floor
import kotlin.math.roundToInt

// Labels describing a run to the AI coach (#107). Structure comes only from a plan, so the one
// distinction the coach needs is whether the run followed a run/walk workout; these are derived
// from RunnerSession.isRunWalkMode, not from any user-selected mode.
private const val AI_LABEL_RUN_WALK = "Run/Walk"
private const val AI_LABEL_OPEN_RUN = "Open Run"

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

/**
 * The target zone a prescription is allowed to carry.
 *
 * [requested] is whatever the model returned, so it is sanitized rather than trusted: an omitted or
 * unrecognisable zone is the coach declining to move the target, and the workout's own zone stands
 * (falling back to the global only when no plan is attached). A recognisable one is snapped to a
 * coaching target — Zone 1 and Zone 5 are excluded from whole-run targets because they overstate
 * time in target (#117), and the coach must not be the one door that re-opens that.
 */
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal fun coachTargetZone(
    requested: Int?,
    workoutTargetZone: Int?,
    settingsTargetZone: Int
): Int {
    val recognised = requested?.let { HrZone.ofNumber(it) }
        ?: return workoutTargetZone ?: settingsTargetZone
    return HrZone.coachingTargetOfNumberOrDefault(recognised.number).number
}

class SessionRepository(
    private val sessionDao: SessionDao,
    private val sampleDao: SampleDao? = null,
    private val runWalkIntervalStatDao: RunWalkIntervalStatDao? = null,
    private val trackPointDao: TrackPointDao? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val coachPrescriptionRepository: CoachPrescriptionRepository? = null,
    private val aiCoachClient: AiCoachClient? = null,
    private val weatherClient: WeatherClient? = null,
    // Re-snapshots run history to the Downloads backup after a deletion. Without this a later
    // Clear-storage restore would bring back a stale snapshot that still holds the deleted runs, so
    // deletes have to invalidate the snapshot too — not just the finish-run path. Null in tests and
    // wherever no backup target is wired.
    private val refreshHistoryBackup: (suspend () -> Unit)? = null
) {
    suspend fun deleteSession(sessionId: Long) {
        sessionDao.deleteSessionById(sessionId)
        refreshHistoryBackup?.invoke()
    }

    /**
     * The one door for setting Max HR — every surface that offers the number should come through
     * here, or history can be stranded on a placeholder forever (#112, #103).
     *
     * The **first** deliberate set recomputes all history against the true number: until someone
     * states it, every run's zone times sit on the default `190` that nobody chose. Every change
     * after that is future-only, so a later correction can't quietly rewrite runs the runner has
     * already read. This is Strava's rule read literally: only the first time you *set* zones.
     *
     * Silent by design — there is nothing to decide, and confirming a correction is nagging.
     *
     * The recompute runs *before* the flag is set, so an interruption is retried rather than
     * remembered as done: a half-finished recompute leaves the flag clear and the old Max HR on
     * screen, and the next set redoes the whole thing (the tally is a pure re-derivation, so
     * repeating it costs nothing). Setting the flag first would strand history permanently
     * half-converted, with nothing on screen to say so.
     */
    suspend fun setMaxHr(maxHr: Int) {
        val settings = settingsRepository ?: return
        val clampedMaxHr = effectiveMaxHr(maxHr)
        if (!settings.userSettingsFlow.first().maxHrEverSet) {
            // No samples to recompute from is a reason to do nothing at all, not a reason to
            // record the set anyway: the flag is one-shot, so spending it here would strand
            // history on the placeholder with no way back.
            val samples = sampleDao ?: return
            recomputeZoneSecondsForAllRuns(samples, clampedMaxHr)
        }
        settings.setMaxHrDeliberately(clampedMaxHr)
    }

    /**
     * Re-tallies every *finished* run's zone seconds from its stored samples, one run at a time so
     * a long history never holds more than a single run's beats in memory.
     *
     * Settings is reachable mid-run, so a run in progress can be sitting in `sessions` while this
     * executes. It is left alone: the recorder finalizes it from its own in-memory counters and
     * would overwrite anything written here, so retallying it would spend the one-shot flag on a
     * row that ends up disagreeing with it. The live run keeps the zone times it accumulated as it
     * was heard — the next run is the first to be measured against the stated number.
     */
    private suspend fun recomputeZoneSecondsForAllRuns(samples: SampleDao, maxHr: Int) {
        sessionDao.getFinalizedSessionIds().forEach { sessionId ->
            val tally = tallyZoneSeconds(samples.getRawBpmsForSession(sessionId), maxHr)
            sessionDao.updateZoneSeconds(
                sessionId = sessionId,
                zone1 = tally.zone1,
                zone2 = tally.zone2,
                zone3 = tally.zone3,
                zone4 = tally.zone4,
                zone5 = tally.zone5
            )
        }
        refreshHistoryBackup?.invoke()
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
        val trimmedNote = note?.trim()?.ifEmpty { null }
        repeat(20) {
            val session = sessionDao.getSessionById(sessionId) ?: return
            if (session.endTime > 0) {
                sessionDao.updateFeelFeedback(sessionId, effort, trimmedNote)
                // Fold this user-entered history into the Downloads snapshot too, or a Clear-storage
                // restore before the next run would bring the run back without it.
                refreshHistoryBackup?.invoke()
                return
            }
            kotlinx.coroutines.delay(finalizeWaitStepMillis)
        }
        // Finalize never landed (should not happen) — save the user's input rather than drop it.
        sessionDao.updateFeelFeedback(sessionId, effort, trimmedNote)
        refreshHistoryBackup?.invoke()
    }

    suspend fun deleteSessions(sessionIds: List<Long>) {
        if (sessionIds.isEmpty()) return
        sessionDao.deleteSessionsByIds(sessionIds)
        refreshHistoryBackup?.invoke()
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
            // A structured run/walk workout is the only run the coach can adjust intervals from
            // (#107). isRunWalkMode records that per run, so it replaces the retired session-type
            // column both as the gate and as the label the coach sees.
            val runWalkMetrics = if (session.isRunWalkMode) {
                buildRunWalkMetrics(session.id)
            } else {
                null
            }
            AiRecentRun(
                durationSeconds = session.durationSeconds,
                avgHr = session.avgBpm,
                walkBreaksCount = session.walkBreaksCount,
                sessionType = if (session.isRunWalkMode) AI_LABEL_RUN_WALK else AI_LABEL_OPEN_RUN,
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
            // Keep interval-based AI prescriptions scoped to structured run/walk runs only.
            if (latestFinalizedSession?.isRunWalkMode != true) {
                Log.d(
                    "AiCoach",
                    "Skipping AI evaluation: latest run was not a structured run/walk. stageId=$stageId"
                )
                return
            }

            Log.d("AiCoach", "Starting AI evaluation for stage: $stageId")
            val context = getAiTrainingContext(stageId)
            Log.d("AiCoach", "Sending prompt to Gemini with ${context.recentRuns.size} recent runs.")
            val response = coachClient.evaluateProgress(context)
            if (response == null) {
                Log.d("AiCoach", "Skipping AI adjustment: the coach could not be reached. stageId=$stageId")
                return
            }
            // Warm-up/cool-down now live on the workout (#107); the load clamp accounts for the
            // active workout's envelope so the estimated total stays comparable to real sessions.
            val activeWorkout = TrainingPlanProvider.resolveBaseWorkout(
                settings.activePlanId,
                settings.activeStageId
            )
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

            settingsRepo.setLatestCoachMessage(clampedResponse.coachMessage)

            if (clampedResponse.graduatedToNextStage) {
                val plan = TrainingPlanProvider
                    .getAllPlans()
                    .firstOrNull { currentPlan -> currentPlan.stages.any { it.id == stageId } }

                val nextStageId = plan
                    ?.stages
                    ?.indexOfFirst { it.id == stageId }
                    ?.takeIf { it >= 0 }
                    ?.let { index -> plan.stages.getOrNull(index + 1)?.id }

                // No prescription on a graduation: it would be intervals for the stage just left,
                // and writing one only to clear it in the next breath leaves a window where a run
                // could start on the new stage carrying the old one's numbers.
                settingsRepo.advanceStageAndClearPrescription(nextStageId)
            } else {
                coachPrescriptionRepository?.prescribe(
                    CoachPrescription(
                        targetZone = coachTargetZone(
                            requested = clampedResponse.nextTargetZone,
                            workoutTargetZone = activeWorkout?.targetZone,
                            settingsTargetZone = settings.targetZone
                        ),
                        runDurationSeconds = clampedResponse.nextRunDurationSeconds,
                        walkDurationSeconds = clampedResponse.nextWalkDurationSeconds,
                        totalRepeats = clampedResponse.nextRepeats,
                        prescribedAtEpochMillis = System.currentTimeMillis()
                    )
                )
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
