package com.example.runningapp.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.runningapp.BestEffortRequirement
import com.example.runningapp.CoachPrescription
import com.example.runningapp.CoachPrescriptionRepository
import com.example.runningapp.COACH_PRESCRIPTION_MAX_AGE_DAYS
import com.example.runningapp.CoachWriteScope
import com.example.runningapp.isFreshAt
import com.example.runningapp.HrZone
import com.example.runningapp.RunType
import com.example.runningapp.SettingsRepository
import com.example.runningapp.StatedHeartRates
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.clearedBy
import com.example.runningapp.isCoachAdjusted
import com.example.runningapp.HrProfile
import com.example.runningapp.effectiveMaxHr
import com.example.runningapp.historyHrProfile
import com.example.runningapp.hrProfile
import com.example.runningapp.tallyZoneSeconds
import com.example.runningapp.training.ScoredRun
import com.example.runningapp.training.TrainingWeek
import com.example.runningapp.training.VolumeRun
import com.example.runningapp.training.effortScoreOf
import com.example.runningapp.training.FormVerdict
import com.example.runningapp.training.formVerdictOf
import com.example.runningapp.training.progressCurve
import com.example.runningapp.training.weeklyVolumeOf
import com.example.runningapp.analysis.BestEffort
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.analysis.routeThumbnailOf
import com.example.runningapp.analysis.RunEfforts
import com.example.runningapp.analysis.recordBookOf
import com.example.runningapp.analysis.standingsAfter
import com.example.runningapp.analysis.bestEffortsOf
import com.example.runningapp.recording.SessionRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.roundToInt

// Labels describing a run to the AI coach (#107). Structure comes only from a plan, so the one
// distinction the coach needs is whether the run followed a run/walk workout; these are derived
/**
 * How many Run ids one `IN (:sessionIds)` query is given at a time (#210).
 *
 * Each id is a bound variable, and SQLite takes a bounded number of them — 999 on the versions this
 * app can be installed on. Comfortably under it rather than at it, because the limit is the
 * statement's, not the list's, and nothing here is worth a full history's worth of arithmetic to
 * cut fine.
 */
private const val MAX_SESSION_IDS_PER_QUERY = 500

/**
 * How many banked seconds a heart rate has to have been held at to count as one this runner has
 * reached (#65, #103).
 *
 * Three, which is the smallest number that is more than a moment: a strap can misreport twice, and
 * anything much longer starts discarding the genuine peak of a hard finish, which is exactly the
 * reading the card wants. The point of the guard is to refuse a spike, not to find a plateau.
 */
private const val HIGHEST_HR_HELD_FOR_SECONDS = 3

// Labels describing a run to the AI coach (#107). Structure comes only from a plan, so the one
// distinction the coach needs is whether the run followed a run/walk workout; these are derived
// from RunnerSession.isRunWalkMode, not from any user-selected mode.
private const val AI_LABEL_RUN_WALK = "Run/Walk"
private const val AI_LABEL_OPEN_RUN = "Open Run"

/**
 * A Run the runner has marked as a Walk (#275), and the label that outranks the other two: a Walk
 * that happened to follow the Workout's structure did not *complete* it, so it must not reach the
 * coach described as one.
 *
 * The prompt is told what this means and told not to graduate a Stage on it. Nothing enforces that
 * by reading this string back — [AiTrainingContext.requirementEvidenceRunIdsByTimestamp] is what the
 * refusal is made of, because a graduation cannot be taken back and a label written for a prompt
 * would stop refusing the moment somebody reworded it.
 */
private const val AI_LABEL_WALK = "Walk"

/** What the coach is told a past Run was — one label, three answers, most specific first (#275). */
private fun aiSessionTypeOf(session: RunnerSession): String = when {
    session.isWalk -> AI_LABEL_WALK
    session.isRunWalkMode -> AI_LABEL_RUN_WALK
    else -> AI_LABEL_OPEN_RUN
}

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
    val timestamp: Long,
    /**
     * How the Run was recorded — "outdoor" or "treadmill". Sent because it says what kind of
     * evidence the Run can carry: a treadmill Run has no GPS, so it never has a [fastest5kSeconds]
     * to be judged on, and its [distanceKm] is a whole-Run total the runner stated rather than a
     * route (#182, #231).
     */
    val runMode: String,
    /**
     * How far the Run went, in kilometres — measured against ground on an outdoor Run, and told to
     * the app off the console on a treadmill one, which counts the same everywhere (ADR 0008). Null
     * for a Run with no distance at all, which is a thing unknown rather than a Run of no length.
     */
    val distanceKm: Double?,
    /**
     * The quickest continuous 5K inside the Run, in seconds — the only number here that answers a
     * requirement stated as a 5K in a time, because [durationSeconds] is the whole Run, warm-up and
     * cool-down included. Null when the Run's track never covered 5K in one continuous stretch of
     * recording, which is an absence of evidence and never a failed attempt (#182).
     */
    val fastest5kSeconds: Long?
)

/**
 * One week of Effort Score as the coach is told it (#66, #247).
 *
 * [score] is the week's total, 0 for a week of rest — no Run in it at all, or none hard enough to
 * score — and null for a week nothing measured, where Runs were made and no heart rate was recorded
 * to score them. Opposite news for a coach reading fatigue: a week off is the rest that earns a
 * harder next Run, while training the app could not see is not rest at all.
 *
 * [partlyMeasured] is the third case the total alone cannot say: some Runs in the week were scored
 * and some were not, so the number is a floor under the week and never a ceiling. Sent rather than
 * left to the prompt's general warning, because "one of these four weeks is short" is a different
 * instruction from "any of them might be".
 */
data class AiWeeklyEffort(val score: Int?, val partlyMeasured: Boolean)

/**
 * How much training the runner is carrying, as the coach is told it (#66) — the same three numbers
 * the Progress screen shows, plus the weeks they were built out of.
 *
 * Whole numbers, exactly as the screen rounds them: a Fitness of 31.4 is not measured to a tenth of
 * anything, and the coach reading a different number from the runner would be two answers to one
 * question.
 *
 * [weeklyEfforts] runs oldest week first and ends with the week in progress, which is short by
 * definition — most of it has not been run yet.
 */
data class AiFitnessAndForm(
    val fitness: Int,
    val fatigue: Int,
    val form: Int,
    /** What the Form number means in a word — its own type, so it can only be one of the three. */
    val verdict: FormVerdict,
    val weeklyEfforts: List<AiWeeklyEffort>,
    /**
     * Whether the Run that prompted this is inside [fitness] and [fatigue].
     *
     * False for a Run that recorded no heart rate: it earns no Effort Score, so the curves cannot
     * see it and the numbers above are the load as it stood *before* it. Told to the coach rather
     * than hidden, because the alternative is a strapless hour reading as an hour of rest — the one
     * reading that turns a hard day into permission to prescribe a harder one.
     */
    val todaysRunIsInTheNumbers: Boolean
)

data class AiTrainingContext(
    val currentStageTitle: String,
    val graduationRequirement: String,
    /**
     * Whether this Stage's requirement is one the app answers itself (#290) — a Best Effort at a
     * record distance, in a time.
     *
     * The coach is still shown the requirement and still writes the debrief; what it may not do is
     * graduate. The prompt says so, and [evaluateAndAdjustPlan] refuses a graduation anyway when
     * this is true, because a prompt sentence is a promise the code has to keep (#286, #288) and the
     * two paths must never both be able to grant.
     */
    val requirementIsTheAppsToAnswer: Boolean = false,
    val recentRuns: List<AiRecentRun>,
    /**
     * Which Runs [recentRuns] are, so a Prescription can be taken back when one of them is deleted
     * (#156).
     *
     * Kept beside the runs rather than inside [AiRecentRun], because that list is serialized into
     * the prompt: an id is a fact about this app's database and nothing the coach could reason from,
     * and sending one would invite it to talk about "run 47".
     *
     * The Runs the coach is shown *individually* and no others. A Run that has since left history
     * also moved Fitness and Fatigue, and those are not in here: the curves are 42 days of arithmetic
     * that one Run nudges, while these three are the evidence the intervals were read off. Taking
     * coaching back over a fraction of a Fitness point would mean almost every delete cost the
     * runner their progression.
     */
    val sourceRunIds: Set<Long> = emptySet(),
    /**
     * Which of [sourceRunIds] may answer the Stage's requirement — the structured `Run/Walk`
     * sessions among them, so neither a Walk (#275) nor an unplanned Open Run can stand for one —
     * keyed by the [AiRecentRun.timestamp] the coach was shown for each, so a reply naming one can
     * be resolved back to the Run it named (#287).
     *
     * A separate list from [sourceRunIds] because the two answer different questions. That one is
     * "what was this reply reasoned from", which every Run shown was, Walks included — a week of
     * walking is not a week of rest and a coach that could not see it would read one as the other.
     * This one is "what could graduate a Stage", which only a Run that followed the plan's
     * structure ever can.
     *
     * Kept as ids rather than read back off [AiRecentRun.sessionType], because a graduation cannot
     * be taken back and a label built for a prompt is not a thing to hang one on: reworded, the
     * check would silently stop refusing.
     *
     * Keyed by timestamp rather than listed, because the question this has to answer is not "was
     * there evidence" but "was *this* the evidence" — see [evidenceRunIdsNamedBy] and
     * [AiCoachResponse.graduationEvidenceRunTimestamps].
     */
    val requirementEvidenceRunIdsByTimestamp: Map<Long, Long> = emptyMap(),
    /**
     * Null when there is no scored history to read it from — a new phone, or a runner who has never
     * run with a Strap. The coach is then told nothing about fatigue rather than being told zeroes,
     * which would read as a runner who has done nothing for six weeks (#66).
     */
    val fitnessAndForm: AiFitnessAndForm? = null,
    /**
     * The Stage's own Workout of the kind of Run that just finished — the intervals the coach's
     * answer replaces (#246). Why the coach is shown it, and what it is told about it, is
     * `appendStageWorkout`.
     *
     * The Workout itself rather than a restatement of it, so the numbers the coach is told are the
     * same object the floor measures its answer against and cannot drift from them.
     *
     * Null wherever there is no Workout to prescribe against — every read outside a finish. A
     * finish never gets here with one missing: [evaluateAndAdjustPlan] returns without asking the
     * coach anything when the Stage offers no Workout of the Run's kind.
     */
    val stageWorkout: WorkoutTemplate? = null
) {
    /**
     * The Runs the coach named as what it graduated the Stage on, or null when it named anything
     * this Stage cannot be graduated on (#287).
     *
     * Null covers every way a name can fail, because they all end the same way — a refusal:
     * - **Nothing named.** A graduation with no evidence behind it is worth no more than one whose
     *   evidence is a Walk; the model omitting the field, sending an empty list, or sending
     *   something that is not a list of numbers are all the same answer.
     * - **A Run that cannot answer the Stage.** A Walk (#275) or an unplanned Open Run is shown to
     *   the coach and is absent from this map, so naming one refuses itself. This is the whole point
     *   of asking: a qualifying Run existing in the list must not license a graduation read off the
     *   Walk beside it.
     * - **A Run nobody was shown**, from a model that invented a number or reworked the one it was
     *   given. There is nothing behind it to have graduated anything.
     *
     * All or nothing across the list, not the names that happen to resolve: a graduation resting on
     * three Runs, one of them a Walk, is a graduation resting on a Walk. Keeping the two that
     * resolved would grant it on evidence the coach itself did not think sufficient, which is the
     * same substitution read from the other end.
     *
     * A timestamp that two of the Runs shown share is in the map for neither of them (see
     * `getAiTrainingContext`), so it lands here as a name that resolves to nothing — and that is
     * asked of every Run shown, not only of the ones that could answer the Stage, or a Walk sharing
     * a start with a structured Run would hand the coach the Run's id under the Walk's number. An
     * ambiguous name is not a name, and the doubt is settled the way every doubt on this path is
     * settled: refuse, because a graduation cannot be taken back.
     */
    fun evidenceRunIdsNamedBy(response: AiCoachResponse): Set<Long>? {
        val named = response.graduationEvidenceRunTimestamps?.takeIf { it.isNotEmpty() } ?: return null
        return named
            .map { timestamp -> requirementEvidenceRunIdsByTimestamp[timestamp] ?: return null }
            .toSet()
    }
}

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

/** How many weeks of Effort Score totals the coach is shown (#66). */
private const val AI_WEEKS_OF_EFFORT = 4

/**
 * A week as the coach is told it: its total, and whether that total is the whole week.
 *
 * A week with no Run in it at all is 0 and not null, which [TrainingWeek.effortScore] cannot say on
 * its own — [weeklyVolumeOf] fills a week nobody ran in with the same null a week of strapless Runs
 * comes to. [TrainingWeek.runsWithoutScore] is what tells them apart: a week of rest has no Runs to
 * have gone unmeasured.
 */
private fun TrainingWeek.forCoach(): AiWeeklyEffort =
    AiWeeklyEffort(
        score = effortScore ?: 0.takeIf { runsWithoutScore == 0 },
        partlyMeasured = partlyMeasured,
    )

