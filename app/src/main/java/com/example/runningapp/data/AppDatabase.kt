package com.example.runningapp.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.runningapp.HrZone
import com.example.runningapp.HrProfile
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.hrZoneOf
import com.example.runningapp.run.RunMode
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class RunnerSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long = 0,
    val durationSeconds: Long = 0,
    val avgBpm: Int = 0,
    val maxBpm: Int = 0,
    // The target this run actually had, so in-target time survives a later change to the global
    // (#97). Written from the global today; the workout becomes its source in #107.
    val targetZone: Int = HrZone.DEFAULT_TARGET.number,
    val zone1Seconds: Long = 0,
    val zone2Seconds: Long = 0,
    val zone3Seconds: Long = 0,
    val zone4Seconds: Long = 0,
    val zone5Seconds: Long = 0,
    val runMode: String = "treadmill",
    val distanceKm: Double = 0.0,
    val avgPaceMinPerKm: Double = 0.0,
    val noDataSeconds: Long = 0L,
    val walkBreaksCount: Int = 0,
    val isRunWalkMode: Boolean = false,
    val includeInAiTraining: Boolean = true,
    // Post-run "How did that feel?" feedback. Context only — must never feed
    // the Effort/TRIMP or Fitness/Fatigue/Form math (#60).
    val perceivedEffort: Int? = null,
    val sessionNote: String? = null,
    // Start position, captured from the first GPS fix of the run. Null for treadmill/no-GPS
    // sessions, or if no fix arrived before the run ended.
    val startLatitude: Double? = null,
    val startLongitude: Double? = null,
    // Weather snapshot at save (#79), fetched from Open-Meteo off the save path.
    val weatherTempC: Double? = null,
    val weatherFeelsLikeC: Double? = null,
    val weatherHumidityPercent: Int? = null,
    val weatherWindSpeedKmh: Double? = null,
    val weatherConditionCode: Int? = null,
    // The run minus the spells the runner spent going nowhere, computed from the recorded track
    // the way Strava computes it (#163). Null until it has been computed: a run recorded before
    // v19, a run still being written, or a run with no GPS track to compute it from.
    val movingTimeSeconds: Long? = null,
    /**
     * What the Run cost the runner: Edwards zone-weighted TRIMP over its own seconds (#61), banked
     * at the finish because it is a fact about the Run rather than a view of it.
     *
     * Null is "no score", and it is not the same as a zero. A Run that recorded no heart rate at all
     * — no Strap, or one that never read — has nothing to score and shows nothing; a Run that spent
     * every second below Zone 1 scores 0, which is a measurement of a very easy hour. Also null on
     * every Run recorded before v21, until the history backfill (#62) reaches them.
     *
     * Deliberately unrelated to [perceivedEffort]: that is how the Run *felt*, and it must never
     * feed this or anything derived from it.
     */
    val effortScore: Int? = null,
    /**
     * Whether this Run has been measured against the record book (#210).
     *
     * False is a debt, not a verdict: it says nobody has scored this Run yet, never that the Run
     * won nothing. A Run scores itself the moment it finishes, but that scoring can be missed —
     * the process killed between the row being stamped finished and the book being written, or
     * the write itself throwing and being logged — and nothing revisits a finished Run afterwards.
     * The launch pass ([SessionRepository.scoreMissedRecords]) finds every finished Run still
     * carrying a false here and scores it.
     *
     * Written only once scoring has *returned*, never in the same breath as the row being stamped
     * finished. That way any ending in between leaves the Run owing a scoring, which costs one
     * redundant re-score at the next launch — and re-scoring is safe, because a Run's own standing
     * rows are dropped before it is ranked again.
     *
     * Not a replacement for the whole-history seeded mark, which answers a different question: this
     * says "this Run was measured", that covers a hole *below* the stored top three, which only a
     * full rebuild can fill.
     */
    val recordsScored: Boolean = false
)

/**
 * Whether the run has been saved with its totals. A row is inserted when the run starts and
 * stamped with an end time only when it finishes, so a zero here means the run is still being
 * written to — the state anything reading a run as a whole (the GPX export, #84) must wait for.
 */
fun RunnerSession.isFinished(): Boolean = endTime > 0

/**
 * Whether the Run was recorded on a treadmill — the one thing that distinguishes two Runs now that
 * the four session types are gone (#94), and the sole thing separating a Stated Distance from a
 * measured one (#231, ADR 0008).
 *
 * Asked here rather than by comparing `runMode` against a string wherever it comes up: an unknown
 * value has to fall the same way everywhere, and [RunMode.ofSettingValue] is where that is decided.
 */
fun RunnerSession.isTreadmill(): Boolean = RunMode.ofSettingValue(runMode) == RunMode.TREADMILL

/** The five zone columns, reachable by zone rather than by name. */
fun RunnerSession.secondsInZone(zone: HrZone): Long = when (zone) {
    HrZone.ENDURANCE -> zone1Seconds
    HrZone.MODERATE -> zone2Seconds
    HrZone.TEMPO -> zone3Seconds
    HrZone.THRESHOLD -> zone4Seconds
    HrZone.ANAEROBIC -> zone5Seconds
}

/**
 * Time on target: exactly the target zone's own seconds.
 *
 * Derived rather than stored. The old `timeInTargetZoneSeconds` column banked by *band* while
 * the five zone columns banked by *zone*, so it could only ever disagree with the numbers beside
 * it (#106). Now that a target is a zone, "in target" means "in zone N" and there is nothing
 * left to store.
 */
val RunnerSession.inTargetZoneSeconds: Long
    get() = secondsInZone(HrZone.ofNumberOrDefault(targetZone))

@Entity(
    tableName = "hr_samples",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class HrSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val elapsedSeconds: Long,
    val rawBpm: Int,
    val smoothedBpm: Int,
    val connectionState: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val paceMinPerKm: Double? = null,
    // Wall clock of the second this sample was banked. [elapsedSeconds] counts *running* seconds, so
    // it stops during a pause and no longer says when the reading happened — which is what anything
    // lining heart rate up against the GPS track needs (#84). Null on rows written before v16.
    val timestampMillis: Long? = null
)

@Entity(
    tableName = "run_walk_interval_stats",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class RunWalkIntervalStat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val intervalIndex: Int,
    val plannedDurationSeconds: Int,
    val actualRunningDurationBeforeHrTriggerSeconds: Int,
    val timeIntoIntervalWhenHrExceededCapSeconds: Int? = null,
    val hrTriggerEvents: Int,
    val totalTimeSpentWalkingDuringRunIntervalSeconds: Int,
    val avgHrAtTriggerInInterval: Double? = null,
    val avgRecoverySecondsAfterTriggerInInterval: Double? = null
)

