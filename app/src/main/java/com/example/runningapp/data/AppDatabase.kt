package com.example.runningapp.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.example.runningapp.HrZone
import com.example.runningapp.HrProfile
import com.example.runningapp.analysis.Medal
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.hrZoneOf
import com.example.runningapp.run.RunMode
import com.example.runningapp.training.HistoryBestEffort
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
    val recordsScored: Boolean = false,
    /**
     * The Max HR this Run's zone seconds are banded against, and beside it [bandedOnRestingHr] —
     * the Reserve the numbers on this row mean something against (#228). Written with the row at
     * START from [com.example.runningapp.run.RunConfig.hrProfile], the Reserve the Run is recorded
     * under, so anything re-reading its beats afterwards asks the Run rather than guessing at one
     * from settings that have since moved on.
     *
     * Named for what it is rather than for where it came from, because one thing does move it: a
     * re-tally re-bands every finished Run onto another Reserve and stamps this as it goes
     * ([SessionRepository.recomputeZoneSecondsAndEffortForAllRuns]). A row left naming the Reserve
     * it was *recorded* under would then be describing seconds it no longer holds.
     *
     * The two numbers are one fact and must be read as a pair — see [bandedOnHrProfile], which is
     * the only way anything should ask.
     *
     * Null means "whatever history is banded against" ([com.example.runningapp.historyHrProfile]):
     * a Run recorded before v24 that no re-tally has reached since. There is no backfill, because
     * the pair those rows would be filled with is exactly what that fallback reads.
     */
    val bandedOnMaxHr: Int? = null,
    /** The resting heart rate half of the Reserve this Run is banded on — see [bandedOnMaxHr]. */
    val bandedOnRestingHr: Int? = null,
    /**
     * The Stage this Run was recorded under (#234), written with the row at START from
     * [com.example.runningapp.run.RunConfig.ranUnderStageId] — the Stage in force when the runner
     * pressed it.
     *
     * It is here because a Stage is graduated on evidence, and evidence has to belong to the Stage
     * it is offered to. Without it the coach was shown the last three Runs full stop, so the moment
     * a Stage was graduated the next evaluation still read the Runs before it — most of them the
     * work of the Stage just left. Harmless for a Stage asking for consistency; not harmless for
     * one asking for a time, where one Stage's Run could graduate the next one too, and a
     * graduation cannot be taken back.
     *
     * Unlike [bandedOnMaxHr] nothing ever moves this: it is where a Run happened, and no later
     * re-reading of the Run changes that.
     *
     * Null is "no Stage recorded", not "no Stage": a Run recorded before v25, or one run with no
     * plan attached. Such a Run can never answer a Stage's requirement — see
     * [SessionDao.getLast3AiEligibleRunsOfStage] — which errs towards graduating late rather than
     * twice. There is no backfill, because the only Stage a backfill could write is whichever one
     * the runner is on now, which is exactly the guess this exists to stop.
     */
    val ranUnderStageId: String? = null,
    /**
     * The Stage's Workout this Run followed (#292), written with the row at START from
     * [com.example.runningapp.run.RunConfig.workout] — the Workout the card was showing when the
     * runner pressed it, and never the shape the coach prescribed into it (a Prescription changes a
     * Workout's numbers and never its identity, #113).
     *
     * It is here for one question: when the runner last ran their Stage's Test. That date is
     * derived and not claimed (ADR 0001) — a stored "last tested" field would be a second copy of a
     * fact history already holds, and it would drift the first time a Run was deleted — so history
     * has to be able to say which Runs were the Test, and nothing in a stored Run said so. Its
     * shape does not: a Test is a Workout the plan *named* as one ([com.example.runningapp.WorkoutTemplate.isTest]).
     *
     * Like [ranUnderStageId] nothing ever moves it, and null is "no Workout recorded": a Run
     * recorded before v30, one that skipped today's plan, and one run with no plan attached are all
     * the same null. There is no backfill — the only Workout a backfill could write is a guess, and
     * a wrongly-guessed Test would silence the prompt for three weeks.
     */
    val ranUnderWorkoutId: String? = null,
    /**
     * Whether the runner walked this one (#275) — a Run marked as a Walk, and nothing more
     * elaborate than that. There is no activity taxonomy here and no per-second classification: one
     * whole-Run flag, defaulting to a Run.
     *
     * **Never inferred, and never written by the app on its own.** A treadmill Run has no GPS and no
     * measured pace, so there is nothing in a stored Run that distinguishes a walk from a run;
     * guessing would rewrite curves nobody asked to change. It is set by the runner on the "How did
     * that feel?" sheet at the finish and changed on the Run's own page for ever afterwards, which
     * is also why every Run recorded before v29 — and every Run in an older archive — comes back a
     * Run. There is no backfill and there will not be one.
     *
     * **It does not touch the Effort Score.** The Score measures what the heart did, and a Zone-2
     * walk really did cost the heart what a Zone-2 easy run costs it. What it changes is what the
     * curves read: the whole Score builds Fitness, and only
     * [com.example.runningapp.training.WALK_FATIGUE_SHARE] of it is carried into Fatigue, because the
     * fatigue that degrades a runner's form is largely mechanical and walking barely pays it.
     *
     * **A Walk contests no record** ([com.example.runningapp.analysis.bestEffortsOf]), completes no
     * prescribed workout and graduates no Stage. It still counts towards Goals, still fills the
     * weekly volume bars, and still appears in history and in the coach's prompt, where it is named
     * as a Walk.
     *
     * Marking one is the one edit to a finished Run that can take a medal off it, so it goes through
     * the record book's mend ([SessionRepository.markAsWalk]).
     */
    val isWalk: Boolean = false,
    /**
     * Whether this Run's Stage has been settled — the app's graduation rule asked of it, and the
     * coach asked after (#297). See [SessionRepository.settleStageForRun].
     *
     * False is a debt, exactly as [recordsScored]'s false is: it says the question has not been put
     * yet, never that the answer was no. What makes it a debt rather than a step in the finish is
     * [isWalk]: the mark is the runner's own word, given on the finish sheet *after* STOP, and a
     * Walk graduates nothing — so a Stage settled at STOP is settled before the one fact that can
     * withdraw the Run from the judgement. The settlement therefore waits for the sheet to resolve,
     * and this column is what carries the wait across a process that dies in between, for the
     * launch pass ([SessionRepository.settleStagesMissedAtTheFinish]) to pay.
     *
     * Written only once the settlement has *returned*, never beside the row being stamped finished,
     * for [recordsScored]'s reason: an ending in between leaves the debt standing rather than
     * losing the Run its graduation for good.
     *
     * **Every Run recorded before v31 arrives already settled**, which is the migration doing
     * nothing rather than a fact about those Runs. The rule is forwards-only (ADR 0016): a pass that
     * jumped the runner two Stages on old evidence is the highest-stakes version of the one act the
     * app can never undo, and a column defaulting to a debt would have made the first launch after
     * the upgrade exactly that pass.
     */
    val stageSettled: Boolean = false,
    /**
     * How far east of UTC the runner was when they pressed START, in seconds (#304) — written with
     * the row at START from the phone's own zone, and the whole of what says which calendar day
     * this Run happened on.
     *
     * It is here because a day is not a property of a moment. Every reader used to take [startTime]
     * and re-read it in whatever zone the phone happened to be in at the time of asking, so a Run
     * at half past eleven at night moved to a different calendar day the moment the runner flew:
     * the weekly bars re-totalled, the curve's day moved, the GPX came out named for the wrong
     * evening. Most of that self-corrects on the way home, which is what made it easy to live with —
     * but a Plan Completion records its day once and can never re-earn it
     * ([com.example.runningapp.training.PlanCompletion]), so for that one fact the wrong day is
     * permanent.
     *
     * Seconds rather than hours because zones offset by three quarters of an hour exist. An offset
     * rather than a zone id because the offset is the fact: a zone id would have to be resolved back
     * through that year's daylight-saving rules to say anything, and governments rewrite those after
     * the fact. It follows that this says where the runner's clock was and not where the runner was
     * — two countries on the same offset are the same thing here, which is all a day boundary asks.
     *
     * Like [ranUnderStageId] nothing ever moves it: it is where a Run happened, and no later reading
     * of the Run changes that.
     *
     * Null is "this Run never wrote one down": every Run recorded before v32, and every Run in an
     * older archive. Those are read the way they always were, in the zone the phone is in now — see
     * [com.example.runningapp.ranOn], which is the only way anything should ask. There is no
     * backfill, because the only offset a backfill could write is the one the phone is on today,
     * which is exactly the guess this exists to stop.
     */
    val ranAtUtcOffsetSeconds: Int? = null
)