class SessionRepository(
    private val sessionDao: SessionDao,
    private val sampleDao: SampleDao? = null,
    private val trackPointDao: TrackPointDao? = null,
    private val intervalStatDao: RunWalkIntervalStatDao? = null,
    // Null wherever records are not wired (tests, and the archive's read-only container): a run then
    // finishes without being scored rather than failing to finish.
    private val achievementDao: AchievementDao? = null,
    // Null on the same terms as the record book it feeds: a treadmill Run then simply holds no
    // stated Best Effort, which is what every Run held before #282 anyway.
    private val statedBestEffortDao: StatedBestEffortDao? = null,
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
    suspend fun deleteSession(sessionId: Long) =
        deleteRuns(listOf(sessionId)) { sessionDao.deleteSessionById(sessionId) }

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
                inTransaction { recomputeZoneSecondsAndEffortForAllRuns(samples, historyProfile) }
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
     *
     * Every row it re-bands is stamped with the Reserve it re-banded it against (#228). That is
     * what keeps a Run's stored pair the truth about that Run rather than a note of where it
     * started life: this is the only writer that moves a *finished* Run onto another Reserve.
     */
    private suspend fun recomputeZoneSecondsAndEffortForAllRuns(samples: SampleDao, profile: HrProfile) {
        sessionDao.getFinalizedSessionIds().forEach { sessionId ->
            val bpms = samples.getRawBpmsForSession(sessionId)
            val tally = tallyZoneSeconds(bpms, profile)
            sessionDao.updateZoneSecondsAndEffort(
                sessionId = sessionId,
                zone1 = tally.zone1,
                zone2 = tally.zone2,
                zone3 = tally.zone3,
                zone4 = tally.zone4,
                zone5 = tally.zone5,
                // From the same beats and the same edges as the tally above, so a Run's Effort Score
                // and its zone chart never part company (#61). Only a Run that already has a Score
                // is rewritten — see [SessionDao.updateZoneSecondsAndEffort].
                effortScore = effortScoreOf(bpms, profile),
                // What the Run is banded on from here — including a Run recorded before the pair
                // was written down, which this is the moment to stop guessing about (#228).
                bandedOnMaxHr = profile.maxHr,
                bandedOnRestingHr = profile.restingHr,
            )
        }
    }

    /**
     * Scores the Runs already in history from the beats they wrote down (#62).
     *
     * A Run finished before v21 has every second of its heart rate stored and no Effort Score, so
     * the trends built on those Scores would start empty and fill in one Run a week. This is what
     * lets them tell the runner's real story from the first launch after the update: the same
     * arithmetic the Run would have banked as it ran ([effortScoreOf]), applied afterwards to the
     * samples it kept.
     *
     * **Idempotent and resumable, and by the same means**: the work list is the Runs that have no
     * Score ([SessionDao.getSessionIdsMissingEffort]), asked fresh every time. A process killed
     * half way through leaves the Runs it reached scored, and the next launch asks again and gets
     * the remainder. Once history is scored the list comes back empty and the pass writes nothing.
     * Nothing is written down about the pass's own progress, so there is no mark to be left stale by
     * a restore, a Clear-storage, or an archive dropped in from another phone.
     *
     * Deliberately **not** one transaction, which is where this parts company with the re-tally
     * above ([recomputeZoneSecondsAndEffortForAllRuns]). A re-tally moves history from one Max HR to
     * another and half of that is a history that cannot be compared with itself; this only ever adds
     * a number where there was none, so a half-finished pass is a history that is partly scored —
     * which is exactly the state it is built to resume from. Rolling it back would throw away work
     * and buy nothing.
     *
     * A Run that recorded no beats — no Strap, or a Strap that never connected — is left with no
     * Score rather than a zero, because zero is a real answer here (an hour spent below Zone 1) and
     * the two must not be confused. It stays on the work list for ever at the cost of one read of
     * its empty samples per launch, which is cheaper than any of the ways of remembering that it was
     * already looked at.
     *
     * [profile] is the heart-rate profile to score against, and null — what the launch pass passes —
     * means the one history is already banded on ([UserSettings.historyMaxHr]), which is the same
     * number the re-tally uses. Not the stored maximum: after a future-only Max HR
     * correction those differ, and scoring against the stored one would put these Runs on a profile
     * their own zone times are not on.
     *
     * A global rather than each Run's own Reserve ([RunnerSession.bandedOnHrProfile]), unlike the
     * rescue pass (#228), and for a reason rather than by omission: every Run this pass can reach is
     * a Run with **no** Score, and a Run recorded since the columns existed banks one as it
     * finishes. So the Runs left here are the ones from before them, and the strapless ones — which
     * have no beats to score against any Reserve. Reading a row per Run to learn that, at every
     * launch, for ever, would buy nothing.
     *
     * Read **inside** the lock rather than by the caller, which is why the maximum is a nullable
     * parameter rather than a required one: a profile read outside would be the very staleness the
     * lock is here to prevent, since a statement could land between the read and the pass.
     *
     * Being able to point the pass at an arbitrary maximum is #62's own requirement, held against the
     * Max HR change to come. Worth being exact about what it will and will not do there: this only
     * ever scores Runs that have **no** Score, so once history is scored it is the re-tally
     * ([recomputeZoneSecondsAndEffortForAllRuns]) that moves those Scores to a new maximum, and this
     * that catches anything the re-tally had nothing to move.
     *
     * Holds [statedProfile] for the same reason the rescue pass does: this writes Scores, the
     * re-tally rewrites them, and both can run at launch. Under the lock a Run is either scored
     * before the re-tally walks history — and therefore re-banded by it — or scored afterwards
     * against the profile the re-tally has finished storing. Unserialized, a Score computed against
     * the old maximum could land after the re-tally had already passed that row. The cost is that a
     * heart rate stated while this is running waits for it — the same bargain the re-tally strikes,
     * and bounded the same way: one read and one write per Run, and no file IO.
     *
     * The Downloads snapshot is not refreshed. A Score is a pure re-derivation from samples that are
     * never pruned, so a snapshot taken before this pass restores to a history this pass scores
     * again on the next launch — nothing is lost, and a launch should not pay for a copy of the whole
     * database to carry a number it can rebuild.
     *
     * One Run at a time, keeping going past a failure: a Run whose samples cannot be read should cost
     * the others nothing, and it stays unscored for the next launch to try again.
     */
    suspend fun backfillEffortScores(profile: HrProfile? = null) = statedProfile.withLock {
        val samples = sampleDao ?: return@withLock
        val against = profile
            ?: settingsRepository?.userSettingsFlow?.first()?.historyHrProfile
            ?: return@withLock
        val sessionIds = sessionDao.getSessionIdsMissingEffort()
        if (sessionIds.isEmpty()) return@withLock
        var scored = 0
        sessionIds.forEach { sessionId ->
            try {
                val score = effortScoreOf(samples.getRawBpmsForSession(sessionId), against)
                    ?: return@forEach
                sessionDao.setEffortScore(sessionId, score)
                scored++
            } catch (e: Exception) {
                Log.w("Effort", "Could not score run $sessionId; leaving it for next launch", e)
            }
        }
        Log.d("Effort", "Scored $scored of ${sessionIds.size} unscored run(s)")
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

    /**
     * Which runs have a route worth drawing, asked once for the whole history (#85).
     *
     * The same [getTrackPointsForMap] gate, applied a run at a time rather than a point at a time,
     * so a run only counts as having a route if at least one of its fixes would survive to be
     * written.
     */
    suspend fun getSessionIdsWithMappableTrack(): List<Long> {
        val dao = trackPointDao ?: return emptyList()
        return dao.getSessionIdsWithTrackPoints(SessionRecorder.ACCURACY_THRESHOLD_METERS)
    }

    /** One-shot read of a finished run, for callers that need it once rather than as a stream. */
    suspend fun getSession(sessionId: Long): RunnerSession? = sessionDao.getSessionById(sessionId)

    /** The runs the History list shows, newest first. */
    fun recentSessionsFlow(): Flow<List<RunnerSession>> = sessionDao.getLast20Sessions()

    /**
     * Every scored Run in history, oldest first — what the Progress screen builds its curves from
     * (#63). See [SessionDao.getScoredRunsFlow] for why it is all of history and only the scored
     * part of it.
     */
    fun scoredRunsFlow(): Flow<List<ScoredRun>> = sessionDao.getScoredRunsFlow().map { rows ->
        rows.map {
            ScoredRun(
                startedAtMillis = it.startTime,
                effortScore = it.effortScore,
                isWalk = it.isWalk,
            )
        }
    }

    /**
     * The highest heart rate this runner has actually been recorded at, or null where there is not
     * enough of a record to say (#65, #103).
     *
     * What the confirmation card offers instead of a population formula: the app has kept every
     * beat it ever heard — samples are never pruned — so the runner's own evidence is there to be
     * read, and it beats `220 − age` for the same reason a measurement beats an estimate. On the
     * phone this was built on it is 167 against the untouched default of 190, which is the whole
     * argument.
     *
     * Spike-guarded in [SampleDao.getHighestSustainedBpm], not here, because the guard is part of
     * what the number *means*: an artefact is not a heart rate, and a maximum read off one would
     * push every zone edge up for good.
     *
     * Null where the samples are not wired at all, which is the same answer as a phone with no
     * heart-rate history: nothing measured to suggest, so the card falls back to asking an age.
     */
    suspend fun highestRecordedHr(): Int? =
        sampleDao?.getHighestSustainedBpm(HIGHEST_HR_HELD_FOR_SECONDS)

    /**
     * Every finished Run in history, oldest first — what the weekly volume bars are totalled from
     * (#64). See [SessionDao.getRunVolumesFlow] for why it is every finished Run and all of history.
     *
     * A week's time is counted on the same clock its pace is: moving time where the Run's track has
     * given one, and the Run's own duration where it has not ([paceClockSeconds]). Counting duration
     * everywhere would credit a week for the minutes spent standing at crossings; counting moving
     * time everywhere would leave every treadmill Run out of the week entirely.
     */
    fun runVolumesFlow(): Flow<List<VolumeRun>> = sessionDao.getRunVolumesFlow().map { rows ->
        rows.map {
            VolumeRun(
                startedAtMillis = it.startTime,
                distanceKm = it.distanceKm,
                timeSeconds = it.movingTimeSeconds ?: it.durationSeconds,
                effortScore = it.effortScore,
            )
        }
    }

    /**
     * How many medals each run holds, keyed by run, for the History list's medal badges (#51).
     *
     * A stream, so a run scored the moment it finishes gets its badge without the list being left
     * and re-entered. Empty where records are not wired at all.
     */
    fun medalCountsFlow(): Flow<Map<Long, Int>> =
        achievementDao?.getMedalCountsFlow()?.map { counts ->
            counts.associate { it.sessionId to it.medals }
        } ?: flowOf(emptyMap())

    /**
     * The shape of a run's route, for the drawing beside it in the History list (#51).
     *
     * Thousands of fixes read and walked, so it belongs on a thread the runner is not waiting on —
     * this is asked for a screenful of runs at a time while they are scrolling them. The caller
     * chooses that thread ([com.example.runningapp.ui.HistoryViewModel]).
     *
     * Null for a run with no route: a treadmill run, or one whose fixes were all too poor to draw.
     */
    suspend fun getRouteThumbnail(sessionId: Long): RouteThumbnail? =
        routeThumbnailOf(measureTrack(getTrackPointsForMap(sessionId)))

    /**
     * Finishes any Run a previous process left interrupted, from the seconds it already wrote (#192).
     *
     * A Run whose process is killed mid-recording — the system reclaiming memory, a crash, a battery
     * pull — never reaches the finish that stamps its totals, so its row keeps `endTime = 0` and
     * every query in the app steps around it. The Run disappears from history, from the export and
     * from the coach, while every second of it sits in `hr_samples` and `track_points`. This puts it
     * back: see [finishedFromRecord] for what can be derived and what cannot.
     *
     * [startedBeforeMillis] is the moment this process started. A Run that began before then is not
     * one this process is recording — the service cannot resume a Run across a process death, so it
     * has no live Run older than itself — and that is the whole of what makes this safe to run at
     * launch without a flag, a lock or a look at the recorder. A Run started a moment later, while
     * this pass is still walking the list, is outside the query by construction rather than by
     * timing.
     *
     * Runs one Run at a time and keeps going past a failure: a Run that cannot be rebuilt should
     * cost the others nothing, and it stays interrupted for the next launch to try again.
     *
     * Holds [statedProfile] for the whole pass, because this is the one other writer of banded zone
     * seconds. A re-tally only ever visits finished Runs, so a rescue landing beside one would slip
     * through it: the profile read here, the re-tally banding all of history against a new one, and
     * then this Run stored against the old — the single row in history on a profile nobody else is
     * on. Both happen at launch, which is exactly when they would meet: a statement interrupted last
     * session is replayed then (`StatedHeartRateQueue`), and so is this. Under the lock the Run is
     * either already banded when the re-tally walks history, or banded by this pass against the
     * profile the re-tally has finished storing.
     */
    suspend fun rescueInterruptedRuns(startedBeforeMillis: Long) = statedProfile.withLock {
        val samples = sampleDao ?: return@withLock
        val settings = settingsRepository ?: return@withLock
        val interruptedIds = sessionDao.getInterruptedSessionIds(startedBeforeMillis)
        if (interruptedIds.isEmpty()) return@withLock

        // What history is banded against, and only for a Run carrying no Reserve of its own (#228).
        // A Run that carries one is rebuilt on *that*: it is the Reserve the Run was recorded and
        // coached under, which is the one its seconds mean anything against.
        //
        // Neither global number would do. The one in force is wrong for a Run started before a
        // future-only Max HR correction, and the one history is banded against is wrong for every
        // Run started after it — and a rescue is by definition a Run that ran some time ago.
        val current = settings.userSettingsFlow.first()
        val historyProfile = current.historyHrProfile
        var rescued = 0
        interruptedIds.forEach { sessionId ->
            try {
                val session = sessionDao.getSessionById(sessionId) ?: return@forEach
                // Read once and gated here rather than through [getTrackPointsForMap], because the
                // rebuild wants both: every fix says when the Run was recording, the accepted ones
                // say where it went. See [finishedFromRecord].
                val track = trackPointDao?.getTrackPointsForSessionOnce(sessionId).orEmpty()
                val finished = session.finishedFromRecord(
                    samples = samples.getSamplesForSessionOnce(sessionId),
                    track = track,
                    mappedTrack = track.acceptedForMap(),
                    profile = session.bandedOnHrProfile() ?: historyProfile,
                    bankedIntervals = intervalStatDao
                        ?.getIntervalStatsForSession(sessionId)
                        .orEmpty()
                        .isNotEmpty(),
                ) ?: return@forEach
                sessionDao.updateSession(finished)
                rescued++
                Log.w(
                    "InterruptedRun",
                    "Rescued run $sessionId: duration=${finished.durationSeconds}s " +
                        "distance=${"%.2f".format(finished.distanceKm)}km avgBpm=${finished.avgBpm}"
                )
            } catch (e: Exception) {
                Log.w("InterruptedRun", "Could not rescue run $sessionId; leaving it for next launch", e)
                return@forEach
            }
            try {
                // After the row is finished, not before: this measures the same stored track and
                // rewrites avgPaceMinPerKm over moving time, which is the pace the app quotes (#163).
                computeMovingTime(sessionId)
            } catch (e: Exception) {
                // Its own attempt, because the row is already finished by this point and will never
                // be offered to this pass again. Failing here leaves movingTimeSeconds null, which
                // is the state [backfillMovingTime] picks up at the next launch — so the Run is in
                // history with everything else it needs, and the one number it is missing is
                // already somebody's job.
                Log.w("InterruptedRun", "Rescued run $sessionId but could not measure its moving time", e)
            }
            try {
                // A rescued Run has just finished, however long ago it was run, so it is scored like
                // any other (#49). Its own attempt for the same reason as the moving time above: the
                // row is already in history, and a book that cannot be written must not undo that.
                // Marked as scored by the same call, and only once the scoring has returned, so a
                // failure here leaves the Run owing one for the launch pass to pay (#210).
                scoreAndMarkRecords(sessionId)
            } catch (e: Exception) {
                Log.w("InterruptedRun", "Rescued run $sessionId but could not score its records", e)
            }
        }

        // Only once, and only if something moved: the snapshot is a copy of the whole database, and
        // a launch that rescued nothing should not pay for one.
        if (rescued > 0) refreshHistoryBackup?.invoke()
    }

    /**
     * Fills in [RunnerSession.movingTimeSeconds] for runs recorded before #163, so a run already in
     * history reports the same pace a run recorded today would.
     *
     * The v19 migration adds the column but leaves it null: working the number out means measuring
     * geodesic distances between every pair of a run's track points, which belongs in Kotlin rather
     * than in SQL. Safe to call more than once — a run is only looked at while its column is null.
     *
     * Null is also how a rule change is served: the v23 migration withdraws every measured run's
     * answer so this pass measures the history again, the once, under the rule an Outage is judged
     * by now ([ADR 0012](docs/adr/0012-an-outage-is-a-leg-like-any-other.md)).
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

        // Measured against the run's own clock, and never below zero. Moving time is measured on
        // wall-clock track timestamps while durationSeconds excludes paused time, so a pause the
        // track cannot see would otherwise let moving time exceed the run it belongs to - and the
        // summary card would show a negative resting time. The clock is handed to the measurement
        // itself rather than trimmed off the total here, because the splits table folds the same
        // legs and has to reach the same answer (measureMovingTimeSeconds, #165).
        //
        // The last coercion still stands for the run whose *recorded* legs outrun its own clock,
        // which no Outage rule can measure around.
        val movingTime = measureMovingTimeSeconds(points, session.durationSeconds)
            .coerceAtMost(session.durationSeconds)
            .coerceAtLeast(0)

        sessionDao.setMovingTime(
            sessionId = sessionId,
            movingTimeSeconds = movingTime,
            avgPaceMinPerKm = averagePaceMinPerKm(movingTime, session.distanceKm),
        )
        return movingTime
    }

    /**
     * Scores a finished run against the record book and banks whatever it won (#49).
     *
     * Returns the medals *this run* holds afterwards, which is what its own page shows — an empty
     * list for an ordinary run, and for one that beat nothing.
     *
     * Called when a run finishes, and safe to call again: [standingsAfter] drops the run's own standing rows
     * before ranking it, so a re-score cannot leave it racing itself. The read of the book, the
     * ranking and the rewrite are one transaction, because a half-written book has a record with two
     * golds in it and no way to tell which one is real.
     *
     * Scoring history recorded before this shipped is a job of its own (#50): it means measuring
     * every stored track, and it has to happen once rather than every time a run finishes.
     */
    suspend fun scoreRecords(sessionId: Long): List<Achievement> =
        scoreRecordsUnlessOvertaken(sessionId).orEmpty()

    /**
     * [scoreRecords], with the one answer it cannot give: `null` for a Run that changed underneath
     * the measuring, and was therefore not written to the book at all (#210).
     *
     * Measuring a Run is minutes of arithmetic on a long history, and the Run is read at the start
     * of it. A stated distance corrected in that window — or the Run deleted — is a change that
     * scores itself and mends the book behind it, so a rewrite landing afterwards out of the effort
     * measured *before* it would put the old number back, over a mend that had already been made.
     * Nothing later would find it: only the top three are stored, so the effort the correction
     * promoted exists nowhere else, and a Run marked scored is never revisited. That window was
     * always here in principle, and the launch pass is what makes it wide — every historical Run
     * queued behind one another after the v22 migration.
     *
     * So the Run is read again inside the transaction that writes the book, and the write is
     * abandoned if the effort it measured is no longer the Run's own. Inside, because the database
     * takes one writer at a time: either the correction has committed by then and this reads it, or
     * it commits afterwards and its own mend has the last word.
     *
     * Cheaper than a lock, and the right shape: nothing is made to wait behind minutes of
     * arithmetic. The cost of abandoning is that the Run keeps owing a scoring, which the next
     * launch pays against a book nobody is moving.
     */
    private suspend fun scoreRecordsUnlessOvertaken(sessionId: Long): List<Achievement>? {
        val dao = achievementDao ?: return emptyList()
        val session = sessionDao.getSessionById(sessionId) ?: return emptyList()
        // The same accuracy-gated points the map, the splits and the GPX export are built from, so a
        // fix the run itself refused cannot come back as a record nobody ran.
        val stated = statedEffortsOf(sessionId)
        val efforts = bestEffortsOf(session, getTrackPointsForMap(sessionId), stated = stated)
        if (efforts.isEmpty()) return emptyList()

        var earned: List<Achievement>? = null
        inTransaction {
            val now = sessionDao.getSessionById(sessionId)
            if (now == null || !now.contestsAs(session) || statedEffortsOf(sessionId) != stated) {
                Log.d("Records", "Run $sessionId changed while it was being measured; leaving it unscored")
                return@inTransaction
            }
            val rewritten = standingsAfter(dao.getAllAchievements(), sessionId, efforts)
            dao.deleteAchievementsOfTypes(efforts.map { it.type })
            dao.insertAchievements(rewritten)
            earned = rewritten.filter { it.sessionId == sessionId }
        }
        return earned
    }

    /**
     * Whether two readings of the same Run would put the same efforts to the book: everything
     * [bestEffortsOf] measures a Run by, and nothing else (#210).
     *
     * Deliberately not the whole row. A Run's feel, its note and its Effort Score can all be written
     * while history is being measured — the Effort backfill runs at the same launch — and none of
     * them can change a distance or a duration, so none of them is a reason to abandon a scoring.
     *
     * The Walk mark is here for the opposite reason (#275): it changes what the Run is worth at
     * every record at once, from everything it measured to nothing at all. Marked in the window, the
     * Run scores itself and mends the book behind it, and a rewrite landing afterwards out of the
     * efforts measured before the mark would put its medals straight back.
     *
     * What a Run has been *told* it holds is not in the row at all, so the caller checks that
     * separately against the same table (#282) — for the same reason and against the same window: a
     * stated Best Effort corrected while history is being measured scores itself and mends the book
     * behind it, and a rewrite landing afterwards out of the older claim would put the old time
     * back over a mend already made.
     */
    private fun RunnerSession.contestsAs(other: RunnerSession): Boolean =
        endTime == other.endTime &&
            runMode == other.runMode &&
            durationSeconds == other.durationSeconds &&
            distanceKm == other.distanceKm &&
            isWalk == other.isWalk

    /**
     * Scores a Run and, only once that has landed, writes down that it has been scored (#210).
     *
     * The order is the whole of it, which is why the two are one function rather than a rule three
     * callers are asked to remember. [scoreRecords] is the work; the mark is the receipt, and it is
     * written after — never inside the scoring, and never in the same breath as the row being
     * stamped finished. Every way the work can end short of finishing therefore leaves the Run
     * owing a scoring: the process reclaimed, the write thrown. That debt costs one redundant
     * re-score at the next launch, which is safe, where a receipt written early would cost the Run
     * its medals for good.
     *
     * Returns what the Run holds afterwards, exactly as [scoreRecords] does, and throws where it
     * throws — an unmarked Run being precisely what the caller wants left behind.
     *
     * A Run that changed while it was being measured is one of those ways of ending short: nothing
     * was written to the book (see [scoreRecordsUnlessOvertaken]), so nothing is marked either, and
     * the debt stands for the next launch to pay.
     *
     * Called for a statement too, and not only for a Run finishing (#282). A Stated Distance or a
     * stated Best Effort re-scores a Run the book has already seen — but the mark is what stops the
     * book ever looking again, so a claim stored against a marked Run is a medal nobody goes back
     * for if the scoring behind it ends short. [writeAndScore] lifts the mark first for exactly
     * that reason, which leaves this to hand it back.
     */
    suspend fun scoreAndMarkRecords(sessionId: Long): List<Achievement> {
        val earned = scoreRecordsUnlessOvertaken(sessionId) ?: return emptyList()
        sessionDao.setRecordsScored(sessionId)
        return earned
    }

    /**
     * Scores every finished Run the book never measured, at launch (#210).
     *
     * A Run is scored the moment it finishes, and that is the only moment anything offers it to the
     * book — so a scoring that is missed is missed for good. The process can be killed between the
     * row being stamped finished and the book being written; the write itself can throw and be
     * logged. Nothing revisits it afterwards: the interrupted-run rescue only looks at Runs with no
     * end time, a later Run's scoring ranks only itself, and the seeding pass declines once history
     * carries its mark. This is the pass that goes back for them.
     *
     * **Silent while history is still owed its seeding.** That pass is about to measure all of
     * history at once and its book is the better one — only a rebuild can fill a hole *below* the
     * stored top three — so scoring Runs one at a time in front of it would be work done twice for
     * a worse answer. It marks the Runs it measured itself, which leaves this with nothing to do.
     *
     * One Run at a time, each marked as its scoring lands, so a pass cut short keeps what it paid
     * for and the next launch picks up the rest. A Run that cannot be scored costs the others
     * nothing and stays owed. Failures are logged and never thrown: a book that cannot be written
     * is not a reason to take the app down on the way to the first screen.
     */
    suspend fun scoreMissedRecords() {
        if (achievementDao == null) return
        val settings = settingsRepository ?: return
        if (!settings.userSettingsFlow.first().historyRecordsSeeded) return
        val sessionIds = sessionDao.getSessionIdsMissingRecordScoring()
        if (sessionIds.isEmpty()) return
        var scored = 0
        sessionIds.forEach { sessionId ->
            try {
                scoreAndMarkRecords(sessionId)
                scored++
            } catch (e: Exception) {
                Log.w("Records", "Could not score run $sessionId; leaving it for next launch", e)
            }
        }
        Log.d("Records", "Scored $scored of ${sessionIds.size} run(s) the book had missed")
    }

    /**
     * Puts every Run already in history to the record book, once (#50).
     *
     * #49 scores a Run as it finishes, which leaves the history recorded before it shipped — years
     * of it — holding medals nobody ever awarded. This is the pass that awards them: every stored
     * track measured, the whole book built from all of it at once, so old Runs show the
     * achievements they earned at the time and the book means "all time" rather than "since the
     * update".
     *
     * Once, because it is minutes of GPS arithmetic over a long history: the mark is stored only
     * after the rebuilt book has been committed, so a pass killed part-way through — the process
     * reclaimed, the phone off — simply runs again at the next launch rather than leaving half a
     * book behind. Nothing is written until the rebuild is complete, so there is no half state to
     * resume from and nothing to clean up.
     *
     * Deliberately *not* gated on the book being empty: a fresh install scores its first Run the
     * moment it finishes, and a book with one Run in it would look seeded while the rest of a
     * restored history was still unread. The mark travels with the history it describes — a restored
     * archive clears it (see [com.example.runningapp.SettingsRepository.restoreArchivedSettings]),
     * and a Clear-storage wipe takes it with the settings, which is the safe direction: an
     * unnecessary reseed costs a few minutes of background work and produces the same book.
     */
    suspend fun seedRecordsFromHistory() {
        if (achievementDao == null) return
        val settings = settingsRepository ?: return
        if (settings.userSettingsFlow.first().historyRecordsSeeded) return

        // Noted before a line of history is read, and asked again before the mark is written. Both
        // under the lock, so a delete cannot slip into the gap between looking and deciding.
        var deletesBefore = 0L
        var deleteRunningBefore = false
        recordBookMark.withLock {
            deletesBefore = deletesStarted.get()
            deleteRunningBefore = deletesActive.get() != 0
        }
        // Which Runs this pass is about to settle the debt of (#210), read before it measures
        // anything: everything on this list is finished now, so the rebuild below is certain to
        // measure it. A Run that finishes while the measuring is going on is deliberately not here
        // — it scores itself, and if that scoring is missed the debt is still its own to owe.
        val runsOwedScoring = sessionDao.getSessionIdsMissingRecordScoring()
        try {
            val book = rebuildRecords(RecordType.entries)
            if (book == null) {
                Log.d("Records", "A stated Best Effort changed while history was being scored; leaving it for next launch")
                return
            }
            // Only now: until this lands, the pass is still owed. And not at all if a delete ran at
            // any point alongside it — one that started after the baseline was taken, one still
            // running now, or one already under way when it was taken, which a count of *starts*
            // cannot see on its own. That delete read history as unseeded, so it lifted no mark and
            // will hand none back, and its own mend can still be cut short: marking here would
            // stand over a book with a hole in it that nothing can find. Left unmarked, the next
            // launch reseeds, which is minutes of background work for the right book.
            // Asked and answered under the lock, so a delete arriving between the question and the
            // write is one that waits rather than one this write talks over.
            recordBookMark.withLock {
                if (deletesStarted.get() != deletesBefore ||
                    deleteRunningBefore ||
                    deletesActive.get() != 0
                ) {
                    Log.d("Records", "A run was deleted while history was being scored; leaving it for next launch")
                    return
                }
                settings.setHistoryRecordsSeeded()
                // In the same breath as the whole-history mark, and on the same terms: this book
                // measured every one of them, so none of them is owed a scoring of its own (#210).
                // Nothing is marked on the path above, where the pass declines the mark — that book
                // may have a hole in it, and a Run marked scored against it would never be revisited.
                // In batches, because every id is a bound variable and SQLite takes a bounded
                // number of them: a history long enough to be worth seeding is a history long
                // enough to exceed it.
                runsOwedScoring.chunked(MAX_SESSION_IDS_PER_QUERY)
                    .forEach { sessionDao.setRecordsScoredForSessions(it) }
            }
            Log.d("Records", "Seeded the record book from history: ${book.size} medal(s) awarded")
        } catch (e: Exception) {
            // Caught rather than left to the launch scope, which has no handler behind it: a book
            // that cannot be built is a card the runner does not see yet, not a reason to take the
            // app down on the way to the first screen. Unmarked, so the next launch tries again.
            Log.w("Records", "Could not seed the record book from history; leaving it for next launch", e)
        }
    }

    /**
     * The one way a Run leaves history, and the one way a medal it holds is written down to a
     * smaller number: what it held noted, the change made durable, and only then the book mended
     * (#50, #231).
     *
     * Both are the same shape, which is why they are the same function. A deletion takes a medal off
     * the book; a distance corrected downward demotes one. Either way the Run behind it — the one
     * that should move up — exists nowhere but in history, because only the top three are banked, so
     * neither can be put right by re-scoring one Run. Nothing here is about the deletion in
     * particular except the words: [change] is whatever the sessions are about to be made into, and
     * [mendOnly] narrows the mend where the caller knows which record can have moved. Everything
     * below says "delete" because deletion is where all of it was worked out, and every line of it
     * holds for a correction unchanged — including the counters ([deletesStarted], [deletesActive]),
     * which count anything that can leave the book standing short, not deletions specifically.
     *
     * **The backup is refreshed twice, and the first one is the important one.** The Downloads
     * snapshot is what a Clear-storage restore reads, so until it is rewritten it still holds the
     * deleted Run. Mending the book measures every stored track — minutes on a long history — and a
     * process killed inside that window would leave the deletion committed here and undone there,
     * so the runner could restore a Run they had deleted. The snapshot therefore goes out the
     * moment the rows are gone, before anything slow, and the deletion is durable from that point
     * whatever happens next. What the change itself owes goes out ahead of even that
     * ([onceRowsAreDurable]): the snapshot is a copy of rows that have already gone, while the
     * coaching taken back is the thing the deletion promised the runner.
     *
     * The second refresh carries the mended book — the promotions behind the deleted Run — into the
     * snapshot, and is skipped entirely when the Run held nothing, which is the ordinary case. Its
     * failing costs a restored history a record standing two deep until something contests it; the
     * first one's failing would cost the runner a deletion that did not stick.
     *
     * **The seeding mark is lifted first of all, and handed back last.** From the moment the rows
     * go the medals go with them, so until the book is mended a record the deleted Run held stands
     * short — and short is a hole nothing else can find: only the top three are ever stored, so the
     * fourth-best effort that should move up exists nowhere but in the tracks, and no future Run
     * finishing can promote it. The debt is therefore written down *before* the delete, not after
     * it: everything between here and the mend can be cut short — the process reclaimed, the view
     * model's scope cancelled by the runner leaving the screen mid-backup — and a debt recorded
     * afterwards would be a debt those endings skip. Recorded first, every one of them leaves
     * history owing a reseed, which the next launch pays.
     *
     * Lifted for deletes that turn out to hold nothing too, because what a Run held is not known
     * until it is already gone. That costs a needless reseed only if the process dies inside the
     * delete itself, and a reseed of unmoved history arrives at the same book.
     *
     * Only if it was marked to begin with: an install whose seeding pass has not finished (or has
     * failed) is already owed one, and marking it seeded at the end of a two-record repair would
     * cancel a debt this never paid. And only if this delete was the only one there was: another
     * one mending different records is a debt of its own, and this one's mend landing says nothing
     * about whether that one's will. "The only one" is asked two ways, because one delete can be
     * *behind* another as easily as ahead of it: none begun since this one started, and none still
     * running once this one has stepped out of the count. Deletes that overlapped in either
     * direction leave the reseed owed, which the next launch pays.
     */
    private suspend fun changeAndRepair(
        sessionIds: List<Long>,
        mendOnly: Collection<RecordType>? = null,
        /**
         * What else the change owes, run the moment the rows are durable — before the backup and
         * before the mend, which is the slow part (#156). First of everything that follows the
         * commit, because a delete's promise to the runner is that the coaching goes with the Run,
         * and everything after the commit can be cut short.
         *
         * Uncancellable, which closes one of the two ways it can be cut short and not the other. The
         * runner leaving the history screen cancels this scope, and there is no second attempt and
         * no pass at startup to find the work undone — so cancellation must not reach it. A process
         * *reclaimed* still can, and what is left of that is set out on [deleteRuns].
         *
         * Nothing here is allowed to stop the mend either: whatever it needs to do, a failure of it
         * is a smaller loss than a record book left standing short.
         */
        onceRowsAreDurable: (suspend () -> Unit)? = null,
        /**
         * Held from before the rows go until [onceRowsAreDurable] has finished, and let go before
         * the mend — so a caller that has to make the change and what it owes look like one act to
         * everybody else can say so (#156). Null where nothing else is watching.
         */
        whileTheRowsGo: Mutex? = null,
        change: suspend () -> Unit,
    ) {
        val settings = settingsRepository
        var mine = 0L
        var wasSeeded = false
        // Only ever true once the mend has landed, so every way out of the block below — thrown,
        // cancelled — leaves the debt standing.
        var repaired = false
        // Joining is one indivisible act: take a number, join the count, and lift the mark. Held
        // across the DataStore read and write because that is the check-and-act another delete has
        // to be kept out of — one entering here between another's read and its write would find the
        // mark already down, lift nothing, and be owed nothing back.
        //
        // Uncancellable, like the leaving below and for the same reason: both suspend, and a
        // cancellation landing after the count was joined but before the block finished would leave
        // a delete counted that is not running. See [deletesActive].
        //
        // Inside the `try` below, because joining is not one write but three — take a number, join
        // the count, then read and lower the mark — and the last two suspend on DataStore, which can
        // throw. A join that got as far as the count and no further would otherwise leave a delete
        // counted that never ran and never leaves, and that phantom stops every later delete and
        // every seeding pass in this process from calling the book whole: a full reseed at every
        // launch, for the life of the install. [joined] is raised the instant the count is joined,
        // so the leaving below answers for exactly the part that happened.
        var joined = false
        try {
            withContext(NonCancellable) {
                recordBookMark.withLock {
                    mine = deletesStarted.incrementAndGet()
                    deletesActive.incrementAndGet()
                    joined = true
                    wasSeeded = settings?.userSettingsFlow?.first()?.historyRecordsSeeded == true
                    if (wasSeeded) settings?.clearHistoryRecordsSeeded()
                }
            }
            // Read and removed in one transaction, so nothing can award these Runs a medal in
            // between. The seeding pass commits a whole book at once and a delete arrives from the
            // history screen while it may still be running: read outside, a medal landing in that
            // gap would be cascaded away by the delete and never appear in `losing`, leaving the
            // record it took vacant with no repair coming and the pass marking history complete
            // over the hole.
            var losing = emptyList<RecordType>()
            whileTheRowsGo.holding {
                inTransaction {
                    losing = recordsHeldBy(sessionIds).filter { mendOnly == null || it in mendOnly }
                    change()
                }
                // Before the backup, and uncancellable — see [onceRowsAreDurable]. Both narrow the
                // same window, the one where the rows have gone and what stood on them has not been
                // taken back yet: ahead of the backup it is one settings write wide instead of a
                // whole copy of history, and uncancellable it is not walked out of by the runner
                // leaving the screen. Narrowed, not closed — a process reclaimed inside the write
                // itself still lands there, which [deleteRuns] says plainly.
                withContext(NonCancellable) { onceRowsAreDurable?.invoke() }
            }
            refreshHistoryBackup?.invoke()

            repaired = repairRecordBook(losing, remeasured = sessionIds)
            if (losing.isNotEmpty()) refreshHistoryBackup?.invoke()
        } finally {
            // Leaving is the same act in reverse, and under the same lock: step out of the count,
            // look around, and hand the mark back only if there is nobody left to speak for. Sampled
            // and written together, so a delete arriving in between cannot be one this write ignores.
            //
            // In a finally so a cancelled delete — the runner leaving the history screen — stops
            // holding every other caller's mark down. It leaves the debt behind it either way, since
            // `repaired` is only true once the mend has landed.
            //
            // And uncancellable, because this *is* the cancellation path and taking the lock
            // suspends: run in the cancelled scope it would throw before the count came down, and
            // the phantom delete left behind would stop every later delete and every seeding pass in
            // this process from ever calling the book whole — a full reseed at every launch, for the
            // life of the install.
            //
            // And only if the count was joined, since a join that threw before it landed has
            // nothing standing in it to take out.
            if (joined) withContext(NonCancellable) {
                recordBookMark.withLock {
                    deletesActive.decrementAndGet()
                    val onlyDelete = deletesStarted.get() == mine && deletesActive.get() == 0
                    if (wasSeeded && repaired && onlyDelete) settings?.setHistoryRecordsSeeded()
                }
            }
        }
    }

    /**
     * The two halves of "was a delete going on while I worked?", which is the question anything
     * about to call history scored has to answer before it does (#50).
     *
     * Asked by the seeding pass and by a delete's own mend, because either can overlap a delete and
     * the result is the same shape: a marked book with a hole in it. A delete that reads history as
     * unseeded lifts no mark, because the seeding pass owes one already — but if that pass then
     * finishes and marks history complete while the delete's mend is cut short, the record the
     * deleted Run held stands short with the mark saying otherwise. Two overlapping deletes reach
     * it from the other side: the first one's mend landing says nothing about whether the second's
     * will, so it must not be the one to call the book whole. Nothing later can find what either
     * leaves behind, since only the top three are ever stored.
     *
     * **Both counters, because neither answers it alone.** [deletesStarted] only ever climbs, so
     * comparing it against a baseline catches a delete that began *after* the baseline was taken
     * and nothing else — a delete already under way at that moment is invisible to it, and one that
     * is still running when the baseline is checked again looks identical to one that finished.
     * [deletesActive] is what says a delete is happening *now*. Asked at both ends, they cover a
     * delete ahead, behind, or alongside.
     *
     * Counters rather than a lock, because the lock would be the wrong shape: seeding is minutes of
     * arithmetic, and a delete made to wait behind it is a history screen that does not respond.
     * These let everything run and refuse only the *mark* when two of them overlapped, which costs
     * a reseed at the next launch and arrives at the same book.
     *
     * In memory only, and that is enough: they exist to catch two things overlapping inside one
     * process, and a process that dies takes any unwritten mark with it.
     *
     * **[deletesActive] must come back down whatever happens**, which is why both the joining and
     * the leaving run uncancellable. A delete counted but not running is not a wrong book — it errs
     * the safe way, refusing the mark — but it never stops erring: every later delete and every
     * later seeding pass would decline to call the book whole, so the install pays a full reseed at
     * every launch for as long as it lives.
     */
    private val deletesStarted = AtomicLong(0)
    private val deletesActive = AtomicInteger(0)

    /**
     * Held while the seeding mark is being looked at and moved, and never while anything is
     * measured (#50).
     *
     * The counters say who was working; this is what makes *asking them and acting on the answer*
     * one act. Sampled and then written without it, a delete could enter in the gap — find the mark
     * already down so it lifts nothing and is owed nothing back — while the write it arrived after
     * put the mark up over a mend that had not landed. Every reader of the counters therefore does
     * its looking and its writing inside here.
     *
     * What is *not* inside here is the work: the rebuild, the backup, the delete's own transaction
     * all happen with the lock released. Nothing held across it takes longer than a DataStore edit,
     * so a delete never waits on a seeding pass, which is the whole reason these are counters and
     * not a lock around the work itself.
     */
    private val recordBookMark = Mutex()

    private suspend fun recordsHeldBy(sessionIds: List<Long>): List<RecordType> =
        achievementDao?.getAchievementsForSessions(sessionIds)?.map { it.type }?.distinct().orEmpty()

    /**
     * Rebuilds the records a deleted Run held, so the places below it move up (#50).
     *
     * Only the records it actually held: deleting a Run that never won anything changes nothing
     * about the book, and must not cost a re-measure of the whole history to prove it — so an empty
     * list is nothing to do rather than a whole history to walk, and counts as mended.
     *
     * Its own attempt, because the Run is already deleted by the time this runs. A book that cannot
     * be rewritten leaves a record two deep until the next Run contests it, which is a wrong number
     * on a card; failing here would instead leave the runner staring at a delete that appeared not
     * to work, with the run gone anyway. Returns whether it landed, so the caller can leave history
     * owing a full reseed when it did not.
     */
    private suspend fun repairRecordBook(types: List<RecordType>, remeasured: List<Long>): Boolean {
        if (types.isEmpty()) return true
        return try {
            // Null is the rebuild declining to commit, which is not a failure but is not a repair
            // either: the book stands as it was and the debt stays owed.
            rebuildRecords(types, remeasured) != null
        } catch (e: Exception) {
            Log.w("Records", "Deleted run(s) held ${types.size} record(s) the book could not be rebuilt for", e)
            false
        }
    }

    /**
     * Measures the whole history and writes [types] of the record book from it, returning what it
     * wrote (#50).
     *
     * The measuring is done outside the transaction and the writing inside it, which is the split
     * that matters: reading and measuring every stored track is minutes of work, and holding the
     * database's write lock for it would stall the per-second inserts of a Run being recorded.
     * Everything that *changes* the book is one commit, so the book is never half rewritten.
     *
     * A Run that finished while the measuring was going on has already scored itself, and its rows
     * would be wiped by the rewrite — so the standing rows of any Run this pass did not measure an
     * effort for are carried in as claims of their own. Their stored value is the effort they were
     * awarded for, so they can be ranked beside the freshly measured ones without measuring again.
     * "Did not measure an effort for" rather than "did not see": a Run still being recorded when
     * history was read *is* in the list, and is worth nothing until it finishes — which is exactly
     * the Run most likely to finish and score itself while this pass is still measuring.
     *
     * [remeasured] is the Runs this rebuild was called *for*, whose standing rows are therefore
     * never carried in — the whole point of the pass is to replace them. Without it a Run that now
     * measures to nothing is indistinguishable from one that was never measured, and its old row
     * comes straight back: a Stated Distance withdrawn would keep the medal it held at the number it
     * no longer has (#231). Empty for the seeding pass, which is measuring history rather than
     * mending it, and harmless for a deletion, whose rows have already cascaded away.
     *
     * On [Dispatchers.Default] because the measuring is geodesic arithmetic over every stored
     * track — minutes of it on a long history. The callers are a launch-time pass and a delete from
     * the history screen, and the delete arrives on the main thread.
     */
    private suspend fun rebuildRecords(
        types: List<RecordType>,
        remeasured: List<Long> = emptyList(),
    ): List<Achievement>? {
        val dao = achievementDao ?: return emptyList()
        // Every statement in history, in one read rather than one per Run: a query inside the loop
        // below is a round trip per Run in the runner's life, to fetch at most five rows. Read
        // before the measuring, and checked again after it — see the abandonment below.
        val statedBefore = claimsAt(types)
        // Regrouped out of the very list the abandonment below compares against, rather than read a
        // second time: the claims a Run is measured against have to be the claims that were
        // compared, or the rebuild could commit having measured one reading and checked another.
        val statedByRun: Map<Long, Map<RecordType, Double>> = statedBefore
            .groupBy({ it.sessionId }) { it.type to it.seconds.toDouble() }
            .mapValues { (_, claims) -> claims.toMap() }
        // One Run at a time, because a track is thousands of points and the whole history's worth
        // of them at once is not something to hold in memory. Unfinished Runs are in this list and
        // measure to nothing, which is what [bestEffortsOf] says they are worth.
        val measured = withContext(Dispatchers.Default) {
            sessionDao.getAllSessions().map { session ->
                RunEfforts(session.id, effortsAt(session, types, statedByRun[session.id].orEmpty()))
            }
        }
        val measuredIds =
            measured.filter { it.efforts.isNotEmpty() }.map { it.sessionId }.toSet() + remeasured

        var written: List<Achievement>? = null
        inTransaction {
            // The statements are asked for again, inside the transaction that writes the book, and
            // the whole rebuild is abandoned if they have moved (#282). A Run finishing mid-measure
            // is already answered — it scores itself, and its standing rows are carried in as
            // `unseen` — but a *statement* has no such answer: stating or improving one takes the
            // direct scoring path rather than this one, so a rebuild committing afterwards out of
            // the claims it read minutes ago would overwrite that scoring with the old time, or
            // with no row at all. Nothing later would find it: only the top three are stored.
            //
            // Inside, because the database takes one writer at a time — either the statement has
            // committed by now and this sees it, or it commits afterwards and its own scoring has
            // the last word. Abandoning costs a book left as it was and the seeding debt still
            // owed, which the next launch pays against a history nobody is moving.
            if (claimsAt(types) != statedBefore) {
                Log.d("Records", "A stated Best Effort changed while the book was being rebuilt; leaving it")
                return@inTransaction
            }
            val unseen = dao.getAllAchievements()
                .filter { it.type in types && it.sessionId !in measuredIds }
                .groupBy { it.sessionId }
                .map { (sessionId, rows) ->
                    RunEfforts(sessionId, rows.map { BestEffort(it.type, it.value) })
                }
            val book = recordBookOf(measured + unseen)
            dao.deleteAchievementsOfTypes(types)
            dao.insertAchievements(book)
            written = book
        }
        return written
    }

    /**
     * One statement as the record book's input sees it: which Run made it, at which record, and the
     * time it claims (#282).
     *
     * The claim and not the stored row, which is what lets two readings of an unmoved table compare
     * equal: correcting a statement replaces it, giving the same claim a new id, and an id is not
     * something a record book has ever ranked by.
     */
    private data class Claim(val sessionId: Long, val type: RecordType, val seconds: Int)

    /**
     * Every claim standing at [types], as the thing two readings of it are compared by (#282).
     *
     * Sorted so two reads of an unchanged table compare equal whatever order the database hands
     * them back in.
     */
    private suspend fun claimsAt(types: List<RecordType>): List<Claim> =
        statedBestEffortDao?.getAll().orEmpty()
            .filter { it.type in types }
            .map { Claim(it.sessionId, it.type, it.seconds) }
            .sortedWith(compareBy({ it.sessionId }, { it.type }))

    /** What one Run is worth at [types], measuring its track only if one of them needs it. */
    private suspend fun effortsAt(
        session: RunnerSession,
        types: List<RecordType>,
        stated: Map<RecordType, Double> = emptyMap(),
    ): List<BestEffort> {
        // The longest time is asked of the Run's own clock; every other record is measured against
        // ground, so it needs the track read and accuracy-gated first.
        val overGround = types.any { it != RecordType.LONGEST_DURATION }
        val track = if (overGround) getTrackPointsForMap(session.id) else emptyList()
        return bestEffortsOf(session, track, types, stated)
    }

    /** What one Run has been told it holds, in the shape the record book ranks (#282). */
    private suspend fun statedEffortsOf(sessionId: Long): Map<RecordType, Double> =
        statedBestEffortDao?.getForSession(sessionId).orEmpty().byType()

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
        awaitFinalized(sessionId, finalizeWaitStepMillis) ?: return
        sessionDao.updateFeelFeedback(sessionId, effort, trimmedNote)
        // Fold this user-entered history into the Downloads snapshot too, or a Clear-storage
        // restore before the next run would bring the run back without it.
        refreshHistoryBackup?.invoke()
    }

    /**
     * Changes what the runner said about a Run, from the Run's own page (#80).
     *
     * The same two columns [saveFeelFeedback] writes, under a different rule: **nothing left to say
     * is an instruction here**, not a skip. A runner who empties a note is asking for it to be gone,
     * so nulls are written rather than treated as an absent answer — which is the one thing the
     * sheet at the finish must never do, where "nothing" means the runner walked past it.
     *
     * A Run still being recorded is refused outright rather than waited for: `finalizeRun` writes
     * the row whole, and unlike the sheet — which is on screen while that write is in flight — this
     * page is reached long afterwards, so an unfinished row here is a Run that has no business being
     * edited at all.
     *
     * Nothing changed writes nothing, so re-opening the dialog and pressing Save costs neither a row
     * update nor a copy of the whole database.
     */
    suspend fun editFeelFeedback(sessionId: Long, effort: Int?, note: String?) {
        val session = sessionDao.getSessionById(sessionId) ?: return
        if (!session.isFinished()) {
            Log.w("FeelFeedback", "Refusing an edit for run $sessionId: it is not finished")
            return
        }
        val trimmedNote = note?.trim()?.ifEmpty { null }
        if (effort == session.perceivedEffort && trimmedNote == session.sessionNote) return
        sessionDao.updateFeelFeedback(sessionId, effort, trimmedNote)
        // As at the finish: this is history the runner typed, and a Clear-storage restore that
        // brought the Run back without it would lose the only copy.
        refreshHistoryBackup?.invoke()
    }

    /**
     * Marks a finished Run as a Walk, or takes the mark back (#275) — see [RunnerSession.isWalk].
     *
     * The runner's own word about a Run, said on the sheet at the finish and changeable on the Run's
     * own page for ever afterwards. Nothing here infers it and nothing else writes it.
     *
     * **It waits for the Run to be finished**, for the same reason a Stated Distance does: the sheet
     * that asks is on screen from the moment STOP is pressed while `finalizeRun` is still writing
     * the row whole, so a mark landing first would be overwritten by a false a second later.
     *
     * **Marking a Run a Walk takes its medals off it, through the mend a deletion already owes.** A
     * Walk contests nothing ([bestEffortsOf]), so every record it held falls vacant — and the Run
     * that should move up behind it exists nowhere but in history, since only the top three are
     * banked. Re-scoring this Run alone could never find it. Unmarking goes the other way: the Run
     * can only win things back, which is the improvement path [writeAndScore] exists for.
     *
     * **It rewrites the past, and silently.** The curves are worked out on read, so marking a
     * session from three weeks ago moves every Fitness, Fatigue and Form number from that day
     * forward — including days the coach has already prescribed against. That is correct: the curves
     * are a live read of the truth, and the alternative is freezing numbers we know to be wrong.
     * Nothing warns and no past coaching is re-run. A Stage already graduated stays graduated.
     *
     * **The Run's own Stage evaluation is not re-run and cannot be**, which is the same rule a
     * Stated Distance is under (#231, ADR 0008) and is worth stating plainly because the mark
     * usually arrives *seconds* late rather than weeks: `finalizeRun` asks the coach at STOP, while
     * the sheet carrying this switch is still on screen. So the Run that has just finished is judged
     * as the Run it was when it ended — sent to the coach under its old label, and able to graduate
     * a Stage. What the mark buys is every evaluation *after* it, where
     * [AiTrainingContext.requirementEvidenceRunIdsByTimestamp] leaves it out.
     *
     * That is the safe side of a judgement made once and never taken back, but it is a real edge:
     * only a Run that followed a Workout the coach adjusts is evaluated at all, so it is reached by
     * starting a planned Long Run and then walking it — never by the post-lifting walk this ticket
     * was written for, which carries no Run Type and is never evaluated.
     *
     * Nothing changed writes nothing, so re-opening the dialog and pressing Save on the mark already
     * there costs neither a row update nor a walk of the record book.
     */
    suspend fun markAsWalk(
        sessionId: Long,
        isWalk: Boolean,
        finalizeWaitStepMillis: Long = 250L,
    ) {
        val session = awaitFinalized(sessionId, finalizeWaitStepMillis) ?: return
        if (session.isWalk == isWalk) return

        val write: suspend () -> Unit = { sessionDao.setIsWalk(sessionId, isWalk) }
        if (isWalk) {
            // Every record this Run holds, mended from all of history — `mendOnly` is left null
            // because a Walk stops contesting all of them at once, so there is no narrower list to
            // give than "whatever it was holding".
            Log.i("Walk", "Run $sessionId is a Walk; its records go back to the book")
            changeAndRepair(listOf(sessionId), change = write)
        } else {
            writeAndScore("Walk", sessionId, write) {
                "Run $sessionId is a Run again but could not be scored"
            }
        }
    }

    /**
     * States how far a treadmill Run went, or corrects a number already stated (#231).
     *
     * The runner reads it off the console after the Run; everything else here follows from it being
     * a distance like any other ([ADR 0008](docs/adr/0008-a-stated-distance-is-a-real-distance.md)).
     * Null withdraws one, which stores the same zero a Run nobody stated one for has always carried.
     *
     * **Only a treadmill Run**, which is what keeps this to one column and no migration: an outdoor
     * Run's distance is measured, and a Run whose GPS recorded nothing is not rescued this way. A
     * Run that is not one is refused rather than corrected quietly.
     *
     * **It waits for the Run to be finished**, because the sheet that asks for the number is on
     * screen from the moment STOP is pressed while `finalizeRun` is still writing the row — and it
     * writes the row whole, so a distance landing first would be overwritten by a zero seconds
     * later.
     *
     * **The record book is replayed, because scoring is a function of history** (ADR 0008). A first
     * statement or a correction upward is scored like any Run finishing. A correction *downward*
     * rebuilds the longest-distance record from all of history instead: only the top three are ever
     * banked, so the Run that should move up behind a demoted medal exists nowhere but in the
     * sessions, and re-scoring this Run alone cannot find it. That is the mend a deletion already
     * owes, which is why it goes through the same door.
     *
     * **A correction takes any stated Best Effort it has made impossible with it** (#282, ADR 0015),
     * and mends the records those held. A Run that says it went three kilometres cannot hold a five,
     * and a correction is the one way a claim that was true when it was made stops being one.
     *
     * **The Run's own Stage evaluation is not re-run and cannot be** — see
     * [evaluateAndAdjustPlan]. What a stated distance buys the coach is every evaluation after it.
     *
     * Either way the history backup is refreshed: the one `finalizeRun` took went out before the
     * number existed, and a Run restored from it would come back with the distance gone.
     */
    suspend fun stateDistance(
        sessionId: Long,
        distanceKm: Double?,
        finalizeWaitStepMillis: Long = 250L
    ) {
        if (distanceKm != null && (!distanceKm.isFinite() || distanceKm <= 0.0)) {
            Log.w("StatedDistance", "Refusing $distanceKm km for run $sessionId: not a distance")
            return
        }
        val session = awaitFinalized(sessionId, finalizeWaitStepMillis) ?: return
        if (!session.isTreadmill()) {
            Log.w("StatedDistance", "Refusing a stated distance for run $sessionId: it is not a treadmill Run")
            return
        }
        val stated = distanceKm ?: 0.0
        // Nothing to write, and so nothing to re-score or re-snapshot: re-opening the sheet and
        // pressing save on the number already there must not cost a walk of the record book.
        if (stated == session.distanceKm) return

        // Claims the Run can no longer contain (#282). A stated Best Effort is independent of the
        // distance — a Run nobody stated one for may hold any of them — but a Run that says it went
        // three kilometres cannot hold a five, and a correction is exactly how a claim that was
        // possible when it was made stops being one. The same act that makes it impossible takes it
        // away, because the alternative is a Medal standing on a claim nothing will ever look at
        // again. Nothing is orphaned by a distance being *withdrawn*: that leaves the Run with no
        // distance at all, which contradicts nothing.
        val orphaned = statedBestEffortDao?.getForSession(sessionId).orEmpty()
            .filterNot { it.type.fitsWithin(stated) }
            .map { it.type }

        val write: suspend () -> Unit = {
            sessionDao.setStatedDistance(
                sessionId = sessionId,
                distanceKm = stated,
                // The same clock the app quotes this Run's pace over (#163) — its own duration,
                // there being no moving time to measure without a track.
                avgPaceMinPerKm = averagePaceMinPerKm(session.paceClockSeconds, stated),
            )
            // Asked again here rather than reusing the list above, because this runs inside the
            // transaction that writes the distance and that one did not: a claim stated in the gap
            // between them would otherwise survive a correction that has just made it impossible.
            // What the earlier read settles is which records to *mend*, and that has to be known
            // before the change begins. A claim landing in the gap is therefore withdrawn but leaves
            // the record it held unmended — narrowed to a window one write wide, not closed, and the
            // next launch's seeding debt is not owed for it. Both doors are dialogs on one screen,
            // so reaching it means two statements from one finger in the same instant.
            statedBestEffortDao?.getForSession(sessionId).orEmpty()
                .filterNot { it.type.fitsWithin(stated) }
                .forEach { claim ->
                    Log.i(
                        "StatedDistance",
                        "Run $sessionId is $stated km, so its stated ${claim.type} goes with the correction"
                    )
                    statedBestEffortDao?.withdraw(sessionId, claim.type)
                }
        }

        val lowered = stated < session.distanceKm
        if (lowered || orphaned.isNotEmpty()) {
            // Every record this distance can no longer hold, and the longest distance where the
            // number came down. Asked of the *distance* rather than of the claims that happen to
            // stand right now, because the withdrawal above runs later and inside a transaction:
            // a claim stated in between is one this list has to have named already, and a list read
            // from the claims would leave that record demoted with no mend coming. Naming a record
            // nothing was withdrawn from costs a rebuild that arrives at the book already there.
            //
            // Not the whole book either: the duration is untouched, and the records this distance
            // still contains stand exactly as they were.
            val moved = RecordType.bestEffortDistances.filterNot { it.fitsWithin(stated) } +
                listOfNotNull(RecordType.LONGEST_DISTANCE.takeIf { lowered })
            changeAndRepair(listOf(sessionId), mendOnly = moved, change = write)
        } else {
            writeAndScore("StatedDistance", sessionId, write) {
                "Stated $stated km for run $sessionId but could not score it"
            }
        }
    }

    /** What a Run has been told it holds, as its own page watches it (#282). */
    fun statedBestEffortsFlow(sessionId: Long): Flow<List<StatedBestEffort>> =
        statedBestEffortDao?.getForSessionFlow(sessionId) ?: flowOf(emptyList())

    /**
     * States the time a treadmill's console showed for one of the record distances, corrects one
     * already stated, or takes it back (#282).
     *
     * The Stated Distance's twin, and deliberately the same shape
     * ([ADR 0015](docs/adr/0015-a-stated-best-effort-is-read-off-a-console-not-off-an-average.md)):
     * only a treadmill Run, only once the Run is finished, and the record book replayed afterwards.
     * Null [seconds] withdraws, leaving a Run nobody stated that distance for. What differs is that
     * a Run holds up to five of these — one per record distance — so each is stated and withdrawn on
     * its own, and mending touches only the record the claim was made at.
     *
     * **Three things are refused rather than stored.** Two of them are the rules that keep the app
     * from ever deciding a Best Effort for itself:
     * - *Not a treadmill Run.* An outdoor Run's efforts are measured, and one whose GPS recorded
     *   nothing is not rescued this way — the same refusal a Stated Distance makes, and what keeps
     *   any Run from holding a measured effort and a stated one at the same record.
     * - *Not one of the five.* The two totals are the Run's own numbers, already read off the row.
     * - *A claim the Run could not contain*: a time longer than the whole Run, or a distance longer
     *   than the Run's Stated Distance. That is not the app doubting the runner, which it does
     *   nowhere and must not start doing here — an implausible time is believed and corrected
     *   afterwards, exactly as an implausible distance is. What is refused is only the
     *   arithmetically impossible, which is not a claim about this Run at all.
     *
     * The distance check is skipped where the Run has no Stated Distance, because the two statements
     * are independent: a runner who noted the 5 km split and never looked at the total has still
     * said something true.
     *
     * **A claim made worse rebuilds; a claim made better re-scores.** A slower time — or a
     * withdrawal — can demote a Medal, and the Run that should move up behind it exists nowhere but
     * in history, since only the top three are ever banked. That is the mend a deletion already
     * owes, so it goes through the same door, narrowed to the one record that can have moved. Note
     * this runs the opposite way from a Stated Distance, where a *smaller* number is the worse
     * claim: the rule is the same and it is the direction of the record that flips, which is the one
     * thing [RecordType.lowerIsBetter] exists to say.
     *
     * Either way the history backup is refreshed, and the Run's own *coach* evaluation is not re-run
     * and cannot be — see [evaluateAndAdjustPlan] and ADR 0008.
     *
     * The app's own rule is asked again, though (#290), because a Stated Best Effort is a Best
     * Effort: a treadmill 5K is stated after the Run ends, so a rule that only ever looked at the
     * finish would accept a measured 5K and silently refuse a stated one — the app disagreeing with
     * itself, and with ADR 0015, which says a stated effort places in the record book exactly as a
     * measured one does. The same three edges apply as at the finish: the Run's own Stage must still
     * be the active one, so a claim typed weeks later about an old Run graduates nothing.
     */
    suspend fun stateBestEffort(
        sessionId: Long,
        type: RecordType,
        seconds: Int?,
        finalizeWaitStepMillis: Long = 250L,
    ) {
        val dao = statedBestEffortDao ?: return
        if (type.distanceMeters == null) {
            Log.w("StatedBestEffort", "Refusing $type for run $sessionId: it is not run over a distance")
            return
        }
        if (seconds != null && seconds <= 0) {
            Log.w("StatedBestEffort", "Refusing ${seconds}s at $type for run $sessionId: not a time")
            return
        }
        val session = awaitFinalized(sessionId, finalizeWaitStepMillis) ?: return
        if (!session.isTreadmill()) {
            Log.w("StatedBestEffort", "Refusing a stated Best Effort for run $sessionId: it is not a treadmill Run")
            return
        }
        if (seconds != null && !session.couldContain(type, seconds)) {
            Log.w("StatedBestEffort", "Refusing ${seconds}s at $type for run $sessionId: the Run does not contain it")
            return
        }

        val standing = dao.getForSession(sessionId).singleOrNull { it.type == type }?.seconds
        // Nothing to write, and so nothing to re-score or re-snapshot: re-opening the dialog and
        // pressing Save on the time already there must not cost a walk of the record book.
        if (standing == seconds) return

        val write: suspend () -> Unit = {
            inTransaction {
                // Asked again, here, inside the transaction that stores the claim — because the
                // check above was made against the Run as it stood before any of this began, and a
                // Stated Distance corrected downward in the meantime is exactly what turns a claim
                // that was possible into one that is not. Storing it is the moment the claim becomes
                // real, so storing it is where the Run has to be asked.
                //
                // This and the correction's own re-read are the two halves of one rule, and between
                // them the ordering no longer matters: the database takes one writer at a time, so
                // either the correction commits first and this refuses, or this commits first and
                // the correction withdraws it. There is no interleaving left in which an impossible
                // claim reaches the book.
                val now = seconds?.let { sessionDao.getSessionById(sessionId) }
                if (seconds != null && (now == null || !now.couldContain(type, seconds))) {
                    Log.w(
                        "StatedBestEffort",
                        "Refusing ${seconds}s at $type for run $sessionId: the Run no longer contains it"
                    )
                    return@inTransaction
                }
                if (seconds == null) dao.withdraw(sessionId, type)
                else dao.state(StatedBestEffort(sessionId = sessionId, type = type, seconds = seconds))
            }
        }

        // Withdrawn, or slower than what stood: either way this Run's claim at this record just got
        // worse, and a Medal may have to come off it.
        val worse = standing != null && (seconds == null || seconds > standing)
        if (worse) {
            changeAndRepair(listOf(sessionId), mendOnly = listOf(type), change = write)
        } else {
            writeAndScore("StatedBestEffort", sessionId, write) {
                "Stated ${seconds}s at $type for run $sessionId but could not score it"
            }
        }

        // A claim just stated or improved can answer a Stage requirement written in numbers (#290).
        // Only that direction: a withdrawal or a slower time takes nothing back, because a
        // graduation is never revoked. Asked of the Run as the write left it — the claim is read
        // back inside the rule — and against the Stage the Run was recorded under, which is null for
        // a Run that followed no plan.
        if (!worse) {
            sessionDao.getSessionById(sessionId)?.let { stored ->
                stored.ranUnderStageId?.let { graduateOnBestEffortRequirement(it, stored) }
            }
        }
    }

    /**
     * Whether this Run could hold a claim of [seconds] at [type] — the arithmetic, and no judgement
     * beyond it (#282).
     *
     * Two questions, and the Run has to answer both: it lasted long enough, and it went far enough
     * ([fitsWithin], which is where the distance half is argued).
     */
    private fun RunnerSession.couldContain(type: RecordType, seconds: Int): Boolean =
        seconds <= durationSeconds && type.fitsWithin(distanceKm)

    /**
     * Makes a change that can only improve what a Run is worth, scores it, and refreshes the
     * backup — leaving the Run owing a scoring if any of that cannot be finished (#282).
     *
     * The two doors are a Stated Distance corrected upward and a Best Effort stated or improved.
     * Neither can demote a Medal, so neither needs the mend [changeAndRepair] exists for; what they
     * do need is the debt, and one of them having it and the other not would be an accident of which
     * was written first.
     *
     * **The scoring mark is lifted before the change and handed back only once the book has taken
     * it.** A Run is marked scored once and never revisited ([scoreAndMarkRecords]), so a claim
     * stored against a Run already carrying the mark is a medal nobody goes back for the moment
     * anything after it ends short — the process reclaimed, the scope cancelled, the write throwing.
     * Lifted first, every one of those endings leaves the Run owing a scoring, which the next
     * launch's pass pays against a book nobody is moving. The cost of lifting it needlessly is one
     * redundant re-score arriving at the same book.
     *
     * The scoring is still its own attempt, as everywhere else the book is written: the change is
     * stored by that point, and a book that cannot be written must not read as a statement that did
     * not save.
     *
     * The backup goes out either way — the snapshot `finalizeRun` took went out before any of this
     * existed, and a Run restored from it would come back without it.
     */
    private suspend fun writeAndScore(
        tag: String,
        sessionId: Long,
        change: suspend () -> Unit,
        couldNotScore: () -> String,
    ) {
        sessionDao.clearRecordsScored(sessionId)
        change()
        try {
            scoreAndMarkRecords(sessionId)
        } catch (e: Exception) {
            Log.w(tag, couldNotScore(), e)
        }
        refreshHistoryBackup?.invoke()
    }

    /**
     * The Run once its totals have been written, or null when there is no such row.
     *
     * Both doors a runner can reach a finished Run through immediately — the feel sheet and a stated
     * distance — are open from the moment STOP is pressed, while `finalizeRun` is still assembling
     * the row it will write whole. Waiting is what stops those writes being overwritten a second
     * later by the finalize that was already in flight.
     *
     * A finalize that never lands is not a reason to throw the runner's input away: the write goes
     * ahead after the wait, which is the lesser of the two losses.
     */
    private suspend fun awaitFinalized(sessionId: Long, waitStepMillis: Long): RunnerSession? {
        repeat(20) {
            val session = sessionDao.getSessionById(sessionId) ?: return null
            if (session.isFinished()) return session
            kotlinx.coroutines.delay(waitStepMillis)
        }
        Log.w("SessionRepository", "Run $sessionId never finalized; writing to it anyway")
        return sessionDao.getSessionById(sessionId)
    }

    suspend fun deleteSessions(sessionIds: List<Long>) {
        if (sessionIds.isEmpty()) return
        deleteRuns(sessionIds) { sessionDao.deleteSessionsByIds(sessionIds) }
    }

    /**
     * A Run leaving history for good, and the coach's work about it going with it (#156).
     *
     * The two doors — one Run from its own page, a selection from the history screen — differ only in
     * the statement that removes the rows, so everything that has to happen around a delete is here
     * and neither can be given it and the other not.
     *
     * **Which Runs fed the coach is read before the rows go**, because afterwards there is nothing
     * left to ask. Only the Runs the coach was allowed to see are offered
     * ([SessionDao.getAiEligibleIdsIn]): a Run the runner kept out of AI training was never evidence
     * for anything, so deleting it must disturb no coaching at all.
     *
     * The work is taken back the moment the rows are gone — before the backup and before the record
     * book is mended, which takes minutes on a long history. Its own attempt, like every other write
     * around a delete: coaching that could not be taken back must not read as a delete that did not
     * happen, and the runner can delete again.
     *
     * **The window between the commit and the rollback is narrowed, not closed.** It used to be the
     * whole backup and the whole mend, and to end wherever the runner's scope was cancelled; it is
     * now one settings write, and cancellation does not reach it ([changeAndRepair]). What is left
     * is a process reclaimed inside that one write, which leaves the row gone and the coaching
     * standing on it with nothing to notice — there is no reconciling pass at startup, and building
     * one is a separate piece of work. Until there is, the runner's remedy is the one they have
     * always had: delete the Run again, or cycle testing mode.
     *
     * **The rows going and the coaching coming back are one act**, held together under
     * [coachingProvenance] so that a coach write cannot land between them and store a Prescription
     * naming a Run this delete has already taken out of history.
     */
    private suspend fun deleteRuns(runIds: List<Long>, delete: suspend () -> Unit) {
        val fedTheCoach = aiEligibleIdsAmong(runIds)
        changeAndRepair(
            runIds,
            onceRowsAreDurable = {
                try {
                    coachPrescriptionRepository?.forgetWorkFedBy(fedTheCoach)
                } catch (e: Exception) {
                    Log.w("AiCoach", "Deleted $runIds but could not take back the coaching", e)
                }
            },
            whileTheRowsGo = coachingProvenance,
            change = delete
        )
    }

    /**
     * Held while the Runs a Prescription stands on are being settled — either written down with a
     * new Prescription, or taken back with the Runs they named (#156).
     *
     * A delete reads which Runs fed the coach, removes the rows and rolls back whatever stood on
     * them; an evaluation reads its evidence, asks the coach, and stores the answer naming that
     * evidence. The two run on different scopes — the history screen's view model and the recording
     * service's finalization — so nothing but this puts them in an order. Without it a delete
     * landing after the evaluation's last look at history finds nothing standing to take back, and
     * the reply that arrives behind it writes down a Run that has already gone: provenance no later
     * delete can ever answer for, because the Run it names cannot be deleted twice.
     *
     * Held across the re-read *and* the write on the coach's side, because that is the check-and-act
     * a delete has to be kept out of, and across the rows going *and* the rollback on the delete's
     * side, for the same reason from the other direction. Never across the network round trip: the
     * coach takes seconds to answer and a history screen made to wait on it is a history screen that
     * does not respond. What the round trip costs is a reply that can be refused, which is the
     * refusal below.
     */
    private val coachingProvenance = Mutex()

    /**
     * Which of [runIds] the coach was allowed to be shown — asked in chunks, for the reason
     * [MAX_SESSION_IDS_PER_QUERY] gives.
     */
    private suspend fun aiEligibleIdsAmong(runIds: List<Long>): Set<Long> =
        runIds.chunked(MAX_SESSION_IDS_PER_QUERY)
            .flatMap { sessionDao.getAiEligibleIdsIn(it) }
            .toSet()

    /**
     * Whether every Run in [shownRunIds] is still in history — asked again, under
     * [coachingProvenance], before anything the coach's evidence is written down with (#156).
     *
     * **This asks the eligibility question and reads the answer as an existence one, which it is.**
     * Whether a Run may be shown to the coach is stamped on its row when START is pressed, out of
     * the AI-sharing setting in force at that moment (`HrForegroundService`), and nothing ever
     * writes to that column again: the only two writers of a finished Run's row are the finalize and
     * the rescue of an interrupted one, both of which copy the row forward, and there is no screen
     * anywhere that offers the runner a per-Run switch. So a Run that was eligible when it was
     * shown to the coach is eligible for as long as it exists, and the one way it can fall out of
     * this answer is by leaving history — which is why the log below is entitled to say "deleted".
     * The same query the delete itself asks, rather than a second existence one, so there is one
     * spelling of "the coach was allowed to see this Run".
     *
     * [refusing] names what is being turned away, in the words the log should use for it.
     */
    private suspend fun theEvidenceStillStands(shownRunIds: Set<Long>, refusing: String): Boolean {
        val stillInHistory = aiEligibleIdsAmong(shownRunIds.toList())
        val gone = shownRunIds - stillInHistory
        if (gone.isEmpty()) return true
        Log.d(
            "AiCoach",
            "Refusing $refusing: it was reasoned from runs deleted while it was being decided. " +
                "gone=$gone shown=$shownRunIds"
        )
        return false
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

    /**
     * What the coach is told, for a judgement about [stageId].
     *
     * [asFinalized] is the Run just finished, as its row stood when `finalizeRun` wrote it — passed
     * in rather than read back, and used in place of the stored row wherever it turns up in the last
     * three. A stated distance can land between the finalize and this read (the sheet asking for it
     * is on screen the whole time), and a Run must be judged on what it was when it ended: see
     * [evaluateAndAdjustPlan]. Null everywhere the context is asked for outside a finish.
     */
    suspend fun getAiTrainingContext(
        stageId: String,
        asFinalized: RunnerSession? = null,
        /** The zone the runner's calendar days are in — which day and week a Run falls in. */
        zone: ZoneId = ZoneId.systemDefault(),
        /** What day the curves are read through. The Run that prompted this has already landed. */
        today: LocalDate = LocalDate.now(zone),
        /**
         * The Stage's Workout of the kind of Run that finished — see [AiTrainingContext.stageWorkout].
         * Passed in rather than looked up here, because the caller has already resolved it to decide
         * whether to ask the coach at all, and two lookups are two things to disagree.
         */
        stageWorkout: WorkoutTemplate? = null,
    ): AiTrainingContext {
        val stage = TrainingPlanProvider
            .getAllPlans()
            .asSequence()
            .flatMap { it.stages.asSequence() }
            .firstOrNull { it.id == stageId }
            ?: throw IllegalArgumentException("Stage not found for id: $stageId")

        // The Stage's own Runs and no others, which is what a Stage is graduated on (#234) — see
        // [RunnerSession.ranUnderStageId].
        val storedRecentRuns = sessionDao.getLast3AiEligibleRunsOfStage(stageId)
        // The same Runs as they stand *now*: [asFinalized] is the Run that has just finished, which
        // the read above can have caught mid-write, and it stands in for one of these rows — it is
        // never another Run. Resolved once and read from twice below, so what the coach is shown and
        // what may graduate the Stage cannot describe different Runs (#275, #287).
        val recentSessions = storedRecentRuns.map { stored ->
            if (stored.id == asFinalized?.id) asFinalized else stored
        }
        val recentRuns = recentSessions.map { session ->
            // The label the coach sees for a past run: whether it followed a Workout at all.
            // Whether a run is *evaluated* is no longer this — that is its Run Type (#176) — but a
            // recorded run carries no Run Type of its own, so this stays the label.
            AiRecentRun(
                durationSeconds = session.durationSeconds,
                avgHr = session.avgBpm,
                // A Walk says so and says nothing else (#275): it is in the list, because a week of
                // walking is not a week of rest and a coach that could not see it would read one as
                // the other — but it did not complete the Workout, whatever structure it followed.
                sessionType = aiSessionTypeOf(session),
                timestamp = session.startTime,
                runMode = session.runMode,
                // Zero is a distance nobody stated or measured rather than a Run that covered no
                // ground, so it is sent as "unknown" and never as a nought the coach can reason
                // from. A treadmill Run's Stated Distance is sent exactly like a measured one
                // (#231) — that is the whole of what makes an indoor winter visible here.
                distanceKm = session.distanceKm.takeIf { it > 0 },
                // Measured from the stored track rather than read off the Run: nothing records where
                // the warm-up ended, so the fastest window is the only way to a 5K time the Phases
                // either side of it have not inflated (#182).
                //
                // Never for a Walk (#290). A Walk holds no Best Effort of any kind — CONTEXT.md
                // says so and the record book keeps to it — so a brisk 5 km walk measured here
                // would be the one place in the app that disagrees, and it would be the place a
                // Stage requirement is read from.
                fastest5kSeconds = if (session.isWalk) {
                    null
                } else {
                    measureFastestEffortSeconds(
                        points = getTrackPointsForMap(session.id),
                        targetMeters = FIVE_K_METERS
                    )
                }
            )
        }

        return AiTrainingContext(
            currentStageTitle = stage.title,
            graduationRequirement = stage.graduationRequirementText,
            requirementIsTheAppsToAnswer = stage.bestEffortRequirement != null,
            recentRuns = recentRuns,
            // The stored rows' ids, which [asFinalized] cannot change: it stands in for one of these
            // Runs, it is never another one.
            sourceRunIds = storedRecentRuns.map { it.id }.toSet(),
            // Asked of the same rows [recentRuns] was built from, so the two cannot describe
            // different Runs — including where [asFinalized] stands in for one, since the mark can
            // land on the Run that just finished before this is read.
            requirementEvidenceRunIdsByTimestamp = recentSessions
                // Keyed by the timestamp the coach is shown for the Run — `AiRecentRun.timestamp`,
                // which is this same field — so a reply naming one comes back to the Run it named
                // (#287). A timestamp two Runs share names neither: it is dropped rather than left
                // to whichever row happened to be written last, since a graduation granted on a
                // coin toss between two Runs is exactly the thing that cannot be taken back.
                //
                // Grouped across *every* Run shown before any is discarded, which is the order that
                // matters: a Walk sharing its start with a structured Run would otherwise be the
                // only one dropped, leaving the Run answering to a timestamp the coach wrote down
                // off the Walk — the exact substitution this whole check exists to refuse.
                .groupBy { it.startTime }
                .filterValues { sharingAStart -> sharingAStart.size == 1 }
                .mapValues { (_, sharingAStart) -> sharingAStart.single() }
                // The same three answers [aiSessionTypeOf] gives, asked as one question: only a
                // structured Run/Walk is evidence. The prompt says both halves of this — an Open
                // Run may not progress a Stage, a Walk may not either — and a prompt sentence is a
                // promise the code has to keep, because a graduation cannot be taken back.
                .filterValues { it.isRunWalkMode && !it.isWalk }
                .mapValues { (_, evidence) -> evidence.id },
            fitnessAndForm = fitnessAndFormThrough(
                today = today,
                zone = zone,
                // A Run that heard no beats earns no Score, so the curves never see it. Nor do they
                // see one dated after [today] — a clock corrected backwards mid-Run leaves a Run
                // stamped in the future, and progressCurve drops it rather than let it bend today.
                // Asked here the same way it is asked there, so the flag cannot claim a Run the
                // curves declined. True with no finalized Run at all: nothing ran, so there is
                // nothing missing from the numbers.
                todaysRunIsInTheNumbers = asFinalized == null || (
                    asFinalized.effortScore != null &&
                        !Instant.ofEpochMilli(asFinalized.startTime).atZone(zone).toLocalDate().isAfter(today)
                    )
            ),
            stageWorkout = stageWorkout
        )
    }

    /**
     * The runner's Fitness, Fatigue and Form, and the last [AI_WEEKS_OF_EFFORT] weeks of Effort
     * Score behind them — or null when no Run in history has a Score to build a curve from (#66).
     *
     * Worked out from the same reads and the same arithmetic the Progress screen uses, so the coach
     * and the runner are looking at one set of numbers rather than two that agree most of the time.
     *
     * The Run just finished is in this wherever it has a Score, which is written on the finish path
     * before the coach is asked anything. It moves Fitness and Fatigue and cannot move Form, which
     * is the pair as the day opened by design — freshness is a question asked before the day's
     * training had cost anything. A Run that recorded no heart rate has no Score and so is in none
     * of the three; [AiFitnessAndForm.todaysRunIsInTheNumbers] is how the coach is told.
     */
    private suspend fun fitnessAndFormThrough(
        today: LocalDate,
        zone: ZoneId,
        todaysRunIsInTheNumbers: Boolean,
    ): AiFitnessAndForm? {
        // The screen's own reads, taken once — the coach is asked its question at a moment, and
        // there is nothing on the far side of a sent prompt for a later emission to redraw.
        val curveToday = progressCurve(scoredRunsFlow().first(), through = today, zone = zone)
            .lastOrNull()
            ?: return null
        val weeks = weeklyVolumeOf(runVolumesFlow().first(), through = today, zone = zone)
        return AiFitnessAndForm(
            fitness = curveToday.fitness.roundToInt(),
            fatigue = curveToday.fatigue.roundToInt(),
            form = curveToday.form.roundToInt(),
            verdict = formVerdictOf(curveToday.form),
            weeklyEfforts = weeks.takeLast(AI_WEEKS_OF_EFFORT).map { it.forCoach() },
            todaysRunIsInTheNumbers = todaysRunIsInTheNumbers
        )
    }

    /**
     * Everything the Plan has to say about the Run just finished, in the order it has to be said
     * (#290).
     *
     * **The app answers first, and then the coach is asked.** That order is the whole of this
     * function and it is not an implementation detail: where a Long Run happens to contain a
     * qualifying 5K, both paths would otherwise have a view, and only one of them may grant. The
     * rule runs, grants, and moves the stored Stage on; [evaluateAndAdjustPlan] then reads the
     * settings fresh, sees the Stage it was called about is no longer the active one, and returns
     * without asking the coach anything — the guard written for a graduation landing mid-Run
     * (#234), catching this case exactly.
     *
     * So the coach is still called either way rather than skipped here. Skipping it would be a
     * second rule saying the same thing as that guard, free to drift from it; and on a Stage the
     * rule declined, the coach's debrief and Prescription are exactly what the runner is owed.
     *
     * [runType] and [finalizedRun] are passed straight through — see [evaluateAndAdjustPlan] for
     * what each is and why the Run is handed over rather than read back.
     */
    suspend fun settleStageAfterRun(
        stageId: String,
        runType: RunType?,
        finalizedRun: RunnerSession,
    ) {
        graduateOnBestEffortRequirement(stageId, finalizedRun)
        evaluateAndAdjustPlan(stageId, runType, finalizedRun)
    }

    /**
     * Graduates a Stage whose requirement is written in numbers, when the Run just finished answers
     * it (#290) — and does nothing at all otherwise. Returns whether it granted.
     *
     * The rule, in one sentence: **any finished Run not marked a Walk, whose Best Effort at the
     * requirement's distance clears its time, graduates the Stage.** Measured off the track or
     * stated off a treadmill console, and any kind of Run at all — an Open Run included, because a
     * parkrun is the truest 5K test there is and the number is the number wherever it turned up.
     * That is the narrowing this replaces: a *structural* requirement can only be answered by a Run
     * that followed a structure, and a time requirement is answered by a time.
     *
     * The three edges — not a Walk, not a Run still going, measured-or-stated — are not restated
     * here. They are [bestEffortsOf]'s, asked through [effortsAt], which is the same measurement the
     * record book ranks: a rule applied in one reader of a shared measurement is a bug waiting for
     * the second reader. It also means the Walk exclusion is not a filter this has to remember —
     * a Walk is worth no Best Effort at all, so it clears nothing.
     *
     * **No lock, and no re-read of the evidence.** The graduation the coach grants is wrapped in
     * [coachingProvenance] and re-checks [theEvidenceStillStands], because a Gemini round trip
     * leaves seconds in which the Run behind it can be deleted. There is no round trip here: the Run
     * exists, its effort clears, it grants. Copying that machinery across would be a guard around
     * nothing.
     *
     * **Forwards only, and never taken back.** No pass over history: a launch that silently jumped
     * the runner two Stages on evidence recorded under different rules is the highest-stakes version
     * of the one act that cannot be undone — the Stage card names an already-beaten bar instead
     * (#293). And deleting the Run afterwards, or marking it a Walk, does not un-graduate; CONTEXT.md
     * already says that of the Walk mark and the rule holds the same line for a delete.
     */
    private suspend fun graduateOnBestEffortRequirement(
        stageId: String,
        run: RunnerSession,
    ): Boolean {
        val settingsRepo = settingsRepository ?: return false
        val plan = TrainingPlanProvider
            .getAllPlans()
            .firstOrNull { candidate -> candidate.stages.any { it.id == stageId } }
            ?: return false
        val stageIndex = plan.stages.indexOfFirst { it.id == stageId }
        val stage = plan.stages[stageIndex]
        // The Stage's requirement is a judgement, so it stays the coach's — stage 1's "4 weeks of
        // consistent Zone 2 training" is met by no measurement this could take.
        val requirement = stage.bestEffortRequirement ?: return false

        val settings = settingsRepo.userSettingsFlow.first()
        // Testing mode erases the coach's work and blocks it from writing more; a Stage advanced
        // under it would be the one write from a desk test that outlives the desk test.
        if (settings.testingModeEnabled) return false
        // The Run was recorded under a Stage the runner has since left (#234): its evidence belongs
        // to that Stage and answers nothing about this one. The same guard [evaluateAndAdjustPlan]
        // makes, for the same reason.
        if (stageId != settings.activeStageId) return false
        // A Run the runner has taken out of their training answers no requirement either — the same
        // switch that keeps it away from the coach.
        if (!run.includeInAiTraining) return false

        val seconds = effortsAt(
            session = run,
            types = listOf(requirement.record),
            stated = statedEffortsOf(run.id),
        ).firstOrNull { it.type == requirement.record }?.value ?: return false

        if (seconds > requirement.withinSeconds) {
            Log.d(
                "StageRule",
                "Run ${run.id} is worth ${seconds.toLong()}s at ${requirement.record}, which does not " +
                    "clear ${requirement.withinSeconds}s for stage=$stageId"
            )
            return false
        }

        val nextStage = plan.stages.getOrNull(stageIndex + 1)
        val scope = CoachWriteScope(settings.activePlanId, settings.activeStageId)
        // Written by the app and not by the coach. This decision is already made, and handing a
        // made decision to a model is inviting it to editorialise its way into disagreeing with a
        // fact; it also means the graduation still lands offline and with no Gemini key.
        settingsRepo.setLatestCoachMessage(
            graduationMessage(stage.title, requirement, seconds, nextStage?.title),
            scope
        )
        if (nextStage == null) {
            // The last Stage of the plan. Nothing to advance to, and the Prescription is deliberately
            // left standing: clearing it here would delete the runner's standing numbers and leave
            // them in a Stage that never moved, which is the bug #294 exists to fix. The
            // congratulation is the part that is true today, so it is the part that is written.
            Log.i(
                "StageRule",
                "Run ${run.id} answers the requirement of the plan's last stage=$stageId; " +
                    "there is no stage to advance to (#294)"
            )
            return true
        }
        settingsRepo.advanceStageAndClearPrescriptions(nextStage.id, scope)
        Log.i(
            "StageRule",
            "Run ${run.id} is worth ${seconds.toLong()}s at ${requirement.record} and graduates " +
                "stage=$stageId to stage=${nextStage.id}"
        )
        return true
    }

    /**
     * What the runner is told when the app grants a graduation: the number they ran, and what it
     * just finished (#290).
     *
     * The time is theirs — the Best Effort as measured or as stated — and not the bar they cleared,
     * because "you ran 5 km in 27:12" is the fact and "under 30 minutes" is only what it was enough
     * for.
     */
    private fun graduationMessage(
        stageTitle: String,
        requirement: BestEffortRequirement,
        seconds: Double,
        nextStageTitle: String?,
    ): String {
        val whole = seconds.roundToInt().coerceAtLeast(0)
        val clock = "%d:%02d".format(whole / 60, whole % 60)
        val distance = requirement.record.label.removePrefix("Fastest ")
        return buildString {
            append("You ran $distance in $clock. $stageTitle complete.")
            if (nextStageTitle != null) append(" Next up: $nextStageTitle.")
        }
    }

    /**
     * Ask the coach about the Run just finished, and write what it says down.
     *
     * [runType] is the kind of Run that finished, taken from the Workout it followed — null for a Run
     * that followed none. It is the gate (#176): only a Long Run is evaluated, so an Easy or Quality
     * Run returns here before the coach is asked anything, and no Prescription slot of theirs is ever
     * written. Both are still recorded in full and still count toward the 30-day load; those happen
     * on the way in, before this is called.
     *
     * [finalizedRun] is the Run just finished, as its row stood at the finish. **A distance stated
     * after that does not join this judgement** (#231, ADR 0008): the sheet asking for one is on
     * screen while this is still in flight, and this reads the last three Runs out of the database
     * on its way to the coach — so without the row, a number typed quickly enough would be judged
     * as though it had been there all along. That is the one case where a typo could graduate a
     * Stage, and a graduation cannot be taken back. A Run's own Stage evaluation is not replayed
     * when a distance arrives later, for the same reason: it is a judgement made once, about one
     * Run, under the Stage in force at that moment — which the Run now writes down for itself
     * ([RunnerSession.ranUnderStageId], #234), so that it can be shown to that Stage and to no
     * other, though replaying the judgement is still not something that happens. What a stated
     * distance buys the coach is every evaluation *after* it — which is where an indoor winter was
     * going missing.
     */
    suspend fun evaluateAndAdjustPlan(
        stageId: String,
        runType: RunType?,
        finalizedRun: RunnerSession? = null,
    ) {
        val settingsRepo = settingsRepository ?: return
        val coachClient = aiCoachClient ?: return
        if (runType == null || !runType.isCoachAdjusted) {
            Log.d(
                "AiCoach",
                "Skipping AI evaluation: a ${runType ?: "plan-less"} run is not one the coach adjusts. stageId=$stageId"
            )
            return
        }

        try {
            val settings = settingsRepo.userSettingsFlow.first()
            if (settings.testingModeEnabled) {
                Log.d("AiCoach", "Skipping AI evaluation: testing mode enabled")
                return
            }
            // The Run was recorded under a Stage the runner has since left (#234) — which happens
            // when an earlier Run's evaluation graduated the plan while this one was still going.
            // Nothing here can be done with it: its evidence belongs to the old Stage, so it cannot
            // answer the new one's requirement, and a verdict on the old one could only graduate a
            // Stage the runner is no longer in or prescribe into a Stage this Run never ran.
            //
            // Both sides are the Stage the runner was in, resolved against the plan — the Run's
            // was stamped from this same setting at START — so this catches a plan that moved and
            // never a plan that merely never named its Stage.
            if (stageId != settings.activeStageId) {
                Log.d(
                    "AiCoach",
                    "Skipping AI evaluation: the run was recorded under stage=$stageId and the " +
                        "plan has since moved to stage=${settings.activeStageId}"
                )
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
            // Warm-up/cool-down now live on the workout (#107); the load clamp accounts for the
            // envelope so the estimated total stays comparable to real sessions. The Workout is the
            // Stage's own one of this Run's kind (#176) — both the envelope and the floor below are
            // about the session the coach is prescribing, which is the kind that just finished.
            //
            // Resolved before the coach is asked anything, because its absence is the same "not a
            // Run I understand" answer as the Run Type gate above. The Run followed a Workout of
            // this kind, so a missing one means the plan moved on: detached, or moved to a stage
            // that offers no Workout of this Run Type, as stage 3 offers no Long run. A graduation
            // landing mid-Run is caught above, by the Stage the Run was recorded under (#234); what
            // is left for this to catch is the runner changing plan or stage themselves. Asking
            // anyway would prescribe into a stage this Run was never run under.
            val stageWorkoutOfKind = TrainingPlanProvider.resolveWorkoutOfType(
                settings.activePlanId,
                settings.activeStageId,
                runType
            )
            if (stageWorkoutOfKind == null) {
                Log.d(
                    "AiCoach",
                    "Skipping AI evaluation: no $runType workout is attached to judge this run against. " +
                        "planId=${settings.activePlanId} stageId=${settings.activeStageId}"
                )
                return
            }
            Log.d("AiCoach", "Starting AI evaluation of a $runType run for stage: $stageId")
            val context = getAiTrainingContext(
                stageId,
                asFinalized = finalizedRun,
                stageWorkout = stageWorkoutOfKind
            )
            Log.d("AiCoach", "Sending prompt to Gemini with ${context.recentRuns.size} recent runs.")
            val response = coachClient.evaluateProgress(context)
            if (response == null) {
                // No new prescription is written — see evaluateProgress. But a prescription already
                // standing keeps overriding the Stage's workout for up to 14 days, so on a fatigued
                // runner an unreachable coach would hand them exactly the harder intervals the hold
                // exists to take away (#248). The hold does not need the coach: it is read off the
                // training state, which was measured here.
                Log.d("AiCoach", "No new prescription: the coach could not be reached. stageId=$stageId")
                holdStandingPrescriptionAtWorkout(
                    runType = runType,
                    workout = stageWorkoutOfKind,
                    fitnessAndForm = context.fitnessAndForm,
                    shownRunIds = context.sourceRunIds,
                    scope = CoachWriteScope(settings.activePlanId, settings.activeStageId)
                )
                return
            }
            // Ceiling first, then floor: the floor wins where they disagree (#170). The ceiling is
            // measured against recorded runs, so a run cut short drags it below the plan — the
            // stage's own workout is the commitment and outranks that.
            //
            // The hold is applied last and outranks both (#248), because it is not a bound on the
            // three numbers but a statement of what they are: on a fatigued runner they are the
            // workout's own, so there is nothing left for a floor or a ceiling to have a view on.
            // Read from the state the coach was shown rather than a second read of the curves — the
            // rule the runner is held to has to be the one the coach was asked to follow.
            val clampedResponse = holdAiResponseAtWorkout(
                floorAiResponseAtWorkout(
                    clampAiResponseByRecentLoad(
                        response,
                        warmUpSeconds = stageWorkoutOfKind.warmUpSeconds,
                        coolDownSeconds = stageWorkoutOfKind.coolDownSeconds
                    ),
                    stageWorkoutOfKind
                ),
                stageWorkoutOfKind,
                context.fitnessAndForm
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

            // A Stage is graduated on its own Runs, and with none of them there is nothing to
            // graduate it on (#234). The coach is told this in as many words, but a graduation
            // cannot be taken back, so the one place it is acted on refuses it outright rather than
            // trusting the telling — and the coach's message still reaches the runner either way.
            //
            // Neither is a Walk (#275), for the same reason it completes no prescribed workout: a
            // week of post-lifting walks must not push the plan forward. Nor is an unplanned Open
            // Run, which followed no structure to complete.
            //
            // And the question is not "was there a structured Run" but "was *this* the Run" (#287).
            // Non-emptiness is not a link: shown one old structured Run that plainly failed the
            // requirement beside a two-hour Walk, the coach can read the requirement as met from
            // the Walk's numbers, and a check that only asked whether a qualifying Run existed
            // would grant it — one eligible Run switching the guard off for everything shown beside
            // it. So the coach names the Runs it graduated on and the names are resolved here: all
            // of them have to be Runs this Stage could actually be graduated on, or there is no
            // graduation.
            //
            // Several Runs may be named, because several is what some requirements take — "4 weeks
            // of consistent Zone 2 training" is met by no single Run, and a rule demanding one
            // would leave the plan's first stage impossible to finish. Naming more of them does not
            // loosen anything: each name still has to resolve, and one Walk among them refuses the
            // lot.
            // And where the Stage's requirement is written in numbers, the coach may not graduate at
            // all (#290) — the app has already answered it, before this was called, and two paths
            // able to grant the same graduation is one of them granting it twice. The prompt tells
            // the coach this in as many words; a prompt sentence is a promise the code has to keep.
            val evidenceRunIds = context.evidenceRunIdsNamedBy(clampedResponse)
            val graduated = clampedResponse.graduatedToNextStage &&
                !context.requirementIsTheAppsToAnswer &&
                evidenceRunIds != null
            if (clampedResponse.graduatedToNextStage && context.requirementIsTheAppsToAnswer) {
                Log.d(
                    "AiCoach",
                    "Refusing a graduation: stage=$stageId states its requirement in numbers, so the " +
                        "app answers it and the coach does not"
                )
            } else if (clampedResponse.graduatedToNextStage && !graduated) {
                Log.d(
                    "AiCoach",
                    "Refusing a graduation: the coach named runs at " +
                        "${clampedResponse.graduationEvidenceRunTimestamps} as its evidence, and not all " +
                        "of them are runs recorded under stage=$stageId that could answer the " +
                        "requirement (${context.recentRuns.size} recent, " +
                        "${context.requirementEvidenceRunIdsByTimestamp.size} of them able to answer it)"
                )
            }

            if (graduated) {
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
                // could start on the new stage carrying the old one's numbers. So the debrief is
                // written on its own here, which is the one path where it stands without numbers —
                // "you have finished this stage" is the whole of what the coach had to say.
                //
                // The third ending of an evaluation, and the one that can least afford to be got
                // wrong. It rests on exactly the same three Runs the reply and the hold rest on —
                // the one read of the Stage's last three — and most of a Stage's requirement is
                // answered by a single Run or a pair of them, so one Run leaving history can take
                // the whole basis with it. Asked again under [coachingProvenance] for the reason
                // the other two are: a delete landing during the round trip has already decided
                // what stands, and a graduation written behind it stands on a Run nobody has.
                //
                // Worse than a Prescription written on evidence that has gone, because there is
                // nothing to take it back with. A Prescription records the Runs it stood on and a
                // later delete unwinds it; a graduation records nothing and only ever writes
                // forward ([SettingsRepository.advanceStageAndClearPrescriptions]), so a graduation
                // granted wrongly is granted for good. Refused whole on a partial delete too — one
                // of the three gone is enough — which is the direction the app already errs in:
                // graduating late rather than twice ([RunnerSession.ranUnderStageId]).
                //
                // Asked of all three rather than of [evidenceRunIds] alone, which is stricter and
                // deliberately so (#287): the Runs the coach named are among these three, so a
                // delete taking one of *them* away is refused here either way, and a delete taking
                // one of the others away still empties the history the requirement's "consistently"
                // was read against.
                //
                // The message goes with it, and is not written on its own: "you have finished this
                // stage" is not true if the Run that finished it has gone. Left behind on a refused
                // graduation it would be a debrief about a Stage the runner is still in, standing
                // with nothing under it and nothing to take it back either.
                coachingProvenance.withLock {
                    if (!theEvidenceStillStands(context.sourceRunIds, refusing = "the graduation")) {
                        return
                    }
                    settingsRepo.setLatestCoachMessage(clampedResponse.coachMessage, scope)
                    settingsRepo.advanceStageAndClearPrescriptions(nextStageId, scope)
                }
            } else {
                // The numbers, the debrief that explains them, and the Runs they were reasoned from,
                // in one write (#156). Stored apart, a delete could take the numbers back and leave
                // the text — which is the shape of the bug this closes. So the debrief goes nowhere
                // without a store to put the numbers in either: with no prescription store wired
                // (tests, and any container assembled without one) there is nothing for it to
                // explain.
                //
                // The evidence is asked for again first, and under [coachingProvenance], because the
                // round trip above takes seconds and the runner can spend them on the history
                // screen. A delete landing in there rolls back whatever stood at the time — which is
                // the coach's *previous* answer, not this one — so a Prescription written afterwards
                // naming the Run that went would stand on it for good: no later delete can take it
                // back, because a Run cannot be deleted twice.
                //
                // Refused whole where any of the evidence has gone, numbers and debrief together,
                // exactly as `editCoachWrite` refuses a write whose plan or stage moved. A reply
                // reasoned from three Runs is not two thirds right with one of them thrown away, and
                // the debrief explains numbers that are not being written.
                coachingProvenance.withLock {
                    if (!theEvidenceStillStands(context.sourceRunIds, refusing = "the coach's reply")) {
                        return
                    }
                    coachPrescriptionRepository?.prescribe(
                        // The kind of Run just finished, which is the kind the Workout above is of.
                        runType = runType,
                        prescription = CoachPrescription(
                            targetZone = coachTargetZone(
                                requested = clampedResponse.nextTargetZone,
                                workoutTargetZone = stageWorkoutOfKind.targetZone,
                                settingsTargetZone = settings.targetZone
                            ),
                            runDurationSeconds = clampedResponse.nextRunDurationSeconds,
                            walkDurationSeconds = clampedResponse.nextWalkDurationSeconds,
                            totalRepeats = clampedResponse.nextRepeats,
                            prescribedAtEpochMillis = System.currentTimeMillis()
                        ),
                        debrief = clampedResponse.coachMessage,
                        sourceRunIds = context.sourceRunIds,
                        scope = scope
                    )
                }
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

        return response.atIntervalsOf(workout)
    }

    /**
     * A runner carrying more than they have absorbed gets the Stage's own Workout, whatever the
     * coach returned (#248).
     *
     * The coach is asked to hold when Fatigue is above Fitness (#66), and asking was all there was:
     * the floor below refuses a Prescription *under* the Workout and the 110% ceiling caps it
     * against recorded load, leaving a wide band in which a tired runner could be handed more work
     * than the plan asks for — under a debrief saying this is not a week to be adding work to. This
     * makes the hold a rule of the app rather than an instruction a model may or may not follow.
     *
     * Held *at* the Workout rather than under it, because there is no lighter day to prescribe: the
     * floor is the same three numbers, so easing below them is not a thing this app can express. So
     * the hold is the Workout's numbers whole — the same move the floor makes, for the same reason
     * that a half-held Prescription would be a shape nobody asked for.
     *
     * Fatigue above Fitness is the reading, not [AiFitnessAndForm.verdict] — the two disagree, and
     * on purpose. The verdict is Form's band, which is where the day *started*, while the pair is
     * where it stands now; a runner can finish a hard Run carrying more than they have absorbed and
     * still print "neutral". The pair is the line the coach is given, so it is the line held to.
     * Read off the same rounded whole numbers the coach was shown, and level is absorbed in both
     * places.
     *
     * The target zone and the message are left alone: this is how much work, not how hard, and not
     * what was said. What the coach may claim about either is fenced in the prompt.
     *
     * Nothing to read is nothing to hold on — no [fitnessAndForm] is a runner with no scored history
     * at all, who was told nothing about fatigue either.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun holdAiResponseAtWorkout(
        response: AiCoachResponse,
        workout: WorkoutTemplate,
        fitnessAndForm: AiFitnessAndForm?
    ): AiCoachResponse {
        if (fitnessAndForm == null) return response
        if (fitnessAndForm.fatigue <= fitnessAndForm.fitness) return response

        return response.atIntervalsOf(workout)
    }

    /**
     * The same hold, applied to the Prescription already standing when the coach cannot be reached
     * (#248).
     *
     * A failed evaluation writes nothing, which is right for a *judgement* — nothing new was
     * learned about the runner. But a Prescription stands for up to 14 days
     * ([COACH_PRESCRIPTION_MAX_AGE_DAYS]) and overrides the Stage's Workout for every Run of its
     * kind in that window, so silence is not neutral: an earlier, harder Prescription would carry
     * the runner straight through the day the hold exists for. The hold is not the coach's opinion —
     * it is read off Fitness and Fatigue, which were measured on this side before the round trip —
     * so it can be applied without a reply.
     *
     * Only the three interval numbers change. The stamp is kept as it was: this pares an existing
     * Prescription back, it does not make a new one, and re-stamping would quietly extend the life
     * of numbers the coach wrote a fortnight ago. The target zone is kept for the reason the hold
     * always keeps it — how much work, not how hard.
     *
     * The standing debrief is left as written, as it is on every other held or clamped path: the
     * floor and the ceiling already change the numbers after the coach has described them, so a
     * debrief is a note about a Run, not a caption on three integers.
     *
     * Nothing standing means the plan runs as written, which is the Workout — already where the hold
     * would put it. A stale one is already ignored by whoever runs the workout, and rewriting it
     * would say the app had done something on a day it had not.
     *
     * **Under [coachingProvenance], and refused whole on evidence that has gone, exactly as a
     * written Prescription is.** This is a read of what stands followed by a write over it, and the
     * amend goes through `editCoachWrite`, which answers for the plan and the stage and nothing
     * else — so a delete landing between the two would roll the slot back to the coach's previous
     * Prescription and have this write last week's numbers straight over it, under a provenance
     * naming Runs those numbers were never reasoned from. That is precisely the debrief and the
     * numbers coming apart that ADR 0013 forbids, and #248's "the hold keeps its debrief, its date
     * and its provenance" with the provenance made a lie.
     *
     * [shownRunIds] is the evidence this whole evaluation was reasoned from — the same three Runs a
     * reply would have named. The hold is a smaller act than a Prescription but it is the same act:
     * it says the standing coaching is still the runner's coaching, only quieter. Once one of the
     * Runs behind it has left history that is no longer something this evaluation is in a position
     * to say, and the delete has already decided what stands. Refused whole rather than applied to
     * whatever the delete left, for the reason the reply is: the hold is not two thirds right with
     * one of the Runs it was measured against thrown away.
     */
    private suspend fun holdStandingPrescriptionAtWorkout(
        runType: RunType,
        workout: WorkoutTemplate,
        fitnessAndForm: AiFitnessAndForm?,
        shownRunIds: Set<Long>,
        scope: CoachWriteScope
    ) {
        val prescriptions = coachPrescriptionRepository ?: return
        if (fitnessAndForm == null) return
        if (fitnessAndForm.fatigue <= fitnessAndForm.fitness) return

        // Held across the read of what stands and the write over it, which is the check-and-act a
        // delete has to be kept out of. Nothing slow is inside it: the round trip that could not be
        // reached is already over, and everything left is storage.
        coachingProvenance.withLock {
            if (!theEvidenceStillStands(shownRunIds, refusing = "the hold")) return

            val standing = prescriptions.prescriptionsFlow.first()[runType] ?: return
            if (!standing.isFreshAt(System.currentTimeMillis())) return
            val held = standing.copy(
                runDurationSeconds = workout.runDurationSeconds,
                walkDurationSeconds = workout.walkDurationSeconds,
                totalRepeats = workout.totalRepeats
            )
            if (held == standing) return

            Log.d(
                "AiCoach",
                "Holding the standing $runType prescription at the stage workout: " +
                    "${held.runDurationSeconds}s Run / ${held.walkDurationSeconds}s Walk " +
                    "x${held.totalRepeats}"
            )
            // Amended rather than prescribed: this is the standing Prescription said again more
            // quietly, so it keeps its debrief, its date and the Runs it stood on (#156).
            prescriptions.amendStanding(runType = runType, prescription = held, scope = scope)
        }
    }

    /**
     * The Workout's three numbers put on a response whole, which is what both the floor (#170) and
     * the hold (#248) do when they refuse the coach's own — one move, so the two rules cannot end up
     * disagreeing about what "the Workout's intervals" are.
     *
     * The target zone is not among them: neither rule has a view on how hard the Run is.
     */
    private fun AiCoachResponse.atIntervalsOf(workout: WorkoutTemplate): AiCoachResponse =
        copy(
            nextRunDurationSeconds = workout.runDurationSeconds,
            nextWalkDurationSeconds = workout.walkDurationSeconds,
            nextRepeats = workout.totalRepeats
        )

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

/** [act] under this lock where there is one to take, and plainly where there is not. */
private suspend fun Mutex?.holding(act: suspend () -> Unit) {
    if (this == null) act() else withLock { act() }
}