data class MaxSessionLoad30dProjection(
    val maxDistanceKm: Double?,
    val maxDurationSeconds: Long?
)

/**
 * A finished Run reduced to the two things the Fitness and Fatigue curves need (#63): the day it
 * began, and what it cost.
 *
 * A projection rather than the whole row because the curves are read over a runner's entire history
 * at once — hundreds of rows of route, weather and zone times, to add up one integer per day.
 */
data class ScoredRunProjection(
    val startTime: Long,
    val effortScore: Int
)

/**
 * A finished Run reduced to what a week of training is totalled from (#64): the day it began, and
 * the three things a week can be counted in.
 *
 * [movingTimeSeconds] comes back beside [durationSeconds] rather than instead of it because a Run
 * recorded before #163, or on a treadmill, has no moving time at all — the caller picks, the same
 * way [paceClockSeconds] does.
 */
data class RunVolumeProjection(
    val startTime: Long,
    val distanceKm: Double,
    val durationSeconds: Long,
    val movingTimeSeconds: Long?,
    val effortScore: Int?
)

/** How many medals one Run holds — what the medal badge on its History row counts (#51). */
data class SessionMedalCount(
    val sessionId: Long,
    val medals: Int
)

object TrackPointSource {
    const val GPS = "GPS"
    const val BACKFILL = "BACKFILL"
}

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    // Null for BACKFILL points — historical hr_samples breadcrumbs never recorded GPS accuracy.
    val horizontalAccuracyMeters: Float? = null,
    val verticalAccuracyMeters: Float? = null,
    val speedMps: Float? = null,
    // Raw barometer pressure at the time of the fix, hPa. Null on phones without a barometer.
    val barometerPressureHpa: Float? = null,
    val timestampMillis: Long,
    val source: String,
    // True on the first fix recorded after the run resumed — the far side of a pause, and the only
    // record that one happened at all. Nothing else marks it: a manual pause stops GPS and an
    // auto-pause is not written to the track, so a pause leaves only an absence, and a short one
    // leaves an absence too small to tell from a sparse patch of a run in progress. Anything drawing
    // or measuring the route must break here rather than joining across ground the runner covered
    // while stopped (#84). False on every row recorded before the column existed.
    val startsAfterPause: Boolean = false
)

/**
 * A medal one Run won, and the effort it won it with (#49).
 *
 * The one thing about records that is banked rather than worked out on read. Everything else the
 * detail page shows is measured off the stored track each time it is opened, but a medal is a fact
 * about a Run *relative to every other Run*, and answering "was this a personal best" by re-measuring
 * the whole history would be an hour of GPS arithmetic to draw one card.
 *
 * At most three rows per [type] — the all-time top three — so the table is the record book itself.
 * [value] is seconds or metres, whichever [RecordType.unit] says; there is no row without a
 * [RecordType] to read it by. Deleted with its Run, like every other recording of one.
 */
@Entity(
    tableName = "achievements",
    foreignKeys = [
        ForeignKey(
            entity = RunnerSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("type")]
)
data class Achievement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: RecordType,
    val medal: Medal,
    val value: Double
)

@Dao
interface AchievementDao {
    @Insert
    suspend fun insertAchievements(achievements: List<Achievement>)

    /** The whole record book — seven records, three places each, so it is read in one go. */
    @Query("SELECT * FROM achievements")
    suspend fun getAllAchievements(): List<Achievement>

    /** What one Run won, for its own page. */
    @Query("SELECT * FROM achievements WHERE sessionId = :sessionId")
    fun getAchievementsForSessionFlow(sessionId: Long): Flow<List<Achievement>>

    /**
     * How many medals each Run holds, counted in the database rather than by reading the book out
     * and tallying it here (#51).
     *
     * The whole history in one row per Run that won anything, because the History list asks about
     * twenty Runs at once and a query per row is twenty round trips to draw one screen. A Run with
     * no medals is simply absent, which is the same answer as a zero.
     */
    @Query("SELECT sessionId, COUNT(*) AS medals FROM achievements GROUP BY sessionId")
    fun getMedalCountsFlow(): Flow<List<SessionMedalCount>>

    /**
     * What Runs about to be deleted hold, asked once (#50).
     *
     * Read *before* the delete, because the rows cascade away with their Run: what is wanted is
     * which records are about to lose a place, and after the delete there is nothing left to say.
     */
    @Query("SELECT * FROM achievements WHERE sessionId IN (:sessionIds)")
    suspend fun getAchievementsForSessions(sessionIds: List<Long>): List<Achievement>