/**
 * The Reserve this Run's zone seconds are banded against, or null for a Run that has none of its
 * own (#228) — for which the caller's fallback is what history is banded against
 * ([com.example.runningapp.historyHrProfile]).
 *
 * The one door for the question, because the two stored numbers are one fact: a row half-filled
 * would otherwise be read as a Max HR against somebody else's resting heart rate, which is a
 * Reserve no Run was ever recorded under. Only a pair that is wholly there is the Run's own.
 */
fun RunnerSession.bandedOnHrProfile(): HrProfile? {
    val maxHr = bandedOnMaxHr ?: return null
    val restingHr = bandedOnRestingHr ?: return null
    return HrProfile(maxHr, restingHr)
}

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

/**
 * One Pause of one Run: when the Run's clock stopped, and when it started again (#328).
 *
 * A Pause is the one thing about a Run that the recording could not hold. GPS is torn down for the
 * length of one, so the only mark it left was a bit on the fix that resumed the Run
 * ([TrackPoint.startsAfterPause]) — and a Run with no GPS has no such fix. A treadmill Run's Pauses
 * were written down nowhere, so an Export could state how long they had been in total and never
 * where any of them fell.
 *
 * Both instants are wall clock, and they are the Run's own boundaries rather than the fixes nearest
 * to them: they are taken where the rulebook stopped and restarted the clock. The row is written as
 * the Pause ends, because that is the moment its far side is known.
 *
 * This is a second record of something the track also carries for an outdoor Run, which
 * [ADR 0018](docs/adr/0018-a-pause-is-written-down.md) is where the cost of it is argued. Nothing that
 * reads the *shape* of a Run reads these rows: a Break is still read off the track (ADR 0010), and
 * these say only where the Run's clock stopped.
 */
