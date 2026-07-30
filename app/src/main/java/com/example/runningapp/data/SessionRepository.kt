package com.example.runningapp.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.runningapp.CoachPrescription
import com.example.runningapp.CoachPrescriptionRepository
import com.example.runningapp.CoachWriteScope
import com.example.runningapp.HrZone
import com.example.runningapp.SettingsRepository
import com.example.runningapp.StatedHeartRates
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.clearedBy
import com.example.runningapp.HrProfile
import com.example.runningapp.effectiveMaxHr
import com.example.runningapp.tallyZoneSeconds
import com.example.runningapp.recording.SessionRecorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.floor

// Labels describing a run to the AI coach (#107). Structure comes only from a plan, so the one
// distinction the coach needs is whether the run followed a run/walk workout; these are derived
// from RunnerSession.isRunWalkMode, not from any user-selected mode.
private const val AI_LABEL_RUN_WALK = "Run/Walk"
private const val AI_LABEL_OPEN_RUN = "Open Run"

/**
 * What the AI coach is told about a past Run.
 *
 * The walk-break count is deliberately absent (#167). It now counts the walks the Workout
 * prescribed, so it says nothing about how the Run went, and the rows saved before #167 count
 * heart-rate cues instead — one number, two meanings, and no way to tell which a row carries.
 * Sending it would have the coach read a six-repeat Workout as six failures.
 *
 * Interval-quality metrics are gone for the same kind of reason (#168). Completion was measured as
 * the second heart rate first crossed the target line over the Interval's planned length, so an
 * Interval run in full logged as a "severe breakdown" — the app never knew whether a runner walked,
 * only whether their heart rate was high (ADR 0003). The coach stopped adapting a Plan from them
 * there; #169 then deleted the figures themselves, so nothing computes or shows them now.
 */