    /**
     * Clears the records a Run is about to be ranked into, so the rewritten places replace the old
     * ones rather than joining them. Only the types being re-ranked: the rest of the book is
     * untouched by a Run that never contested it.
     */
    @Query("DELETE FROM achievements WHERE type IN (:types)")
    suspend fun deleteAchievementsOfTypes(types: List<RecordType>)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: RunnerSession): Long

    @Update
    suspend fun updateSession(session: RunnerSession)

    @Query("SELECT * FROM sessions ORDER BY startTime DESC LIMIT 20")
    fun getLast20Sessions(): Flow<List<RunnerSession>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): RunnerSession?

    /**
     * Runs left unfinished by a previous process, oldest first (#192).
     *
     * `endTime = 0` is what every other query here reads as "still being recorded", so the cut-off
     * is what tells a Run that died from the one being recorded right now: [startedBeforeMillis] is
     * the moment this process started, and a Run that began before that cannot be this process's.
     * Passing it in rather than reading the clock here keeps the boundary the caller's, and keeps
     * this query a question about the database rather than about the time.
     */
    @Query("SELECT id FROM sessions WHERE endTime = 0 AND startTime < :startedBeforeMillis ORDER BY startTime ASC")
    suspend fun getInterruptedSessionIds(startedBeforeMillis: Long): List<Long>

    /**
     * Every run there is, oldest first — the whole history, for the archive that has to carry all
     * of it (#85). Unfiltered on purpose: a run still being recorded is part of what the database
     * holds, and an archive that quietly left rows out would not be the backup it claims to be.
     */
    @Query("SELECT * FROM sessions ORDER BY startTime ASC")
    suspend fun getAllSessions(): List<RunnerSession>

    /**
     * How much history is here, and how recent it is — the two numbers a restore weighs the picked
     * file against before replacing any of it (#86).
     *
     * Counted the same unfiltered way as [getAllSessions], and for the same reason: the runner is
     * being told what they stand to lose, and a run still being recorded is part of that.
     */
    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun countSessions(): Int

    /** When the most recent run started, or null when there is no history at all. */
    @Query("SELECT MAX(startTime) FROM sessions")
    suspend fun newestSessionStartTime(): Long?

    /** Finished outdoor runs whose moving time has not been computed yet (#163 backfill). */
    @Query("SELECT id FROM sessions WHERE movingTimeSeconds IS NULL AND endTime > 0 AND runMode = 'outdoor' ORDER BY startTime DESC")
    suspend fun getSessionIdsMissingMovingTime(): List<Long>

    @Query("UPDATE sessions SET movingTimeSeconds = :movingTimeSeconds, avgPaceMinPerKm = :avgPaceMinPerKm WHERE id = :sessionId")
    suspend fun setMovingTime(sessionId: Long, movingTimeSeconds: Long, avgPaceMinPerKm: Double)

    /**
     * Finished Runs nobody has measured against the record book yet (#210).
     *
     * `endTime > 0` for the same reason every other query here reads it that way: a Run still being
     * recorded has nothing to score, and will score itself when it finishes. Oldest first, so a
     * history being paid off in one pass is scored in the order it was run.
     */
    @Query("SELECT id FROM sessions WHERE recordsScored = 0 AND endTime > 0 ORDER BY startTime ASC")
    suspend fun getSessionIdsMissingRecordScoring(): List<Long>

    /** Marks one Run as measured against the book — written only after its scoring has landed. */
    @Query("UPDATE sessions SET recordsScored = 1 WHERE id = :sessionId")
    suspend fun setRecordsScored(sessionId: Long)

    /**
     * Marks the Runs a whole-history rebuild measured (#210, #50).
     *
     * The seeding pass measures every stored Run at once, so the debt every one of them carried is
     * paid by the same book — but only the Runs it actually read. A Run that finished after it read
     * history is not on this list: it scores itself, and if that scoring was missed the debt is
     * still its own to owe.
     */
    @Query("UPDATE sessions SET recordsScored = 1 WHERE id IN (:sessionIds)")
    suspend fun setRecordsScoredForSessions(sessionIds: List<Long>)

    /**
     * Writes a Stated Distance and the pace that follows from it (#231).
     *
     * The two together, because pace is quoted from the stored column in the archive and the export:
     * a distance written without it would leave a Run reading as fast as it did when it had gone
     * nowhere. Zero is how a distance is withdrawn, which is the same zero a Run nobody stated one
     * for has carried all along.
     */
    @Query("UPDATE sessions SET distanceKm = :distanceKm, avgPaceMinPerKm = :avgPaceMinPerKm WHERE id = :sessionId")
    suspend fun setStatedDistance(sessionId: Long, distanceKm: Double, avgPaceMinPerKm: Double)

    @Query("UPDATE sessions SET perceivedEffort = :effort, sessionNote = :note WHERE id = :sessionId")
    suspend fun updateFeelFeedback(sessionId: Long, effort: Int?, note: String?)

    @Query(
        """
        UPDATE sessions
        SET weatherTempC = :tempC,
            weatherFeelsLikeC = :feelsLikeC,
            weatherHumidityPercent = :humidityPercent,
            weatherWindSpeedKmh = :windSpeedKmh,
            weatherConditionCode = :conditionCode
        WHERE id = :sessionId
        """
    )
    suspend fun updateWeather(
        sessionId: Long,
        tempC: Double,
        feelsLikeC: Double,
        humidityPercent: Int,
        windSpeedKmh: Double,
        conditionCode: Int
    )

    @Query(
        """
        SELECT * FROM sessions
        WHERE runMode = 'outdoor'
          AND startLatitude IS NOT NULL
          AND startLongitude IS NOT NULL
          AND weatherTempC IS NULL
          AND endTime > 0
        """
    )
    suspend fun getOutdoorSessionsMissingWeather(): List<RunnerSession>

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    fun getSessionByIdFlow(sessionId: Long): Flow<RunnerSession?>

    @Query("SELECT * FROM sessions WHERE endTime > 0 AND durationSeconds > 120 ORDER BY startTime DESC LIMIT 3")
    suspend fun getLast3CompletedSessions(): List<RunnerSession>

    @Query("SELECT * FROM sessions WHERE endTime > 0 AND durationSeconds > 120 AND includeInAiTraining = 1 ORDER BY startTime DESC LIMIT 3")
    suspend fun getLast3AiEligibleCompletedSessions(): List<RunnerSession>

    @Query("SELECT * FROM sessions WHERE endTime > 0 ORDER BY endTime DESC LIMIT 1")
    suspend fun getMostRecentFinalizedSession(): RunnerSession?

    @Query(
        """
        SELECT
            MAX(CASE WHEN distanceKm > 0 THEN distanceKm END) AS maxDistanceKm,
            MAX(durationSeconds) AS maxDurationSeconds
        FROM sessions
        WHERE endTime > 0
          AND endTime >= :cutoffEpochMillis
        """
    )
    suspend fun getMaxSessionLoadLast30Days(cutoffEpochMillis: Long): MaxSessionLoad30dProjection

    /**
     * Finished runs only — `endTime > 0` is what finalized means here, as in the queries above.
     *
     * A run in progress must stay out of the one-shot retally (#112): the recorder finalizes it
     * from its own in-memory zone counters, so a retallied row would be overwritten anyway, and
     * the flag would be spent on a run that ends up inconsistent with it.
     */
    @Query("SELECT id FROM sessions WHERE endTime > 0")
    suspend fun getFinalizedSessionIds(): List<Long>

    /**
     * Re-derives what a finished Run is banded as — its zone seconds and, with them, what it cost.
     *
     * The two travel in one statement because they are read off the same beats against the same
     * zone edges (#61): a Run whose zone times move to new edges while its Effort Score stays on the
     * old ones would show a runner two numbers about one hour that no longer describe the same
     * seconds — the very thing #99 says must agree by construction.
     *
     * A Run that has no Score keeps none. Scoring history is the backfill's job (#62), and a
     * re-tally that quietly scored some of it would leave that pass unable to tell what it had
     * already reached from what it had not.
     */
    @Query(
        """
        UPDATE sessions
        SET zone1Seconds = :zone1,
            zone2Seconds = :zone2,
            zone3Seconds = :zone3,
            zone4Seconds = :zone4,
            zone5Seconds = :zone5,
            effortScore = CASE WHEN effortScore IS NULL THEN NULL ELSE :effortScore END
        WHERE id = :sessionId
        """
    )
    suspend fun updateZoneSecondsAndEffort(
        sessionId: Long,
        zone1: Long,
        zone2: Long,
        zone3: Long,
        zone4: Long,
        zone5: Long,
        effortScore: Int?
    )

    /**
     * The finished Runs that have no Effort Score yet — the backfill's work list (#62).
     *
     * Re-derived from the rows themselves rather than tracked in a flag, which is what makes the
     * pass resumable: a process killed half way through leaves the Runs it reached scored and the
     * rest still on this list, so the next launch picks up exactly where it stopped without anything
     * having to have been written down.
     *
     * Newest first, so a history too long to finish in one launch is scored from the end the runner
     * is actually looking at.
     *
     * A Run that recorded no beats has no Score to compute and stays on this list for ever. That
     * costs one read of its (empty) samples per launch and nothing else — see
     * [SessionRepository.backfillEffortScores] for why that is preferred to storing a zero.
     */
    @Query("SELECT id FROM sessions WHERE endTime > 0 AND effortScore IS NULL ORDER BY startTime DESC")
    suspend fun getSessionIdsMissingEffort(): List<Long>

    /**
     * Stores what a finished Run cost, on its own (#62).
     *
     * Unconditional, unlike [updateZoneSecondsAndEffort], which moves a Score only where one already
     * exists: this is the one place a Score is written where there was none.
     */
    @Query("UPDATE sessions SET effortScore = :effortScore WHERE id = :sessionId")
    suspend fun setEffortScore(sessionId: Long, effortScore: Int)

    /**
     * Every scored Run in history, oldest first — the whole input to the Fitness and Fatigue
     * curves (#63).
     *
     * The whole history and not a window of it, however short a range the Progress screen is showing:
     * a 42-day average of the last three months would start from zero three months ago and read as a
     * runner who has just taken up running. The window is applied to the drawn curve, not to what it
     * is built from.
     *
     * A Run with no Score is left out rather than counted as zero, which is the same distinction the
     * backfill makes (#62): no Score means nothing was measured, and a day of unmeasured training is
     * not a day of rest. Runs still in progress are out too, as everywhere else, by `endTime > 0`.
     *
     * A stream, so a Score arriving from the backfill redraws the curves under the runner rather than
     * waiting for the screen to be left and re-entered.
     */
    @Query(
        """
        SELECT startTime, effortScore FROM sessions
        WHERE endTime > 0 AND effortScore IS NOT NULL
        ORDER BY startTime ASC
        """
    )
    fun getScoredRunsFlow(): Flow<List<ScoredRunProjection>>

    /**
     * Every finished Run in history, oldest first — what the weekly volume bars are totalled from
     * (#64).
     *
     * Every finished Run and not only the scored ones, unlike [getScoredRunsFlow]: a Run recorded
     * without a Strap has no Effort Score to give, but it still covered ground and still took an
     * hour, and a week that leaves it out is not the week the runner ran.
     *
     * The whole history rather than the range showing, for the plainer reason this time — the
     * window is a filter over the weeks, so switching range is not another trip to the database.
     */
    @Query(
        """
        SELECT startTime, distanceKm, durationSeconds, movingTimeSeconds, effortScore FROM sessions
        WHERE endTime > 0
        ORDER BY startTime ASC
        """
    )
    fun getRunVolumesFlow(): Flow<List<RunVolumeProjection>>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    @Query("DELETE FROM sessions WHERE id IN (:sessionIds)")
    suspend fun deleteSessionsByIds(sessionIds: List<Long>)
}