@Entity(
    tableName = "run_pauses",
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
data class RunPause(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** When the Run's clock stopped. */
    val startTimeMillis: Long,
    /** When it started again — a resume, or the Run's own finish for a Run stopped while paused. */
    val endTimeMillis: Long,
)

data class MaxSessionLoad30dProjection(
    val maxDistanceKm: Double?,
    val maxDurationSeconds: Long?
)

/**
 * One Run of a Test, reduced to what deciding "was that a test?" needs (#292): when it began, how
 * long it lasted, and which Test it followed.
 *
 * The duration and distance come back with the row rather than being filtered in SQL, because both
 * are judged against the Test's own numbers and those live in the plan — see
 * [SessionDao.getCompletedRunsOfWorkouts].
 */
data class TestRunProjection(
    val startTime: Long,
    val durationSeconds: Int,
    val distanceKm: Double,
    val ranUnderWorkoutId: String,
    /** The Run's own stamp — see [RunnerSession.ranAtUtcOffsetSeconds] and [com.example.runningapp.ranOn]. */
    val ranAtUtcOffsetSeconds: Int? = null,
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
    val effortScore: Int,
    /**
     * Which of the two curves reads how much of the Score — see [RunnerSession.isWalk] (#275).
     *
     * Defaulted to a Run, which is what the column defaults to and what every Run in history is
     * until the runner says otherwise; Room fills it from the query either way.
     */
    val isWalk: Boolean = false,
    /** The Run's own stamp — see [RunnerSession.ranAtUtcOffsetSeconds] and [com.example.runningapp.ranOn]. */
    val ranAtUtcOffsetSeconds: Int? = null,
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
    val effortScore: Int?,
    /** The Run's own stamp — see [RunnerSession.ranAtUtcOffsetSeconds] and [com.example.runningapp.ranOn]. */
    val ranAtUtcOffsetSeconds: Int? = null,
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
     * The best Run in history at one record, and when it was run — what the Stage card names when
     * the runner has already beaten its bar (#293). Null where nothing has ever placed there.
     *
     * Asked of the record book rather than of the Runs, which is the whole of why it is safe to ask
     * over all of history: the book holds efforts as [com.example.runningapp.analysis.bestEffortsOf]
     * measured or was told them, so a Walk is already absent, a Run still going is already absent,
     * and a treadmill claim is already in. Re-deriving any of that here would be a second reader of
     * a shared measurement, keeping its own rules until the day they drift.
     *
     * The quickest and not the gold, though on an intact book they are the same row: the caller
     * only ever asks this of a record run over a set distance
     * ([com.example.runningapp.BestEffortRequirement], which refuses any other), where the smaller
     * number is the better one. Ordering says so outright rather than leaning on the book holding
     * exactly one gold per record, which is an invariant of how the book is written rather than
     * anything the schema enforces.
     */
    @Query(
        """
        SELECT s.startTime AS runStartedAtMillis, a.value AS seconds,
               s.ranAtUtcOffsetSeconds AS ranAtUtcOffsetSeconds
        FROM achievements a
        JOIN sessions s ON s.id = a.sessionId
        WHERE a.type = :type
        ORDER BY a.value ASC
        LIMIT 1
        """
    )
    fun getQuickestInHistoryFlow(type: RecordType): Flow<HistoryBestEffort?>

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
     * Writes down that a Run owes the record book a scoring again (#282).
     *
     * A Run is marked scored once and never revisited, which is what makes the mark worth having —
     * and what makes it a trap for anything that changes what the Run is worth *after* it was set.
     * A statement told to a Run already carrying the mark is exactly that: the claim is stored, and
     * a scoring that is then killed or throws leaves a medal nobody will ever go back for. Lifted
     * before the change and handed back only once the book has taken it, so every way the work can
     * end short leaves the debt standing for the next launch to pay.
     */
    @Query("UPDATE sessions SET recordsScored = 0 WHERE id = :sessionId")
    suspend fun clearRecordsScored(sessionId: Long)

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
     * Finished Runs whose Stage nobody has settled yet (#297) — see [RunnerSession.stageSettled].
     *
     * `endTime > 0` as everywhere else here: a Run still being recorded has no Best Effort to judge
     * and settles itself when it finishes. Oldest first, so a launch paying more than one debt puts
     * the Runs to the rule in the order they were run — which is the order the Stages moved in.
     */
    @Query("SELECT id FROM sessions WHERE stageSettled = 0 AND endTime > 0 ORDER BY startTime ASC")
    suspend fun getSessionIdsOwingStageSettlement(): List<Long>

    /** Closes a Run's Stage question — written only once the settlement has returned. */
    @Query("UPDATE sessions SET stageSettled = 1 WHERE id = :sessionId")
    suspend fun setStageSettled(sessionId: Long)

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

    /**
     * Marks a Run as a Walk, or takes the mark back (#275) — see [RunnerSession.isWalk].
     *
     * Its own statement rather than a column on [updateFeelFeedback], though both are written from
     * the same two screens: a feel and a note are words kept beside a Run, and this changes what the
     * Run is worth to the record book and to the curves. Only [SessionRepository.markAsWalk] should
     * call it, because the mend that has to follow is half of the act.
     */
    @Query("UPDATE sessions SET isWalk = :isWalk WHERE id = :sessionId")
    suspend fun setIsWalk(sessionId: Long, isWalk: Boolean)

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

    /**
     * The last three Runs the coach may judge a Stage on: finished, long enough to mean something,
     * shareable — and recorded under that Stage (#234).
     *
     * A Run carrying no Stage is not a match, so history recorded before v25 answers no
     * requirement at all — see [RunnerSession.ranUnderStageId] for why that is the safe side to be
     * wrong on. There is deliberately no unfiltered twin of this query: the last three Runs full
     * stop is the read the ticket was about.
     */
    @Query(
        """
        SELECT * FROM sessions
        WHERE endTime > 0
          AND durationSeconds > 120
          AND includeInAiTraining = 1
          AND ranUnderStageId = :stageId
        ORDER BY startTime DESC
        LIMIT 3
        """
    )
    suspend fun getLast3AiEligibleRunsOfStage(stageId: String): List<RunnerSession>

    /**
     * Which of [sessionIds] the coach was allowed to be shown, asked while the rows are still there
     * (#156).
     *
     * Only the sharing flag, and deliberately not the rest of [getLast3AiEligibleRunsOfStage]'s
     * filter. It answers "could this Run have fed the coach at all", which a delete asks about a Run
     * whose Stage may since have been graduated and whose Prescription is still standing; the
     * Prescription's own record of what it stood on is what narrows it from there.
     */
    @Query("SELECT id FROM sessions WHERE id IN (:sessionIds) AND includeInAiTraining = 1")
    suspend fun getAiEligibleIdsIn(sessionIds: List<Long>): List<Long>

    @Query("SELECT * FROM sessions WHERE endTime > 0 ORDER BY endTime DESC LIMIT 1")
    suspend fun getMostRecentFinalizedSession(): RunnerSession?

    /**
     * Every finished Run of any of [workoutIds] — the plan's Tests — newest first (#292). What the
     * date the three-week prompt counts from is derived from, off history rather than stored
     * (ADR 0001).
     *
     * Every Test of the plan and not only the Stage's own, because a Test is a 5 km run flat out
     * whichever Stage's Workout it was, and the runner who has just been graduated by one has
     * plainly tested today. Asked of the Stage's Workout alone, a graduation would silence the last
     * Test along with the Stage that offered it and the new Stage would ask for another the same
     * afternoon — which is the "test too often and the number measures noise" this exists to stop.
     *
     * The Run's *start*, because that is what places a Run on a calendar day everywhere else the
     * app counts days.
     *
     * Two conditions here and a third above the query. Finished, because a Run still going is not a
     * Test that was run. And not a Walk — a Walk completes no prescribed Workout (CONTEXT.md) and is
     * worth no Best Effort, so it cannot be the test it did not take.
     *
     * How *much* of the Test was run is not asked here, because the answer differs per Workout and
     * SQL has no way to reach the plan: the rows come back with their durations and
     * [com.example.runningapp.training.wasRunFarEnough] settles it against each Test's own length.
     * A `durationSeconds > 120` would have admitted a 30-minute Test abandoned after two minutes and
     * cost the runner three weeks of prompting for a Test nobody ran (Codex P2).
     *
     * Newest first, so the caller stops at the first row that counts rather than sorting them.
     *
     * Deliberately not filtered on the Stage: a Workout id names one Workout of one Stage, and a
     * runner back for a second go at a Stage is running the same Test they ran the first time.
     * Passing or failing is not asked either — the effort was paid either way (#292).
     */
    @Query(
        """
        SELECT startTime, durationSeconds, distanceKm, ranUnderWorkoutId,
               ranAtUtcOffsetSeconds FROM sessions
        WHERE endTime > 0
          AND isWalk = 0
          AND ranUnderWorkoutId IN (:workoutIds)
        ORDER BY startTime DESC
        """
    )
    fun getCompletedRunsOfWorkouts(workoutIds: List<String>): Flow<List<TestRunProjection>>

    /**
     * The biggest single session of the last 30 days, which the coach's prescription is held under.
     *
     * Walks are left out (#275). This is a ceiling on what the runner is asked to *run*, so it has
     * to be built from running: a two-hour walk would otherwise licence a two-hour workout for a
     * runner whose longest run is twenty minutes, and the guard would be lifted by the very
     * sessions it exists to protect a tired runner from.
     */
    @Query(
        """
        SELECT
            MAX(CASE WHEN distanceKm > 0 THEN distanceKm END) AS maxDistanceKm,
            MAX(durationSeconds) AS maxDurationSeconds
        FROM sessions
        WHERE endTime > 0
          AND isWalk = 0
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
     *
     * The Reserve travels in the same statement for the same reason the Score does (#228): after
     * this the Run really is banded on it, and a row whose stored pair still named the Reserve it
     * *used* to be on would send its route map and its zone bars to two different answers. This is
     * the one writer that moves a finished Run from one Reserve to another, so it is the one place
     * the stamp has to follow.
     *
     * Stamped unconditionally, unlike the Score: a Run with no beats to re-band still has five zone
     * seconds — all of them zero — and they are as true of the new Reserve as of the old.
     */
    @Query(
        """
        UPDATE sessions
        SET zone1Seconds = :zone1,
            zone2Seconds = :zone2,
            zone3Seconds = :zone3,
            zone4Seconds = :zone4,
            zone5Seconds = :zone5,
            effortScore = CASE WHEN effortScore IS NULL THEN NULL ELSE :effortScore END,
            bandedOnMaxHr = :bandedOnMaxHr,
            bandedOnRestingHr = :bandedOnRestingHr
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
        effortScore: Int?,
        bandedOnMaxHr: Int,
        bandedOnRestingHr: Int
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
        SELECT startTime, effortScore, isWalk, ranAtUtcOffsetSeconds FROM sessions
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
        SELECT startTime, distanceKm, durationSeconds, movingTimeSeconds, effortScore,
               ranAtUtcOffsetSeconds FROM sessions
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

    /**
     * The highest heart rate held for [heldForSeconds] recorded seconds across the whole of history,
     * or null where the phone has not recorded that many beats at all (#65).
     *
     * Read off `rawBpm` and never `smoothedBpm`, which is the column ADR 0011 wrote off: Runs
     * recorded before #161 carry a frozen or zero smoothed reading, and their raw feed is the part
     * that survived. Asking the wrong column would not merely be untidy — it understates the very
     * runner it is asking about, and it does so most for whoever has the longest history.
     *
     * The guard against a strap artefact is therefore the offset alone, and it has to be, because
     * a smoothed value is a five-second mean and would have carried one bad reading across five
     * rows unnoticed. Stepping past the first `heldForSeconds - 1` raw readings means the value
     * returned is one the strap reported that many times over, so a single wild sample — or two —
     * cannot be offered as anybody's maximum.
     *
     * Samples are banked once a second, so the offset counts seconds. Ordered by value rather than
     * grouped by Run, which allows those seconds to come from different Runs — a runner who touched
     * 181 in three separate Runs has been to 181 three times, which is if anything better evidence
     * than three consecutive seconds of it.
     */
    @Query("SELECT rawBpm FROM hr_samples ORDER BY rawBpm DESC LIMIT 1 OFFSET (:heldForSeconds - 1)")
    suspend fun getHighestSustainedBpm(heldForSeconds: Int): Int?
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

@Dao
interface RunPauseDao {
    @Insert
    suspend fun insertPause(pause: RunPause): Long

    /** Every Pause of one Run, in the order the Run took them (#328). */
    @Query("SELECT * FROM run_pauses WHERE sessionId = :sessionId ORDER BY startTimeMillis ASC")
    suspend fun getPausesForSession(sessionId: Long): List<RunPause>
}

@Database(
    entities = [
        RunnerSession::class,
        HrSample::class,
        RunWalkIntervalStat::class,
        TrackPoint::class,
        Achievement::class,
        Route::class,
        GoalRow::class,
        StatedBestEffort::class,
        RunPause::class
    ],
    version = 33,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao
    abstract fun runWalkIntervalStatDao(): RunWalkIntervalStatDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun achievementDao(): AchievementDao
    abstract fun routeDao(): RouteDao
    abstract fun goalDao(): GoalDao
    abstract fun statedBestEffortDao(): StatedBestEffortDao
    abstract fun runPauseDao(): RunPauseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * [prepare] is whatever has to be true of the database file before anything reads it — the
         * restores at #86 and #119. It hangs off the *opening* of the file rather than off this
         * call, which is what keeps it off the main thread; [PreparingOpenHelper] is where that is
         * argued (#121).
         *
         * [hrProfileProvider] feeds the v12 → v13 zone recompute, which needs a heart-rate
         * profile that lives in DataStore rather than in the database. It is read lazily, from
         * inside the migration, so the settings read happens on Room's own thread and only on the
         * one launch that migrates — and after [prepare], which is what lets a restored archive's
         * own profile be the one the migration bands its runs against.
         */
        fun getDatabase(
            context: android.content.Context,
            prepare: () -> Unit,
            hrProfileProvider: () -> HrProfile
        ): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "running_app_db"
                )
                .openHelperFactory(
                    PreparingOpenHelperFactory(FrameworkSQLiteOpenHelperFactory(), prepare)
                )
                .addMigrations(*appDatabaseMigrations(hrProfileProvider))
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Every migration this app knows, in one place because two callers have to agree on them.
 *
 * The second caller is the trial open a restore does before it swaps a picked backup in (#201): it
 * opens the staged copy with Room and lets these run against it, which is what turns "this file
 * looks like a database" into "this file provably becomes today's schema". A trial that ran a
 * different list from the live app would prove nothing about the launch that follows it, so there
 * is one list and both read it.
 *
 * [hrProfileProvider] is the v12 → v13 zone recompute's, and is the one thing here that differs
 * between the two callers: the live app supplies whichever profile belongs to the history being
 * opened, and the trial supplies the same one it would, so a v12 backup is banded in the trial
 * exactly as it will be in place.
 */
fun appDatabaseMigrations(hrProfileProvider: () -> HrProfile): Array<Migration> = arrayOf(
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
    MIGRATION_22_23,
    MIGRATION_23_24,
    MIGRATION_24_25,
    MIGRATION_25_26,
    MIGRATION_26_27,
    MIGRATION_27_28,
    MIGRATION_28_29,
    MIGRATION_29_30,
    MIGRATION_30_31,
    MIGRATION_31_32,
    MIGRATION_32_33
)

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

/**
 * Room for the Reserve each Run is banded on (#228): [RunnerSession.bandedOnMaxHr] and
 * [RunnerSession.bandedOnRestingHr].
 *
 * Both nullable and both left null, which is the whole of the migration. The numbers these rows
 * would be filled with live in DataStore rather than in the database, so SQL cannot reach them —
 * but it does not need to, because null already reads as "banded against whatever history is
 * banded against" ([RunnerSession.bandedOnHrProfile]), and that is exactly the pair a backfill
 * would have written. A null here stays true until something moves the row: the one thing that
 * moves a finished Run onto another Reserve is the re-tally, and it stamps every row it re-bands.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(database: SupportSQLiteDatabase) {
        if (!database.hasColumn("sessions", "bandedOnMaxHr")) {
            database.execSQL("ALTER TABLE sessions ADD COLUMN bandedOnMaxHr INTEGER")
        }
        if (!database.hasColumn("sessions", "bandedOnRestingHr")) {
            database.execSQL("ALTER TABLE sessions ADD COLUMN bandedOnRestingHr INTEGER")
        }
    }
}

/**
 * Room for the Stage each Run was recorded under (#234): [RunnerSession.ranUnderStageId].
 *
 * Nullable and left null, which is the whole of the migration. The Stage a past Run was run under
 * was never written down anywhere, so there is nothing for SQL to recover it from — and the one
 * value a backfill could reach, the Stage the runner is on today, is precisely the assumption that
 * lets one Stage's work graduate the next. Null therefore means what it says: this Run answers no
 * Stage's requirement. History stops counting towards a graduation, and the Runs recorded from here
 * carry their own Stage and count towards theirs.
 */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(database: SupportSQLiteDatabase) {
        if (!database.hasColumn("sessions", "ranUnderStageId")) {
            database.execSQL("ALTER TABLE sessions ADD COLUMN ranUnderStageId TEXT")
        }
    }
}

/**
 * Room for the Route library (#54): the `routes` table, and nothing else touched.
 *
 * A new table rather than a column, and one with no key into `sessions` either way — which is the
 * whole of why importing and deleting Routes can never cost a runner a Run. Every existing row of
 * every existing table is left exactly where it was.
 *
 * There is nothing to backfill. A Route is a course the runner keeps on purpose, and no earlier
 * version of the app recorded one; a phone upgrading to v26 has an empty library, which is the
 * truth about it. Turning past Runs into Routes is a thing the runner asks for one Run at a time
 * (#55), not something a migration should decide for them.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `routes` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`distanceMeters` REAL NOT NULL, " +
                "`elevationGainMeters` REAL, " +
                "`polyline` TEXT NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, " +
                "`source` TEXT NOT NULL)"
        )
    }
}

/**
 * Room for the runner's Goals (#82): the `goals` table, and nothing else touched.
 *
 * A new table with no key into `sessions` either way, for the same reason the Route library has
 * none: setting or clearing a goal must never cost a Run, and deleting a Run must never quietly take
 * a goal with it. Every existing row of every existing table is left where it was.
 *
 * There is nothing to backfill. A goal is something the runner states, and no earlier version of the
 * app asked; a phone upgrading to v27 has no goals, which is the truth about it. Inventing one from
 * their recent weeks would be the app setting a target on the runner's behalf.
 *
 * The unique index over (period, metric) is the rule "one goal per period and metric" written where
 * it cannot be got round — the same rule the insert relies on to make stating a goal twice an edit
 * rather than a second goal.
 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `goals` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`period` TEXT NOT NULL, " +
                "`metric` TEXT NOT NULL, " +
                "`target` REAL NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_goals_period_metric` ON `goals` " +
                "(`period`, `metric`)"
        )
    }
}

/**
 * The table a treadmill Run's stated Best Efforts live in (#282).
 *
 * Nothing to backfill: before this there was no way to state one, so every existing Run starts with
 * none — which is exactly what an empty table says. History's records are untouched by the migration
 * and stay exactly as they were measured.
 *
 * The foreign key is the rule that a statement is part of its Run, so deleting the Run takes its
 * claims with it and no orphan can go on holding a medal. The unique index over (sessionId, type) is
 * "one statement per record distance per Run" written where it cannot be got round — the same rule
 * the insert relies on to make stating a time twice a correction rather than a second claim.
 */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `stated_best_efforts` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`seconds` INTEGER NOT NULL, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_stated_best_efforts_sessionId_type` " +
                "ON `stated_best_efforts` (`sessionId`, `type`)"
        )
    }
}

/**
 * Adds the Walk mark (#275) — see [RunnerSession.isWalk].
 *
 * Every Run already in history stays a Run, which is the column's default and the whole of the
 * upgrade: nothing in a stored Run distinguishes a walk from a run, so an app that guessed would
 * rewrite Fitness, Fatigue and Form on days the runner never asked it to touch. Retagging is the
 * runner's to do, one Run at a time, and the same rule covers an older archive restored on top.
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(database: SupportSQLiteDatabase) {
        if (!database.hasColumn("sessions", "isWalk")) {
            database.execSQL("ALTER TABLE sessions ADD COLUMN isWalk INTEGER NOT NULL DEFAULT 0")
        }
    }
}

/**
 * Room for the Workout each Run followed (#292): [RunnerSession.ranUnderWorkoutId].
 *
 * Nullable and left null, for the reason [MIGRATION_24_25] leaves the Stage null: no earlier
 * version wrote a Run's Workout down, so there is nothing for SQL to recover it from, and the one
 * value a backfill could reach is a guess. Here the guess would be the expensive kind — a past Run
 * wrongly called a Test would silence the three-week prompt for three weeks — so history simply
 * holds no Test, and the first Test the runner runs from here starts the clock.
 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(database: SupportSQLiteDatabase) {
        if (!database.hasColumn("sessions", "ranUnderWorkoutId")) {
            database.execSQL("ALTER TABLE sessions ADD COLUMN ranUnderWorkoutId TEXT")
        }
    }
}

/**
 * Room for whether a Run's Stage has been settled (#297): [RunnerSession.stageSettled].
 *
 * **Every Run already in history arrives settled**, which is the opposite of what [MIGRATION_21_22]
 * does with the record book's mark and for the opposite reason. There, an unscored history was the
 * repair. Here, the settlement *grants a Stage* — and the graduation rule is forwards-only
 * ([ADR 0016](docs/adr/0016-a-requirement-stated-in-numbers-is-not-the-coachs-to-judge.md)): a
 * launch that walked every Run ever recorded and put each to the rule is precisely the pass over
 * history the rule refuses to make, and it would land as the runner being jumped two Stages by an
 * upgrade. So the column is added as a debt and every existing row is paid off in the same breath,
 * leaving the pass with only the Runs recorded from here.
 *
 * The same is true of an older archive restored on top: its rows come back settled, and a Run
 * restored from a backup has already had whatever settlement it was going to get.
 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(database: SupportSQLiteDatabase) {
        if (!database.hasColumn("sessions", "stageSettled")) {
            database.execSQL("ALTER TABLE sessions ADD COLUMN stageSettled INTEGER NOT NULL DEFAULT 0")
        }
        database.execSQL("UPDATE sessions SET stageSettled = 1")
    }
}

/**
 * Room for the offset a Run was recorded at (#304): [RunnerSession.ranAtUtcOffsetSeconds].
 *
 * Left null on every Run already in history, which is neither a repair nor a debt but the plain
 * truth: those Runs never wrote down where the runner's clock was, and nothing can work it out
 * afterwards. The only offset this could fill them with is the one the phone is on the day of the
 * upgrade, and a runner who upgrades abroad would have their whole history re-dated to where they
 * are standing — the exact fault the column exists to stop, applied to every Run at once. They keep
 * being read in the phone's current zone, as they always have been.
 *
 * An older archive restored on top comes back the same way, and for the same reason.
 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(database: SupportSQLiteDatabase) {
        if (!database.hasColumn("sessions", "ranAtUtcOffsetSeconds")) {
            database.execSQL("ALTER TABLE sessions ADD COLUMN ranAtUtcOffsetSeconds INTEGER")
        }
    }
}

/**
 * Room for the Pauses of a Run (#328): [RunPause].
 *
 * Empty for every Run already in history, and nothing could fill it. A Pause left one mark and only
 * on an outdoor Run — the bit on the fix that resumed it — and the instants that mark stands between
 * are the fixes either side, not the Run's own boundaries. Backfilling from it would write down
 * approximations as though they had been measured, and would still leave every treadmill Run empty,
 * which is the case this table exists for. Those Runs keep being read the only way they can be: an
 * Export falls back to the mark on the track, and a Run with neither states how long its Pauses were
 * between its two clocks, as it always has.
 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `run_pauses` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `startTimeMillis` INTEGER NOT NULL,
                `endTimeMillis` INTEGER NOT NULL,
                FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_run_pauses_sessionId` ON `run_pauses` (`sessionId`)"
        )
    }
}