data class AiRecentRun(
    val durationSeconds: Long,
    val avgHr: Int,
    val sessionType: String,
    val timestamp: Long
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
    private val trackPointDao: TrackPointDao? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val coachPrescriptionRepository: CoachPrescriptionRepository? = null,
    private val aiCoachClient: AiCoachClient? = null,
    private val weatherClient: WeatherClient? = null,
    // Re-snapshots run history to the Downloads backup after a deletion. Without this a later
    // Clear-storage restore would bring back a stale snapshot that still holds the deleted runs, so
    // deletes have to invalidate the snapshot too — not just the finish-run path. Null in tests and
    // wherever no backup target is wired.
    private val refreshHistoryBackup: (suspend () -> Unit)? = null,
    /**
     * Runs a block as one database transaction, so a re-tally of history is all of it or none.
     *
     * Re-banding walks every finished run one row at a time. Failing part-way through — a full
     * disk, a corrupt page — would otherwise leave the early runs on the new profile and the rest
     * on the old, which is precisely the split #172 exists to prevent, arriving by accident and
     * with nothing on screen to say so. Rolled back, the statement is simply lost and the runner
     * can state it again.
     *
     * Holds the database's write lock for the length of the re-tally, and Settings is reachable
     * mid-run, so a recorder's per-second sample insert can be made to wait behind it. Acceptable
     * because the work is bounded by history size and is a read-and-write per finished run with no
     * IO of its own — the history backup is deliberately taken *after* the commit rather than
     * inside, so a file copy never sits inside the lock.
     *
     * Defaults to running the block as-is, for tests that drive the DAOs directly. The production
     * wiring is a single line in `AppContainer`; without it this silently loses its atomicity, so
     * that line is the thing to look for if half-moved history ever shows up.
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { it() }
) {
    suspend fun deleteSession(sessionId: Long) {
        sessionDao.deleteSessionById(sessionId)
        refreshHistoryBackup?.invoke()
    }

    /**
     * Held across the profile door, so a statement is a read, a re-tally and a store that nothing
     * interleaves with.
     *
     * Stating the pair together is one call now, so the two halves can no longer race each other —
     * but a blur commit and a way-out commit still can, and so can any future surface. Unserialized
     * they would each snapshot the settings before the other's write landed and re-tally against a
     * pair that was never stored: whichever tally finished last would stand, banded to half of one
     * profile and half of the other. The lock is what makes "the number the history was computed
     * from is the number that ends up stored" true rather than usually true.
     */
    private val statedProfile = Mutex()

    /**
     * The one door for stating either heart rate, or both at once.
     *
     * Both at once is the ordinary case: leaving the settings screen commits whatever is pending in
     * each field, and the first time a runner fills the pair in that is two numbers. Sent through
     * separately they were two coroutines racing for [statedProfile] — the lock kept them from
     * overlapping but said nothing about *order*, so the same two edits left different history
     * depending on which won. Resting-first re-tallies against the maximum about to be replaced;
     * maximum-first re-tallies against the final pair. One call, one re-tally, one answer.
     *
     * A null means "not stated in this commit" and leaves that number exactly as it is — which is
     * what makes this safe as the single door for the one-number blur commits too. It is *not* the
     * same as [RESTING_HR_UNSTATED], which is a resting heart rate being deliberately withdrawn.
     *
     * **What history is re-banded against** is the collision of the two numbers' rules, so it is
     * decided in one place here:
     * - A resting heart rate is a measurement that legitimately falls as fitness improves, not a
     *   correction, and a history banded half at one value and half at another cannot be compared
     *   with itself — which is the only thing zone history is for. So every statement re-tallies.
     * - Max HR is one-shot: the **first** deliberate set recomputes everything, because until then
     *   every run's zone times sit on the default `190` that nobody chose. Every change after that
     *   is future-only, so a later correction cannot quietly rewrite runs already read. This is
     *   Strava's rule read literally: only the first time you *set* zones.
     *
     * So the maximum the re-tally uses is the new one only on that first set, and
     * [UserSettings.historyMaxHr] — the one history is *already* banded against — ever after.
     * Not the stored maximum: after a future-only correction those differ, and re-banding against
     * the stored one would drag every run already read onto the later number. The resting heart
     * rate is always whichever is being stated. Both numbers
     * travel together because they bound one reserve, and a recompute against half of the runner's
     * profile would re-band history to a model nobody's zones are on.
     *
     * Recompute first, then store: an interruption leaves the old numbers on screen with history
     * part-converted, and the next statement redoes the whole thing (the tally is a pure
     * re-derivation, so repeating it costs nothing). Storing first would leave the settings screen
     * claiming a conversion that only half happened, and — for Max HR — would strand history
     * permanently half-converted behind a spent one-shot flag.
     *
     * Silent by design. There is nothing here to decide, and confirming a correction is nagging;
     * the one edit that *is* asked about is withdrawing a resting heart rate, and the screen asks
     * that before it ever reaches this door.
     */
    suspend fun setStatedProfile(maxHr: Int?, restingHr: Int?) = statedProfile.withLock {
        val settings = settingsRepository ?: return@withLock
        if (maxHr == null && restingHr == null) return@withLock
        val current = settings.userSettingsFlow.first()
        val clampedMaxHr = maxHr?.let { effectiveMaxHr(it) }
        val firstMaxHrSet = clampedMaxHr != null && !current.maxHrEverSet
        var rebandedAgainst: Int? = null

        if (restingHr != null || firstMaxHrSet) {
            val samples = sampleDao
            // Nothing to re-band from. For Max HR that is a reason to do nothing at all rather than
            // to record the set anyway: the flag is one-shot, so spending it here would strand
            // history on the placeholder with no way back. A resting heart rate carries no such
            // flag — there is simply no history to move — so it goes on and stores.
            if (samples == null) {
                // Storing the maximum would spend the one-shot on a recompute that never ran, so
                // it is left unstated and the next attempt redoes the whole thing. A resting heart
                // rate stated in the same breath is unaffected and still lands below.
                if (firstMaxHrSet) {
                    if (restingHr != null) settings.setStatedHeartRates(null, restingHr, rebandedHistoryAgainst = null)
                    return@withLock
                }
            } else {
                // The maximum history is *already* banded against, not the one in force. They
                // differ the moment a Max HR correction lands: that change is future-only, so the
                // runs keep the maximum they were banded on, and a resting-HR statement re-banding
                // against the current one would drag every run already read onto the later number
                // by a side door — the exact rewrite the one-shot exists to prevent.
                val historyMaxHr = if (firstMaxHrSet) clampedMaxHr!! else current.historyMaxHr
                val historyProfile = HrProfile(
                    maxHr = historyMaxHr,
                    restingHr = restingHr ?: current.restingHr
                )
                // Noted before any of it moves, and cleared only by the statement landing below —
                // see [SettingsRepository.beginStatement]. History and the profile live in
                // different stores, so this is what makes the pair of writes recoverable rather
                // than merely each atomic.
                settings.beginStatement(maxHr, restingHr)
                // All of history or none of it — see [inTransaction]. Half a re-tally is the split
                // this whole rule exists to prevent.
                inTransaction { recomputeZoneSecondsForAllRuns(samples, historyProfile) }
                rebandedAgainst = historyMaxHr
            }
        }

        settings.setStatedHeartRates(clampedMaxHr, restingHr, rebandedHistoryAgainst = rebandedAgainst)
        // Last, so the snapshot copies a database whose history and profile already agree, and so
        // a file copy of the whole database never sits inside the gap the note above covers.
        if (rebandedAgainst != null) refreshHistoryBackup?.invoke()
    }

    /**
     * A statement of the heart rates that began moving history and never landed, ready to be
     * stated again — or null when nothing was interrupted, which is the ordinary case.
     *
     * Read rather than applied, deliberately. `StatedHeartRateQueue` applies it ahead of everything
     * on its queue, which is the only placement that works: applied here it would be one more
     * unordered writer, and enqueued it would race a runner who reached Settings first — their
     * statement landing and then being overwritten by last session's leftover number, or clearing
     * the note with a statement that moves no history and stranding the already-re-banded runs.
     *
     * The whole statement is replayed rather than only the missing half, because a re-tally is a
     * pure re-derivation from per-second samples that are never pruned: doing it twice costs time
     * and changes nothing, and doing it again is the only way to be sure which half was reached.
     */
    suspend fun interruptedStatement(): StatedHeartRates? {
        val settings = settingsRepository ?: return null
        val interrupted = settings.interruptedStatement() ?: return null
        if (interrupted.maxHr == null && interrupted.restingHr == null) {
            // Nothing to replay. Unreachable from `beginStatement`, so this is a corrupt note —
            // dropped rather than left to be found again on every launch for ever.
            Log.w("StatedProfile", "Discarding a heart-rate statement with nothing in it")
            settings.discardStatement()
            return null
        }
        Log.w("StatedProfile", "Finishing an interrupted heart-rate statement: $interrupted")
        return interrupted
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
     *
     * Runs inside [inTransaction], and does not refresh the history backup itself: the caller does
     * that once the transaction has committed, because a snapshot taken mid-transaction would copy
     * a history half-moved, and file IO inside a database transaction holds the write lock open for
     * the length of a file copy.
     */
    private suspend fun recomputeZoneSecondsForAllRuns(samples: SampleDao, profile: HrProfile) {
        sessionDao.getFinalizedSessionIds().forEach { sessionId ->
            val tally = tallyZoneSeconds(samples.getRawBpmsForSession(sessionId), profile)
            sessionDao.updateZoneSeconds(
                sessionId = sessionId,
                zone1 = tally.zone1,
                zone2 = tally.zone2,
                zone3 = tally.zone3,
                zone4 = tally.zone4,
                zone5 = tally.zone5
            )
        }
    }

    /**
     * Track points accepted for map drawing (#38): BACKFILL points are historical breadcrumbs with
     * no recorded GPS accuracy and are always kept; GPS points must meet the same
     * [SessionRecorder.ACCURACY_THRESHOLD_METERS] bar applied live during recording, so what the
     * runner heard mid-run matches what they see on the map afterward.
     */
    suspend fun getTrackPointsForMap(sessionId: Long): List<TrackPoint> {
        val dao = trackPointDao ?: return emptyList()
        return dao.getTrackPointsForSessionOnce(sessionId).acceptedForMap()
    }

    /** One-shot read of a finished run, for callers that need it once rather than as a stream. */
    suspend fun getSession(sessionId: Long): RunnerSession? = sessionDao.getSessionById(sessionId)

    /**
     * Fills in [RunnerSession.movingTimeSeconds] for runs recorded before #163, so a run already in
     * history reports the same pace a run recorded today would.
     *
     * The v19 migration adds the column but leaves it null: working the number out means measuring
     * geodesic distances between every pair of a run's track points, which belongs in Kotlin rather
     * than in SQL. Safe to call more than once — a run is only looked at while its column is null.
     *
     * A run with no usable track keeps a null rather than a stored zero. Null means "measured
     * against the run's duration instead" ([paceClockSeconds]); a zero would mean "this run never
     * moved", and would put every treadmill run's pace at --:--.
     */
    suspend fun backfillMovingTime() {
        val sessionIds = sessionDao.getSessionIdsMissingMovingTime()
        if (sessionIds.isEmpty()) return
        val measured = sessionIds.count { computeMovingTime(it) != null }
        Log.d("MovingTime", "Backfilled moving time for $measured of ${sessionIds.size} run(s)")
    }

    /**
     * Measures a finished run's moving time from its stored track and saves it, along with the
     * average pace that follows from it (#163). Returns the moving time, or null for a run with no
     * usable track to measure — a treadmill run, or GPS history too sparse to say anything.
     *
     * Zero is an answer, not a failure, and is stored like any other. A run of two good fixes that
     * never got anywhere really did move for none of its length: saying so leaves it reading
     * `--:--` with Moving 00:00, where a null would quietly pace it over its duration and let GPS
     * jitter show as a pace the runner never ran. Null is kept for the one case that earns it —
     * too little track to measure at all.
     *
     * Measured over the same accuracy-filtered points the map and the GPX export use, so a fix the
     * run itself refused can't reappear here as a phantom sprint.
     */
    suspend fun computeMovingTime(sessionId: Long): Long? {
        val session = sessionDao.getSessionById(sessionId) ?: return null
        val points = getTrackPointsForMap(sessionId)
        if (points.size < 2) return null

        // Capped at the run's own clock, and never below zero. Moving time is measured on
        // wall-clock track timestamps while durationSeconds excludes paused time, so a pause the
        // track cannot see would otherwise let moving time exceed the run it belongs to - and the
        // summary card would show a negative resting time.
        val movingTime = measureMovingTimeSeconds(points)
            .coerceAtMost(session.durationSeconds)
            .coerceAtLeast(0)

        sessionDao.setMovingTime(
            sessionId = sessionId,
            movingTimeSeconds = movingTime,
            avgPaceMinPerKm = averagePaceMinPerKm(movingTime, session.distanceKm),
        )
        return movingTime
    }

    /** One-shot read of a run's heart-rate samples, ordered by elapsed second. */
    suspend fun getHrSamples(sessionId: Long): List<HrSample> =
        sampleDao?.getSamplesForSessionOnce(sessionId) ?: emptyList()

    /**
     * Live version of [getTrackPointsForMap] (#40): the in-run map card's trail redraws as new
     * points are recorded, filtered by the same #38 accuracy rule.
     */
    fun getTrackPointsForMapFlow(sessionId: Long): Flow<List<TrackPoint>> {
        val dao = trackPointDao ?: return flowOf(emptyList())
        return dao.getTrackPointsForSession(sessionId).map { points -> points.acceptedForMap() }
    }

    /**
     * Whether a run can be exported (#84) — judged on the same accuracy-gated points the map and the
     * GPX file are built from, so Share is never offered for a run the export would find empty. False
     * for a treadmill run, and for history recorded before #37.
     *
     * Also false until the run has finished: history stays reachable mid-run, and a run still being
     * written would export a snapshot that stops short of where the runner actually is.
     */
    fun hasTrackFlow(sessionId: Long): Flow<Boolean> =
        combine(
            sessionDao.getSessionByIdFlow(sessionId),
            getTrackPointsForMapFlow(sessionId)
        ) { session, points ->
            session != null && session.isFinished() && points.isNotEmpty()
        }

    /**
     * The accuracy gate, applied without losing where the run was paused.
     *
     * A resume is recorded on one point ([TrackPoint.startsAfterPause]), and that point is the most
     * likely in the whole run to be thrown out: the run resumes on the first fix after GPS was torn
     * down and re-acquired, which is exactly when accuracy is at its worst. Dropping it would take
     * the pause with it — the next point kept says nothing happened — and the route would be drawn
     * and measured straight across ground the runner covered while stopped.
     *
     * So the boundary moves to whichever point survives to take its place. The pause is a fact about
     * the run, not about the fix that happened to carry it.
     */
    private fun List<TrackPoint>.acceptedForMap(): List<TrackPoint> {
        var pauseToCarry = false
        return mapNotNull { point ->
            if (!point.isAcceptedForMap()) {
                pauseToCarry = pauseToCarry || point.startsAfterPause
                null
            } else {
                val carried = point.startsAfterPause || pauseToCarry
                pauseToCarry = false
                if (carried == point.startsAfterPause) point else point.copy(startsAfterPause = true)
            }
        }
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
            AiRecentRun(
                durationSeconds = session.durationSeconds,
                avgHr = session.avgBpm,
                sessionType = if (session.isRunWalkMode) AI_LABEL_RUN_WALK else AI_LABEL_OPEN_RUN,
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
                // Any standing prescription is deliberately left alone — see evaluateProgress.
                Log.d("AiCoach", "No new prescription: the coach could not be reached. stageId=$stageId")
                return
            }
            // Warm-up/cool-down now live on the workout (#107); the load clamp accounts for the
            // active workout's envelope so the estimated total stays comparable to real sessions.
            val activeWorkout = TrainingPlanProvider.resolveBaseWorkout(
                settings.activePlanId,
                settings.activeStageId
            )
            // Ceiling first, then floor: the floor wins where they disagree (#170). The ceiling is
            // measured against recorded runs, so a run cut short drags it below the plan — the
            // stage's own workout is the commitment and outranks that.
            val clampedResponse = floorAiResponseAtWorkout(
                clampAiResponseByRecentLoad(
                    response,
                    warmUpSeconds = activeWorkout?.warmUpSeconds ?: 0,
                    coolDownSeconds = activeWorkout?.coolDownSeconds ?: 0
                ),
                activeWorkout
            )
            Log.d(
                "AiCoach",
                "Gemini response received! Adjusted intervals: ${clampedResponse.nextRunDurationSeconds}s Run / " +
                    "${clampedResponse.nextWalkDurationSeconds}s Walk. Message: ${clampedResponse.coachMessage}"
            )

            // Everything below was reasoned about against this plan and stage, read before a
            // network round trip that takes seconds. Carried into each write so the write itself
            // can refuse if the runner changed plans meanwhile — see CoachWriteScope.
            val scope = CoachWriteScope(settings.activePlanId, settings.activeStageId)

            settingsRepo.setLatestCoachMessage(clampedResponse.coachMessage, scope)

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
                settingsRepo.advanceStageAndClearPrescriptions(nextStageId, scope)
            } else if (activeWorkout == null) {
                // A prescription is now stored under the Run Type it is about (#175), and with no
                // plan attached there is no Workout to name one. Nothing would have read such a
                // prescription anyway — a run with no plan runs open-ended — so this stores nothing
                // rather than inventing a kind for it.
                Log.d("AiCoach", "No new prescription: no plan is attached, so no Run Type to store it under.")
            } else {
                coachPrescriptionRepository?.prescribe(
                    // The Workout the evaluation was floored against and reasoned about, so its kind
                    // is the slot the prescription belongs in.
                    runType = activeWorkout.runType,
                    prescription = CoachPrescription(
                        targetZone = coachTargetZone(
                            requested = clampedResponse.nextTargetZone,
                            workoutTargetZone = activeWorkout.targetZone,
                            settingsTargetZone = settings.targetZone
                        ),
                        runDurationSeconds = clampedResponse.nextRunDurationSeconds,
                        walkDurationSeconds = clampedResponse.nextWalkDurationSeconds,
                        totalRepeats = clampedResponse.nextRepeats,
                        prescribedAtEpochMillis = System.currentTimeMillis()
                    ),
                    scope = scope
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

    /**
     * The coach may make today harder than the Stage's own Workout, never easier (#170).
     *
     * The 110% ceiling above is floored nowhere, so anything shorter used to pass straight through,
     * and because that ceiling is measured against *recorded* Run durations, a Run cut short lowered
     * the next one directly — a ratchet that only turned down. The Stage's own Workout is a rule a
     * runner can hold in their head: the Workout is the commitment, the coach adjusts upward from
     * it.
     *
     * Being derived from static Plan data, it also still holds where the ceiling silently no-ops —
     * with no 30-day maximum at all, which is every time run history is wiped.
     *
     * Accepted cost: the coach cannot ease anyone back in below the Workout after illness or a
     * layoff. Dropping a Stage by hand is the move there.
     *
     * What counts as clearing the Workout is [clearedBy], which the Prescription is measured
     * against again when it is applied — the Plan's own numbers can change while one stands.
     *
     * Raising means taking the Workout's three numbers whole rather than scaling toward it — a
     * half-raised Prescription would be a shape neither the coach nor the Plan asked for. The
     * coach's target zone is untouched: this rule is about how much work, not how hard. A
     * Prescription that clears the floor is returned exactly as it came, coercions included:
     * sanitising is the ceiling's job, and doing it twice would be two places to disagree.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun floorAiResponseAtWorkout(
        response: AiCoachResponse,
        workout: WorkoutTemplate?
    ): AiCoachResponse {
        if (workout == null) return response

        val clearsFloor = workout.clearedBy(
            runSeconds = response.nextRunDurationSeconds.coerceAtLeast(1),
            walkSeconds = response.nextWalkDurationSeconds.coerceAtLeast(0),
            repeats = response.nextRepeats.coerceAtLeast(1)
        )
        if (clearsFloor) return response

        return response.copy(
            nextRunDurationSeconds = workout.runDurationSeconds,
            nextWalkDurationSeconds = workout.walkDurationSeconds,
            nextRepeats = workout.totalRepeats
        )
    }

    private fun computePlannedTotalSeconds(
        runSeconds: Int,
        walkSeconds: Int,
        repeats: Int,
        warmupSeconds: Int,
        cooldownSeconds: Int
    ): Long =
        warmupSeconds.toLong() +
            mainSetSeconds(runSeconds, walkSeconds, repeats) +
            cooldownSeconds.toLong()

    /** The run/walk repeats alone, without the warm-up/cool-down envelope around them. */
    private fun mainSetSeconds(runSeconds: Int, walkSeconds: Int, repeats: Int): Long =
        (runSeconds.toLong() + walkSeconds.toLong()) * repeats.toLong()

}