@Dao
interface SampleDao {
    @Insert
    suspend fun insertSample(sample: HrSample)

    @Query("SELECT * FROM hr_samples WHERE sessionId = :sessionId ORDER BY elapsedSeconds ASC")
    fun getSamplesForSession(sessionId: Long): Flow<List<HrSample>>

    @Query("SELECT * FROM hr_samples WHERE sessionId = :sessionId ORDER BY elapsedSeconds ASC")
    suspend fun getSamplesForSessionOnce(sessionId: Long): List<HrSample>

    // Just the beats, for re-tallying zone seconds one run at a time (#112) — the full rows would
    // be an order of magnitude more memory for a number that only needs the BPM.
    @Query("SELECT rawBpm FROM hr_samples WHERE sessionId = :sessionId")
    suspend fun getRawBpmsForSession(sessionId: Long): List<Int>
}

@Dao
interface TrackPointDao {
    @Insert
    suspend fun insertTrackPoint(trackPoint: TrackPoint)

    @Insert
    suspend fun insertTrackPoints(trackPoints: List<TrackPoint>)

    // Raw, unfiltered by accuracy — map-drawing callers should go through
    // SessionRepository.getTrackPointsForMap() instead so the #38 accuracy gate applies.
    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY timestampMillis ASC")
    fun getTrackPointsForSession(sessionId: Long): Flow<List<TrackPoint>>

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY timestampMillis ASC")
    suspend fun getTrackPointsForSessionOnce(sessionId: Long): List<TrackPoint>

    /**
     * Which runs have a route at all, asked once rather than a run at a time (#85). A treadmill run
     * has none, and a GPX file of a run that went nowhere would be an empty file with a name.
     *
     * Gated the same way [SessionRepository.getTrackPointsForMap] gates the points themselves (#38),
     * because the two have to agree: a run whose every fix was too vague to trust has rows here but
     * nothing that survives to be written, and asking only whether rows exist would put a
     * point-less GPX in the archive for a run the Share sheet refuses to export.
     */
    @Query(
        "SELECT DISTINCT sessionId FROM track_points " +
            "WHERE source = '${TrackPointSource.BACKFILL}' " +
            "OR (horizontalAccuracyMeters IS NOT NULL " +
            "AND horizontalAccuracyMeters <= :accuracyThresholdMeters)"
    )
    suspend fun getSessionIdsWithTrackPoints(accuracyThresholdMeters: Double): List<Long>
}

@Dao
interface RunWalkIntervalStatDao {
    @Insert
    suspend fun insertIntervalStat(stat: RunWalkIntervalStat): Long

    @Insert
    suspend fun insertIntervalStats(stats: List<RunWalkIntervalStat>)

    @Query("SELECT * FROM run_walk_interval_stats WHERE sessionId = :sessionId ORDER BY intervalIndex ASC")
    fun getIntervalStatsForSessionFlow(sessionId: Long): Flow<List<RunWalkIntervalStat>>

    @Query("SELECT * FROM run_walk_interval_stats WHERE sessionId = :sessionId ORDER BY intervalIndex ASC")
    suspend fun getIntervalStatsForSession(sessionId: Long): List<RunWalkIntervalStat>

    /** Every Interval of every Run, for the archive (#85). Ordered so the file reads run by run. */
    @Query("SELECT * FROM run_walk_interval_stats ORDER BY sessionId ASC, intervalIndex ASC")
    suspend fun getAllIntervalStats(): List<RunWalkIntervalStat>
}

@Database(
    entities = [
        RunnerSession::class,
        HrSample::class,
        RunWalkIntervalStat::class,
        TrackPoint::class,
        Achievement::class
    ],
    version = 23,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao
    abstract fun runWalkIntervalStatDao(): RunWalkIntervalStatDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun achievementDao(): AchievementDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * [hrProfileProvider] feeds the v12 → v13 zone recompute, which needs a heart-rate
         * profile that lives in DataStore rather than in the database. It is read lazily, from
         * inside the migration, so the settings read happens on Room's own thread and only on the
         * one launch that migrates.
         */
        fun getDatabase(context: android.content.Context, hrProfileProvider: () -> HrProfile): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "running_app_db"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    migration12To13(hrProfileProvider),
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN zone1Seconds INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN zone2Seconds INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN zone3Seconds INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN zone4Seconds INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN zone5Seconds INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN runMode TEXT NOT NULL DEFAULT 'treadmill'")
        database.execSQL("ALTER TABLE sessions ADD COLUMN distanceKm REAL NOT NULL DEFAULT 0.0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN avgPaceMinPerKm REAL NOT NULL DEFAULT 0.0")
        
        database.execSQL("ALTER TABLE hr_samples ADD COLUMN latitude REAL")
        database.execSQL("ALTER TABLE hr_samples ADD COLUMN longitude REAL")
        database.execSQL("ALTER TABLE hr_samples ADD COLUMN paceMinPerKm REAL")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN noDataSeconds INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN walkBreaksCount INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE sessions ADD COLUMN isRunWalkMode INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN sessionType TEXT NOT NULL DEFAULT 'Run/Walk'")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN includeInAiTraining INTEGER NOT NULL DEFAULT 1")
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `run_walk_interval_stats` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `intervalIndex` INTEGER NOT NULL,
                `plannedDurationSeconds` INTEGER NOT NULL,
                `actualRunningDurationBeforeHrTriggerSeconds` INTEGER NOT NULL,
                `timeIntoIntervalWhenHrExceededCapSeconds` INTEGER,
                `hrTriggerEvents` INTEGER NOT NULL,
                `totalTimeSpentWalkingDuringRunIntervalSeconds` INTEGER NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_run_walk_interval_stats_sessionId` ON `run_walk_interval_stats` (`sessionId`)"
        )
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE run_walk_interval_stats ADD COLUMN avgHrAtTriggerInInterval REAL"
        )
        database.execSQL(
            "ALTER TABLE run_walk_interval_stats ADD COLUMN avgRecoverySecondsAfterTriggerInInterval REAL"
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyPlannedDurationSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyActualDurationSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyTotalJogSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyTotalWalkSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyJogPercent INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyLongestJogBoutSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyWalkInterruptions INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyHrSummary TEXT")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyTimeAboveCapSeconds INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN easyDataQualitySummary TEXT")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN perceivedEffort INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN sessionNote TEXT")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE sessions ADD COLUMN startLatitude REAL")
        database.execSQL("ALTER TABLE sessions ADD COLUMN startLongitude REAL")
        database.execSQL("ALTER TABLE sessions ADD COLUMN weatherTempC REAL")
        database.execSQL("ALTER TABLE sessions ADD COLUMN weatherFeelsLikeC REAL")
        database.execSQL("ALTER TABLE sessions ADD COLUMN weatherHumidityPercent INTEGER")
        database.execSQL("ALTER TABLE sessions ADD COLUMN weatherWindSpeedKmh REAL")
        database.execSQL("ALTER TABLE sessions ADD COLUMN weatherConditionCode INTEGER")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `track_points` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `latitude` REAL NOT NULL,
                `longitude` REAL NOT NULL,
                `altitudeMeters` REAL,
                `horizontalAccuracyMeters` REAL,
                `verticalAccuracyMeters` REAL,
                `speedMps` REAL,
                `barometerPressureHpa` REAL,
                `timestampMillis` INTEGER NOT NULL,
                `source` TEXT NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_track_points_sessionId` ON `track_points` (`sessionId`)"
        )
        backfillTrackPointsFromHrSamples(database)
    }
}

/**
 * Backfills historical hr_samples lat/lon breadcrumbs into track_points as BACKFILL rows,
 * skipping consecutive duplicate coordinates per session. Walks a Cursor rather than using a
 * SQL window function for the dedup, since window functions need SQLite 3.25+ and this app's
 * minSdk 26 devices can ship with older bundled SQLite.
 */
private fun backfillTrackPointsFromHrSamples(database: SupportSQLiteDatabase) {
    val cursor = database.query(
        """
        SELECT h.sessionId, h.latitude, h.longitude, h.elapsedSeconds, s.startTime
        FROM hr_samples h
        JOIN sessions s ON s.id = h.sessionId
        WHERE h.latitude IS NOT NULL AND h.longitude IS NOT NULL
        ORDER BY h.sessionId ASC, h.elapsedSeconds ASC
        """.trimIndent()
    )
    val insertStatement = database.compileStatement(
        """
        INSERT INTO track_points
            (sessionId, latitude, longitude, altitudeMeters, horizontalAccuracyMeters, verticalAccuracyMeters, speedMps, barometerPressureHpa, timestampMillis, source)
        VALUES (?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, 'BACKFILL')
        """.trimIndent()
    )
    cursor.use { c ->
        var lastSessionId: Long? = null
        var lastLatitude: Double? = null
        var lastLongitude: Double? = null
        while (c.moveToNext()) {
            val sessionId = c.getLong(0)
            val latitude = c.getDouble(1)
            val longitude = c.getDouble(2)
            val elapsedSeconds = c.getLong(3)
            val startTime = c.getLong(4)

            val isConsecutiveDuplicate = sessionId == lastSessionId &&
                latitude == lastLatitude &&
                longitude == lastLongitude
            if (!isConsecutiveDuplicate) {
                insertStatement.bindLong(1, sessionId)
                insertStatement.bindDouble(2, latitude)
                insertStatement.bindDouble(3, longitude)
                insertStatement.bindLong(4, startTime + elapsedSeconds * 1000)
                insertStatement.executeInsert()
                insertStatement.clearBindings()
            }
            lastSessionId = sessionId
            lastLatitude = latitude
            lastLongitude = longitude
        }
    }
}

/**
 * Drops `timeInTargetZoneSeconds`, adds `targetZone`, and recomputes every run's zone times under
 * the Max-HR-derived zone model (#93).
 *
 * Zone times were frozen at save under the old hybrid model, so **every** run in the database
 * carries wrong numbers — most visibly Zone 3 time mis-filed as Zone 4, which the old model's
 * invertible Zone 3 window produced whenever `zone2High >= 0.8 × maxHr`. The recompute is silent
 * by design (#97): it runs against a Max HR already in effect, so there is nothing to decide.
 *
 * This is a one-shot correction, not a freeze. #112 recomputes again on the first *deliberate*
 * Max HR set — the point at which the number stops being a placeholder — and every change after
 * that is future-only.
 *
 * [hrProfileProvider] is called here rather than closed over at construction so the DataStore read
 * happens on Room's own thread, and only on the launch that actually migrates.
 */
fun migration12To13(hrProfileProvider: () -> HrProfile) = object : Migration(12, 13) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // SQLite gained ALTER TABLE ... DROP COLUMN in 3.35, but minSdk 26 devices ship far older,
        // so dropping a column means rebuilding the table. Safe despite hr_samples, track_points
        // and run_walk_interval_stats all carrying FKs to sessions: Room runs migrations before it
        // turns foreign_keys on, and with the pragma off SQLite leaves other tables' REFERENCES
        // clauses untouched across the DROP/RENAME. Same shape Room's own auto-migrations emit.
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sessions_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `startTime` INTEGER NOT NULL,
                `endTime` INTEGER NOT NULL,
                `durationSeconds` INTEGER NOT NULL,
                `avgBpm` INTEGER NOT NULL,
                `maxBpm` INTEGER NOT NULL,
                `targetZone` INTEGER NOT NULL,
                `zone1Seconds` INTEGER NOT NULL,
                `zone2Seconds` INTEGER NOT NULL,
                `zone3Seconds` INTEGER NOT NULL,
                `zone4Seconds` INTEGER NOT NULL,
                `zone5Seconds` INTEGER NOT NULL,
                `runMode` TEXT NOT NULL,
                `distanceKm` REAL NOT NULL,
                `avgPaceMinPerKm` REAL NOT NULL,
                `noDataSeconds` INTEGER NOT NULL,
                `walkBreaksCount` INTEGER NOT NULL,
                `isRunWalkMode` INTEGER NOT NULL,
                `sessionType` TEXT NOT NULL,
                `includeInAiTraining` INTEGER NOT NULL,
                `easyPlannedDurationSeconds` INTEGER,
                `easyActualDurationSeconds` INTEGER,
                `easyTotalJogSeconds` INTEGER,
                `easyTotalWalkSeconds` INTEGER,
                `easyJogPercent` INTEGER,
                `easyLongestJogBoutSeconds` INTEGER,
                `easyWalkInterruptions` INTEGER,
                `easyHrSummary` TEXT,
                `easyTimeAboveCapSeconds` INTEGER,
                `easyDataQualitySummary` TEXT,
                `perceivedEffort` INTEGER,
                `sessionNote` TEXT,
                `startLatitude` REAL,
                `startLongitude` REAL,
                `weatherTempC` REAL,
                `weatherFeelsLikeC` REAL,
                `weatherHumidityPercent` INTEGER,
                `weatherWindSpeedKmh` REAL,
                `weatherConditionCode` INTEGER
            )
            """.trimIndent()
        )
        // Every past run is declared targetZone = 2: their target was a BPM band, not a zone, so
        // the real value is unreconstructible — but the old default band (120-140) was aiming at
        // easy/Z2 anyway (#97). A literal, not HrZone.DEFAULT_TARGET: this is a statement about
        // history, which must not move if the default ever does.
        database.execSQL(
            """
            INSERT INTO `sessions_new` (
                id, startTime, endTime, durationSeconds, avgBpm, maxBpm, targetZone,
                zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds,
                runMode, distanceKm, avgPaceMinPerKm, noDataSeconds, walkBreaksCount,
                isRunWalkMode, sessionType, includeInAiTraining,
                easyPlannedDurationSeconds, easyActualDurationSeconds, easyTotalJogSeconds,
                easyTotalWalkSeconds, easyJogPercent, easyLongestJogBoutSeconds,
                easyWalkInterruptions, easyHrSummary, easyTimeAboveCapSeconds,
                easyDataQualitySummary, perceivedEffort, sessionNote, startLatitude,
                startLongitude, weatherTempC, weatherFeelsLikeC, weatherHumidityPercent,
                weatherWindSpeedKmh, weatherConditionCode
            )
            SELECT
                id, startTime, endTime, durationSeconds, avgBpm, maxBpm, 2,
                zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds,
                runMode, distanceKm, avgPaceMinPerKm, noDataSeconds, walkBreaksCount,
                isRunWalkMode, sessionType, includeInAiTraining,
                easyPlannedDurationSeconds, easyActualDurationSeconds, easyTotalJogSeconds,
                easyTotalWalkSeconds, easyJogPercent, easyLongestJogBoutSeconds,
                easyWalkInterruptions, easyHrSummary, easyTimeAboveCapSeconds,
                easyDataQualitySummary, perceivedEffort, sessionNote, startLatitude,
                startLongitude, weatherTempC, weatherFeelsLikeC, weatherHumidityPercent,
                weatherWindSpeedKmh, weatherConditionCode
            FROM `sessions`
            """.trimIndent()
        )
        database.execSQL("DROP TABLE `sessions`")
        database.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")

        recomputeZoneSecondsFromHrSamples(database, hrProfileProvider())
    }
}

/**
 * Re-tallies each session's zone seconds from its stored `hr_samples` rows.
 *
 * The tally is exact rather than an estimate: the recorder writes exactly one sample per second
 * of the run, and only when BPM > 0 — the same condition under which it banked a second of zone
 * time. So counting samples per zone reproduces what the run would have recorded had the new
 * model been in force. Seconds with no HR signal have no row and gain no zone time, leaving the
 * existing `noDataSeconds` figure meaningful and unfabricated.
 *
 * Walks a Cursor and derives in Kotlin rather than binning in SQL, following
 * [backfillTrackPointsFromHrSamples]: this keeps [hrZoneOf] the app's one classifier, and the
 * arithmetic off SQLite versions that predate window functions.
 */
private fun recomputeZoneSecondsFromHrSamples(database: SupportSQLiteDatabase, profile: HrProfile) {
    // Zero first so the tally below is a replacement rather than a correction: a run whose samples
    // are all gone must end up with no zone time, not with its old numbers left standing.
    database.execSQL(
        "UPDATE sessions SET zone1Seconds = 0, zone2Seconds = 0, zone3Seconds = 0, zone4Seconds = 0, zone5Seconds = 0"
    )
    val cursor = database.query(
        """
        SELECT sessionId, rawBpm
        FROM hr_samples
        ORDER BY sessionId ASC
        """.trimIndent()
    )
    val updateStatement = database.compileStatement(
        """
        UPDATE sessions
        SET zone1Seconds = ?, zone2Seconds = ?, zone3Seconds = ?, zone4Seconds = ?, zone5Seconds = ?
        WHERE id = ?
        """.trimIndent()
    )

    fun flush(sessionId: Long, zoneSeconds: LongArray) {
        zoneSeconds.forEachIndexed { index, seconds ->
            updateStatement.bindLong(index + 1, seconds)
        }
        updateStatement.bindLong(6, sessionId)
        updateStatement.executeUpdateDelete()
        updateStatement.clearBindings()
    }

    cursor.use { c ->
        var currentSessionId: Long? = null
        val zoneSeconds = LongArray(5)
        while (c.moveToNext()) {
            val sessionId = c.getLong(0)
            if (sessionId != currentSessionId) {
                currentSessionId?.let { flush(it, zoneSeconds) }
                currentSessionId = sessionId
                zoneSeconds.fill(0L)
            }
            val zone = hrZoneOf(c.getInt(1), profile)
            if (zone != null) {
                val index = zone.number - 1
                zoneSeconds[index] = zoneSeconds[index] + 1L
            }
        }
        currentSessionId?.let { flush(it, zoneSeconds) }
    }
}

/**
 * Rebuilds [RunnerSession.isRunWalkMode] from hard interval evidence for runs recorded before it
 * became the run's own run/walk flag (#107).
 *
 * Before #107 `isRunWalkMode` was written straight from the separate `runWalkCoachEnabled` setting
 * (`finalIsRunWalkMode = currentSettings.runWalkCoachEnabled`), independent of what the run actually
 * was. The new code treats the flag as the structured-workout truth for AI context and metrics, so
 * on upgraded databases it is wrong in both directions:
 *  - False positive: a run recorded with the coach toggle on but which never ran intervals has
 *    `isRunWalkMode = 1`, which read as a structured run it never was. At the time this also gated
 *    `evaluateAndAdjustPlan`, so such a row as the latest finalized session would send a non-plan run
 *    to Gemini and adjust or graduate the plan; that gate is a Run's Run Type now (#176), and the flag
 *    is only the record's own label and its metrics.
 *  - False negative: a real run/walk run recorded with the toggle off has `isRunWalkMode = 0`.
 *
 * The one durable, trustworthy signal is `run_walk_interval_stats`: those rows are written only when
 * a run actually executed run/walk intervals. So the flag is set from their presence and cleared
 * everywhere else. `sessionType` is deliberately NOT used — its column default backfilled every
 * pre-column row to "Run/Walk", so keying off that label would promote genuine open runs. A run/walk
 * run with no interval rows has no interval evidence to preserve anyway (`buildRunWalkMetrics`
 * returns null), so clearing it is safe. This runs once at the 13→14 upgrade when every row predates
 * #107, and new runs set the flag correctly at insert. Dropping the dead `sessionType`/`easy*`
 * columns lands in [MIGRATION_14_15]; the fuller analytics reconciliation stays with the analytics
 * cluster.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            UPDATE sessions
            SET isRunWalkMode = CASE
                WHEN id IN (SELECT DISTINCT sessionId FROM run_walk_interval_stats) THEN 1
                ELSE 0
            END
            """.trimIndent()
        )
    }
}

/**
 * Drops `sessionType` and the ten `easy*` columns — the last physical remains of the four session
 * types (#107), carried here by #113.
 *
 * Both were dead before this: `sessionType` was replaced as the run/walk truth by `isRunWalkMode`
 * in [MIGRATION_13_14], which also records *why* the label could never be trusted, and the `easy*`
 * columns belonged to Easy Fixed Duration, a mode that no longer exists. Nothing reads either.
 * Leaving them would keep a column that says "Run/Walk" beside runs the app now knows were open
 * runs — a stored contradiction waiting to be believed by whatever reads the table next.
 *
 * A table rebuild for the same reason [migration12To13] needed one: minSdk 26 devices ship SQLite
 * older than 3.35, so `ALTER TABLE ... DROP COLUMN` is not available. Same shape, same safety
 * argument about the three tables holding foreign keys into `sessions` — Room runs migrations with
 * `foreign_keys` off, so their REFERENCES clauses survive the DROP/RENAME untouched. Every
 * surviving column is copied value-for-value: this migration only removes, it computes nothing.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sessions_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `startTime` INTEGER NOT NULL,
                `endTime` INTEGER NOT NULL,
                `durationSeconds` INTEGER NOT NULL,
                `avgBpm` INTEGER NOT NULL,
                `maxBpm` INTEGER NOT NULL,
                `targetZone` INTEGER NOT NULL,
                `zone1Seconds` INTEGER NOT NULL,
                `zone2Seconds` INTEGER NOT NULL,
                `zone3Seconds` INTEGER NOT NULL,
                `zone4Seconds` INTEGER NOT NULL,
                `zone5Seconds` INTEGER NOT NULL,
                `runMode` TEXT NOT NULL,
                `distanceKm` REAL NOT NULL,
                `avgPaceMinPerKm` REAL NOT NULL,
                `noDataSeconds` INTEGER NOT NULL,
                `walkBreaksCount` INTEGER NOT NULL,
                `isRunWalkMode` INTEGER NOT NULL,
                `includeInAiTraining` INTEGER NOT NULL,
                `perceivedEffort` INTEGER,
                `sessionNote` TEXT,
                `startLatitude` REAL,
                `startLongitude` REAL,
                `weatherTempC` REAL,
                `weatherFeelsLikeC` REAL,
                `weatherHumidityPercent` INTEGER,
                `weatherWindSpeedKmh` REAL,
                `weatherConditionCode` INTEGER
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            INSERT INTO `sessions_new` (
                id, startTime, endTime, durationSeconds, avgBpm, maxBpm, targetZone,
                zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds,
                runMode, distanceKm, avgPaceMinPerKm, noDataSeconds, walkBreaksCount,
                isRunWalkMode, includeInAiTraining, perceivedEffort, sessionNote,
                startLatitude, startLongitude, weatherTempC, weatherFeelsLikeC,
                weatherHumidityPercent, weatherWindSpeedKmh, weatherConditionCode
            )
            SELECT
                id, startTime, endTime, durationSeconds, avgBpm, maxBpm, targetZone,
                zone1Seconds, zone2Seconds, zone3Seconds, zone4Seconds, zone5Seconds,
                runMode, distanceKm, avgPaceMinPerKm, noDataSeconds, walkBreaksCount,
                isRunWalkMode, includeInAiTraining, perceivedEffort, sessionNote,
                startLatitude, startLongitude, weatherTempC, weatherFeelsLikeC,
                weatherHumidityPercent, weatherWindSpeedKmh, weatherConditionCode
            FROM `sessions`
            """.trimIndent()
        )
        database.execSQL("DROP TABLE `sessions`")
        database.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
    }
}

/**
 * Records when each heart-rate sample was actually taken (#84).
 *
 * `elapsedSeconds` counts the Run's *running* seconds, so it stands still through a pause and can no
 * longer say what time a reading belongs to. Track points are stamped with the wall clock, so
 * without this column every point after the first pause — including every auto-pause at a crossing —
 * lines up against the wrong reading, or none at all.
 *
 * Nullable and not backfilled: for a run that was never paused, `startTime + elapsedSeconds` is the
 * same answer, and readers derive it that way when the column is null. Inventing a stored timestamp
 * for older paused runs would only record a guess as a fact.
 */
val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE hr_samples ADD COLUMN timestampMillis INTEGER")
    }
}

/**
 * Records where a pause fell, on the fix that resumed the run (#84).
 *
 * Not nullable and not backfilled: false is the honest answer for every existing row. Where a pause
 * fell on an older run was never written down and cannot be recovered — those runs keep
 * the old behaviour, where only a gap longer than twenty seconds breaks the route.
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE track_points ADD COLUMN startsAfterPause INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Settles a version number that briefly meant two different things.
 *
 * A v17 was installed on the phone from an unmerged branch — the #163 pace work, which spends its
 * own v17 on `sessions.movingTimeSeconds` — before this branch spent v17 on `startsAfterPause`
 * above. Two databases now claim to be 17 and are not the same shape, and a version number is the
 * only thing Room has to tell them apart. Room also refuses to open a database carrying a column its
 * entities do not declare, so the phone cannot reach this branch by any ordinary path.
 *
 * Eighteen is the first number that means one thing again, and both shapes are brought to it by
 * asking the database what it actually has rather than trusting the number: the column #84 needs is
 * added if it is missing. A database that reached 17 the ordinary way — through [MIGRATION_16_17] —
 * finds nothing to do here.
 *
 * The other branch's column is left where it is. [MIGRATION_18_19] wants it anyway, and Room checks
 * the shape of the database only once every migration has run, so a column that is briefly early is
 * a column that is on time. Dropping and re-adding it would cost the moving times already stored on
 * that phone, and `ALTER TABLE ... DROP COLUMN` needs a SQLite newer than this app's minimum Android
 * carries.
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(database: SupportSQLiteDatabase) {
        if (!database.hasColumn("track_points", "startsAfterPause")) {
            database.execSQL("ALTER TABLE track_points ADD COLUMN startsAfterPause INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/** What the database says it has, rather than what its version number implies. */
private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == column) return true
        }
        false
    }

/**
 * Moving time (#163). Left null rather than computed here: working it out means measuring geodesic
 * distances between every pair of track points, which is Kotlin's job, not SQL's. A one-time pass
 * fills these in after the database opens — see [SessionRepository.backfillMovingTime].
 *
 * Added only if it is missing, because one phone already carries this column from the unmerged v17
 * described on [MIGRATION_17_18]; there it arrives with its moving times already measured, and the
 * backfill pass leaves a row that has an answer alone.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(database: SupportSQLiteDatabase) {
        if (!database.hasColumn("sessions", "movingTimeSeconds")) {
            database.execSQL("ALTER TABLE sessions ADD COLUMN movingTimeSeconds INTEGER")
        }
    }
}

/**
 * The record book (#49): the top three efforts at each record, one row per medal.
 *
 * Arrives empty, and fills as Runs finish. The history already recorded is not scored here — that
 * means measuring every stored track, which is Kotlin's job and a job of its own (#50) — so until
 * then the book only knows about Runs finished since the app was updated.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `achievements` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `medal` TEXT NOT NULL,
                `value` REAL NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_achievements_sessionId` ON `achievements` (`sessionId`)"
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_achievements_type` ON `achievements` (`type`)"
        )
    }
}

/**
 * Effort (#61): what each Run cost, banked per Run.
 *
 * Every existing Run keeps a null, which is exactly the truth about them — nobody has scored them
 * yet — and is the state the history backfill (#62) looks for. Scoring them here is not an option
 * anyway: the score is weighted per second of `hr_samples` against the runner's zones, which is
 * Kotlin's arithmetic and not SQL's, the same division of labour [MIGRATION_18_19] makes.
 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(database: SupportSQLiteDatabase) {
        if (!database.hasColumn("sessions", "effortScore")) {
            database.execSQL("ALTER TABLE sessions ADD COLUMN effortScore INTEGER")
        }
    }
}

/**
 * The per-Run record of having been measured against the record book (#210).
 *
 * Every existing Run arrives unscored, and that is the repair rather than a side effect: a Run
 * whose scoring was missed before this shipped — the process killed on the way to the book, or the
 * write logged and lost — is invisible to everything that came before, because the rescue pass only
 * looks at Runs with no end time and the seeding pass declines once history is marked seeded. Left
 * unscored here, the first launch after the upgrade measures every Run once and the missing medals
 * appear. On a long history that is minutes of background arithmetic, once.
 *
 * Scoring them here is not an option: measuring a Run's best efforts means walking its stored track
 * point by point, which is Kotlin's arithmetic and not SQL's — the same division of labour
 * [MIGRATION_18_19] and [MIGRATION_20_21] make.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(database: SupportSQLiteDatabase) {
        if (!database.hasColumn("sessions", "recordsScored")) {
            database.execSQL("ALTER TABLE sessions ADD COLUMN recordsScored INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/**
 * Every measured Run put back into the queue to be measured again, because the rule changed under
 * them (#165, [ADR 0012](docs/adr/0012-an-outage-is-a-leg-like-any-other.md)):
 * an Outage now carries its seconds the way it already carried its ground.
 *
 * No column is added or dropped. Nulling `movingTimeSeconds` is the whole of it, because null is
 * what [SessionRepository.backfillMovingTime] looks for, and re-measuring is the one thing that
 * makes a stored Run agree with its own Splits table — which is measured on read and so already
 * follows the new rule. Left alone, a Run holding an Outage would print one pace at the top of its
 * page and a faster set of them underneath.
 *
 * Outdoor and finished, matching the backfill's own query exactly: a treadmill Run has no track to
 * measure and a Run still being written measures itself when it ends. Costs one re-measure of the
 * whole history at the next launch, in the background, once.
 *
 * Until that pass reaches a Run, its pace is quoted against its own duration
 * ([RunnerSession.paceClockSeconds]) — the same "not measured yet" state history sat in before
 * the #163 backfill, and honest in a way that leaving the old answer in place would not be.
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "UPDATE sessions SET movingTimeSeconds = NULL WHERE endTime > 0 AND runMode = 'outdoor'"
        )
    }
}
