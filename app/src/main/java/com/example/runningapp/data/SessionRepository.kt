package com.example.runningapp.data

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.runningapp.BestEffortRequirement
import com.example.runningapp.CoachPrescription
import com.example.runningapp.CoachPrescriptionRepository
import com.example.runningapp.COACH_PRESCRIPTION_MAX_AGE_DAYS
import com.example.runningapp.CoachWriteScope
import com.example.runningapp.DebriefAuthor
import com.example.runningapp.isFreshAt
import com.example.runningapp.startingNow
import com.example.runningapp.HrZone
import com.example.runningapp.RunType
import com.example.runningapp.SettingsRepository
import com.example.runningapp.StatedHeartRates
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.PlanTest
import com.example.runningapp.WorkoutTemplate
import com.example.runningapp.clearedBy
import com.example.runningapp.distanceLabel
import com.example.runningapp.testWorkout
import com.example.runningapp.isCoachAdjusted
import com.example.runningapp.HrProfile
import com.example.runningapp.effectiveMaxHr
import com.example.runningapp.historyHrProfile
import com.example.runningapp.hrProfile
import com.example.runningapp.tallyZoneSeconds
import com.example.runningapp.ranOn
import com.example.runningapp.training.HistoryBestEffort
import com.example.runningapp.training.PlanCompletion
import com.example.runningapp.training.ScoredRun
import com.example.runningapp.training.asClock
import com.example.runningapp.training.TrainingWeek
import com.example.runningapp.training.VolumeRun
import com.example.runningapp.training.effortScoreOf
import com.example.runningapp.training.goalAmountText
import com.example.runningapp.training.goalProgressOf
import com.example.runningapp.training.FormVerdict
import com.example.runningapp.training.formVerdictOf
import com.example.runningapp.training.testIsDue
import com.example.runningapp.training.wasRunFarEnough
import com.example.runningapp.training.progressCurve
import com.example.runningapp.training.weeklyVolumeOf
import com.example.runningapp.analysis.BestEffort
import com.example.runningapp.analysis.RecordType
import com.example.runningapp.analysis.RouteThumbnail
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.RoutePolyline
import com.example.runningapp.analysis.routeThumbnailOf
import com.example.runningapp.analysis.RunEfforts
import com.example.runningapp.analysis.recordBookOf
import com.example.runningapp.analysis.standingsAfter
import com.example.runningapp.analysis.bestEffortsOf
import com.example.runningapp.segments.RunShape
import com.example.runningapp.segments.RunShapeStore
import com.example.runningapp.segments.RunShaping
import com.example.runningapp.segments.SegmentTiming
import com.example.runningapp.segments.SegmentTimingStore
import com.example.runningapp.segments.shapesAs
import com.example.runningapp.recording.SessionRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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
    val fastest5kSeconds: Long?,
    /**
     * How hard the Run felt to the runner, 1–10, off the "How did that feel?" sheet (#78, #83).
     *
     * Null where they never said, which is every Run the sheet was walked past and every Run
     * recorded before there was one. Never a nought and never a middling 5 stood in for them: a Run
     * nobody rated is a Run nobody rated, and a coach handed a number would weigh it exactly as
     * hard as one the runner actually gave.
     *
     * Context and nothing more. It is deliberately unrelated to [com.example.runningapp.data.RunnerSession.effortScore],
     * which is what the Run cost measured beat by beat — the prompt says so, because a coach reading
     * a 9 as a training load would prescribe against a number the app never measured.
     */
    val perceivedEffort: Int? = null,
    /**
     * What the runner wrote about the Run, word for word (#78, #83).
     *
     * Verbatim because the whole value of it is the words they chose — "legs like lead" and "felt
     * flat" are the runner's own reading of a Run whose numbers may look identical. Null where they
     * wrote nothing, blank included: an empty note is the absence of a note, not a note saying
     * nothing.
     *
     * Quoted back to the coach as the runner's words and fenced as such in the prompt. A note is
     * the one field here whose text a person chooses freely, and the coach's answer moves the
     * stored plan — so it is read as how the Run went, never as something addressed to the coach.
     *
     * Bounded, and always built by [noteForCoach]: what the runner typed is stored whole and the
     * run detail page shows all of it, but the copy sent to the coach is cut at
     * [MAX_COACH_NOTE_CHARS] with an ellipsis on the end.
     */
    val note: String? = null,
    /**
     * The weather the Run was run in, as one line (#79, #83) — see [weatherSummaryOf].
     *
     * Null where none was recorded: every treadmill Run, every Run with no GPS fix to place, and
     * any outdoor Run the fetch never reached. A slow hour into a headwind reads fairly only if the
     * headwind is in front of the coach.
     */
    val weather: String? = null
)

/**
 * How much of a runner's note the coach is sent (#83).
 *
 * Six hundred characters is around a hundred words — several sentences longer than anything anybody
 * writes about a single Run, so in practice no real note is touched by this at all. Nothing stops a
 * runner pasting far more than that: the note field takes whatever is typed into it and the column
 * holds it, and three of those go into one request alongside the whole of the rest of the training
 * context. A bound is here because the failure without one is silent — an oversized request is
 * refused by the model, `evaluateProgress` catches that like any other API failure, and the runner
 * gets no debrief and no plan adjustment with nothing on screen to say why. Losing the tail of an
 * essay is the smaller loss.
 */
const val MAX_COACH_NOTE_CHARS = 600

/**
 * A stored note as the coach is sent it — the whole of it where it fits, and its first
 * [MAX_COACH_NOTE_CHARS] characters where it does not.
 *
 * Cut here, at the prompt, and never at the write: what the runner typed is theirs and the run
 * detail page still shows every word of it. This is the one place [AiRecentRun.note] is built, so a
 * second reader of the column cannot send an unbounded one.
 *
 * A cut note ends in an ellipsis, so the coach can tell it was cut. Without the mark the model reads
 * a sentence that stops mid-word as the runner's whole thought, and a note trailing off at "the last
 * mile felt" would be answered as if that were all they had to say.
 *
 * Null for a note nobody wrote, blank included: the finish sheet leaves the column null when it is
 * walked past, but the edit path writes a runner's cleared note through (#80), so the emptiness can
 * arrive either way and is answered once, here.
 */
fun noteForCoach(stored: String?): String? {
    val written = stored?.takeIf { it.isNotBlank() } ?: return null
    return if (written.length <= MAX_COACH_NOTE_CHARS) {
        written
    } else {
        written.take(MAX_COACH_NOTE_CHARS) + "…"
    }
}

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

/**
 * One of the runner's own Goals, and where they stand against it, as the coach is told it (#83).
 *
 * The period the runner is in now and no other — a Goal card is about the week they are having, and
 * last week's is over ([goalProgressOf]).
 *
 * [done] and [target] are text rather than numbers, and rounded here by the same [goalAmountText]
 * the Goals card rounds by. The coach and the runner are then reading one pair of numbers rather
 * than two that agree most of the time: a runner looking at "24 / 40 km" must not be told by the
 * coach they are 15.7 km short.
 */
data class AiGoal(
    /** How the runner says the period — "This week", "This month", "This year". */
    val period: String,
    /** What the Goal is counted in — "Distance", "Time", "Runs". */
    val metric: String,
    val done: String,
    val target: String,
    /** The word after the numbers — "km", "hours", "runs". */
    val unit: String
) {
    /**
     * The Goal on one line, as the prompt writes it: "This week — Distance: 24 of 40 km".
     *
     * The metric is named as well as its unit, because the two are not the same fact and the spec
     * asks for both: "runs" is a unit that happens to name its metric, while "hours" is the unit of
     * a Goal about Time and reads on its own as a Goal about hours of something unsaid.
     */
    internal fun forPrompt(): String = "$period — $metric: $done of $target $unit"
}

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
    /**
     * Whether the runner has already finished this Stage's whole Plan (#294).
     *
     * Told to the coach because otherwise it is told forever that the runner is in "Stage 3: Sub-25
     * Peak — run a 5K in 24:59 or faster", and it will keep coaching them toward a time they have
     * already run. The Stage is still theirs and still has Workouts; it is no longer something to
     * achieve.
     *
     * True on the finished Plan's **last** Stage and on no other, because that is the Stage the
     * completion is about: an earlier Stage of the same plan, which a runner who re-attached the
     * plan would be in, has a Stage after it and is not the end of anything.
     */
    val planComplete: Boolean = false,
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
    val stageWorkout: WorkoutTemplate? = null,
    /**
     * The runner's own standing Goals and where they stand against them (#82, #83), in the order
     * they were set. Empty where they have set none, and the prompt then says nothing about goals
     * at all rather than saying there are none — a runner who has never used the feature is not a
     * runner failing at it.
     *
     * Told to the coach so a debrief can read a hard week as the week the runner meant to have.
     * Fenced hard in the prompt for the same reason it is worth sending: a coach shown "12 of 40 km
     * with two days left" has an obvious way to help, and it is the one way this app will not
     * allow — a Goal is the runner's to chase across a period, never work to buy with one harder
     * prescription, and never evidence that a Stage has been earned.
     *
     * Not gated on [RunnerSession.includeInAiTraining], on the same terms as [fitnessAndForm]: this
     * is a total over a period, not a Run described to the coach. The runs the coach is shown
     * one by one are the eligible ones and only those (`getLast3AiEligibleRunsOfStage`), which is
     * where that switch has always done its work.
     */
    val goals: List<AiGoal> = emptyList()
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
    private val runPauseDao: RunPauseDao? = null,
    // Null wherever records are not wired (tests, and the archive's read-only container): a run then
    // finishes without being scored rather than failing to finish.
    private val achievementDao: AchievementDao? = null,
    // Null on the same terms as the record book it feeds: a treadmill Run then simply holds no
    // stated Best Effort, which is what every Run held before #282 anyway.
    private val statedBestEffortDao: StatedBestEffortDao? = null,
    /**
     * The runner's named places and the times run at them (#70).
     *
     * Null on the same terms as the record book: wherever Segments are not wired — tests, and the
     * archive's read-only container — a Run finishes without being put to any, which is what every
     * Run did before this shipped.
     */
    private val segmentDao: SegmentDao? = null,
    private val segmentEffortDao: SegmentEffortDao? = null,
    /**
     * The shapes Runs recognise each other by (#73).
     *
     * Null on the same terms as the Segments above: wherever it is not wired, a Run finishes without
     * its shape being taken and no Run is ever matched to another, which is what every Run did
     * before this shipped.
     */
    private val runShapeDao: RunShapeDao? = null,
    /**
     * Every Run's claim at every Record, banked beside the medals (#75) — see [RunEffortRow].
     *
     * Null on the same terms as the record book it is written beside: wherever records are not
     * wired, nothing is banked here either, and the Records section reads an empty history. Never
     * written without the book being written in the same transaction, and never read to decide
     * anything — this class only fills it.
     */
    private val runEffortDao: RunEffortDao? = null,
    /**
     * Whether the whole of history is part-way through being measured against the book (#75) — see
     * [RecordFillRow].
     *
     * Null on the same terms as the rows it speaks for: wherever records are not wired there is no
     * fill to be part-way through, and the Records section is handed a history that is whole because
     * it is empty. Read by exactly one screen and written by exactly the two passes that can pay a
     * fill off.
     */
    private val recordFillDao: RecordFillDao? = null,
    /**
     * The runner's own Goals, read so the coach can be told where they stand (#83).
     *
     * Null wherever goals are not wired — tests, and the archive's read-only container — and the
     * coach is then told nothing about goals at all, which is the same thing it is told about a
     * runner who has set none. Nothing else in this class reads it: a Goal answers no requirement
     * and moves no curve, so there is no path here it could go wrong on.
     */
    private val goalDao: GoalDao? = null,
    /**
     * Where a Run's AI summary is kept once it has been written (#76) — see [RunSummaryRow].
     *
     * Null on the same terms as the record book: wherever it is not wired — tests, and the archive's
     * read-only container — a Run's page simply never offers a summary, which is what every Run's
     * page did before this shipped.
     */
    private val runSummaryDao: RunSummaryDao? = null,
    /**
     * Where a Run whose row does not yet say what the runner said it was is written down (#371) —
     * see [WalkMarkDebtRow].
     *
     * Null wherever the debt cannot be paid: tests that drive the DAOs directly, and the archive's
     * read-only container, which never marks a Run anything. Unwired, a settlement judging on a word
     * the row disagrees with writes the same row the code did before this shipped and says so in the
     * log — the judgement is unaffected either way, because the word is what it is made on.
     */
    private val walkMarkDebtDao: WalkMarkDebtDao? = null,
    /**
     * The runner's library of courses, read for one thing only: drawing the course a live Run set
     * out to follow on its map (#56).
     *
     * Null on the same terms as the record book — wherever it is not wired, a Run's map draws its
     * amber trail and nothing else, which is what every Run's map did before this shipped. Nothing
     * here ever writes to it: keeping the library is the Routes screen's business, and a Run that
     * happened to be started on a course must not be able to change it.
     */
    private val routeDao: RouteDao? = null,
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
     * Books the after-run work for a Run that has just been written down, and returns only once the
     * request is in WorkManager's own database (`AfterRunWorker.enqueue`).
     *
     * The difference from [refreshHistoryBackup] is whose life the snapshot depends on. That one
     * copies the database here and now, on this process; this one hands the job to something that
     * survives the process. A teardown rescue needs the second kind, because the thing that took
     * the service down is often about to take the process with it: the row would be stamped
     * finished, the Run would no longer qualify for the launch pass, and the Downloads snapshot
     * would stay one Run behind until some later operation happened to refresh it (#309).
     *
     * Injected rather than reached for through a `Context`, so this class stays a repository over
     * DAOs and the tests can watch the booking without WorkManager. Null in tests and wherever no
     * backup target is wired; the teardown rescue then falls back to the in-process snapshot.
     */
    private val bookAfterRunWork: ((Long) -> Unit)? = null,
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
     * Every claim ever banked against a Record, oldest Run first — what the Records section of the
     * Progress screen is drawn from (#75).
     *
     * Empty wherever records are not wired, which is the same picture a runner with no history sees:
     * seven Records with nothing standing at any of them.
     */
    fun recordEffortsFlow(): Flow<List<RecordEffortRow>> =
        runEffortDao?.getRecordEffortsFlow() ?: flowOf(emptyList())

    /**
     * Whether history is being measured against the record book wholesale right now — which is when
     * the Records section must not call anything it can see an all-time best (#75).
     *
     * `run_efforts` is filled a Run at a time by the launch pass ([scoreMissedRecords]), and over a
     * long history that is minutes of work. The upgrade that added the table cleared every Run's
     * scoring mark to raise exactly that work (`MIGRATION_36_37`), so the first launch after it has
     * a table holding a slice of history — and a top ten read off a slice is a top ten with the
     * wrong runs in it, handing display medals to Runs that do not really place. The record book
     * itself is not in that position: it keeps its medals across the upgrade and is only
     * re-confirmed. This is about the deeper rows underneath it.
     *
     * **The fill is asked about, not counted.** How many Runs owe a scoring cannot answer this at
     * any threshold: one debt is an ordinary Run finishing on a Tuesday, whose records must not be
     * blanked for the seconds its own scoring takes, and one debt is also the entire migration
     * backfill on a history with a single Run in it, where nothing the section could draw has been
     * measured yet. Same count, opposite answers. So the fill is written down as its own fact where
     * it starts and lowered where it finishes ([RecordFillRow]), and this reads that.
     *
     * What follows from reading the fact rather than the debts is the part worth stating plainly:
     * an ordinary Run awaiting its own scoring changes nothing here. What stands is then every claim
     * but the newest, which was the right answer a moment ago and becomes the right answer as of now
     * the moment that Run is scored. What can never be shown is a *slice*.
     *
     * False wherever no record fill is wired, which is the same picture as a history nobody is
     * measuring — because there is none.
     */
    fun recordsBeingMeasuredFlow(): Flow<Boolean> =
        recordFillDao?.wholesaleFillOwedFlow()?.distinctUntilChanged() ?: flowOf(false)

    /**
     * Hands back the wholesale-fill debt, once the pass that was paying it has been through the
     * whole of the work it found (#75).
     *
     * Asked before it is written, so a launch that was owed nothing does not wake the Records
     * section with a write that changes no answer.
     *
     * **After the sweep, not after a perfect sweep.** A Run the pass could not measure — an
     * unreadable track, a write that threw — keeps its own debt and is tried again at the next
     * launch, and that is the right place for it. But the fill is a statement about the *table*,
     * and once every owed Run has been offered to the book the table is as whole as this history
     * can make it: one Run's claims missing from a top ten is a small, self-mending wrong, while a
     * Records section hidden for ever behind a Run that will never measure is a permanent one. The
     * runner would be left with a screen that says "still measuring your runs" until they delete
     * the Run, with nothing on it to tell them why.
     */
    private suspend fun handBackTheWholesaleFill() {
        val dao = recordFillDao ?: return
        if (!dao.wholesaleFillOwed()) return
        dao.put(RecordFillRow(wholesaleFillOwed = false))
    }

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
                ranAtUtcOffsetSeconds = it.ranAtUtcOffsetSeconds,
            )
        }
    }

    /**
     * Whether the runner's Test is due, for the Today card to say so (#292).
     *
     * [planTests] is every Test the active plan holds, or empty for a plan whose Stages offer none —
     * which answers false for ever without asking history anything. All of them rather than the
     * Stage's own, for the reason set out on [SessionDao.getCompletedRunsOfWorkouts]: the Test that
     * graduated a Stage was still a test, and it was run days ago.
     *
     * The last Test is the newest Run of one of them that was actually that Test ([wasRunFarEnough])
     * — asked here rather than in SQL, which cannot reach the plan to learn what each Test asks
     * for.
     *
     * The two facts the rule needs, each read where it already lives: when a Test was last run, off
     * history, and the runner's Form, off the same curve the Progress screen draws. The decision
     * itself is [testIsDue] and is nowhere near either read.
     *
     * The calendar day is a third input and moves on its own, unlike the other two: both halves of
     * the rule are answers about today — a Test comes due at midnight, and Form recovers across a
     * rest day the curve only counts once the day exists — so a phone kept in a pocket over a
     * weekend would otherwise hold Friday's answer until some Run was recorded. [dayTurns] is what
     * makes the answer arrive on its own; the day itself is read below.
     *
     * [zone] is asked, not held (#299): the day the app is in is observed, so a runner who lands in
     * another zone with this card in front of them is answered where they are and not where they
     * took off from. Which is why [clock] is a plain clock and not one bound to a zone — the zone
     * comes from [zone] every time, and nowhere else.
     *
     * [zoneChanges] is what makes that answer arrive when the runner moves rather than when history
     * does — see [systemZoneChanges]. It reaches the rule through [dayTurns], because a zone change
     * and a midnight are the same thing to this flow: the day under the answer has moved.
     *
     * Defaulted, unlike the screens', and on the same terms as [zone] and [clock] beside it: a caller
     * that offers none is a caller with no phone to hear the broadcast from, which is every test
     * here. The one caller with a phone passes it.
     */
    fun testDueFlow(
        planTests: List<PlanTest>,
        zone: () -> ZoneId = ZoneId::systemDefault,
        clock: Clock = Clock.systemUTC(),
        zoneChanges: Flow<Unit> = emptyFlow(),
    ): Flow<Boolean> {
        if (planTests.isEmpty()) return flowOf(false)
        val testsById = planTests.associateBy { it.workout.id }
        return combine(
            sessionDao.getCompletedRunsOfWorkouts(testsById.keys.toList()),
            scoredRunsFlow(),
            dayTurns(zone, clock, zoneChanges),
        ) { testRuns, scoredRuns, _ ->
            // Read here, at the answer, rather than carried down from the turn of the day that
            // woke it: every emission is answered in the zone the phone is in as it is answered.
            val here = zone()
            val today = LocalDate.now(clock.withZone(here))
            testIsDue(
                lastTestRanOn = testRuns.firstOrNull { run ->
                    testsById[run.ranUnderWorkoutId]?.let { test ->
                        wasRunFarEnough(
                            test = test.workout,
                            durationSeconds = run.durationSeconds,
                            coveredTheDistance = test.distance?.wasCoveredBy(run.distanceKm) == true,
                        )
                    } == true
                }?.let { ranOn(it.startTime, it.ranAtUtcOffsetSeconds, here) },
                // Yesterday's Fitness less yesterday's Fatigue, as the screen and the coach both
                // read it — null while no Run in history has a Score to build a curve from.
                form = progressCurve(scoredRuns, through = today, zone = here).lastOrNull()?.form,
                today = today,
            )
        }
    }

    /**
     * The best Run in history at [requirement]'s distance, for the Stage card to name where the
     * runner has already beaten the bar (#293) — and null where there is nothing to name.
     *
     * A read and nothing else: nothing here grants, advances or writes anything. The rule stays
     * forwards-only (ADR 0016) and this only says out loud what history already holds, so the card
     * can stop looking like a bug to a runner with a qualifying 5K behind them.
     *
     * Null [requirement] is a Stage whose requirement is a judgement — stage 1's "4 weeks of
     * consistent Zone 2 training" — which has no bar to have been beaten and so says nothing.
     *
     * Silent under testing mode, which is the one state where "run one now and it counts" is not
     * true: [graduateOnBestEffortRequirement] refuses to grant while it is on, and a card promising
     * a graduation the rule will decline is worse than a card that says nothing.
     */
    fun bestInHistoryFlow(requirement: BestEffortRequirement?): Flow<HistoryBestEffort?> {
        if (requirement == null) return flowOf(null)
        val dao = achievementDao ?: return flowOf(null)
        val settings = settingsRepository?.userSettingsFlow ?: return flowOf(null)
        return combine(
            dao.getQuickestInHistoryFlow(requirement.record),
            settings,
        ) { best, userSettings -> best.takeUnless { userSettings.testingModeEnabled } }
    }

    /**
     * A nudge now, and another each time the calendar turns over — the passage of time as something
     * a rule can be combined with rather than something it has to remember to read (Codex P2 on
     * #292).
     *
     * It emits nothing but the nudge. The day itself is read by whoever was woken, at the moment
     * they answer, so nothing here can hand on a day that was true when the sleep started and is
     * not true now (#299).
     *
     * It sleeps to the next local midnight rather than polling, so an app left open costs one wake
     * a day. A phone that dozes through midnight wakes the sleep late and the new day lands a moment
     * after the runner returns to the screen, which is a prompt that appears rather than one that
     * was already wrong — the direction [testDueFlow] wants (see its `initial = false`).
     *
     * Midnight is worked out in [zone]'s answer each time round, so the sleep is aimed at the
     * runner's own midnight through a timezone change or the end of summer time.
     *
     * But a sleep already running cannot re-aim itself, and that is what [zoneChanges] is for
     * (#320). A runner who flies west has a sleep aimed at a midnight hours later than the one they
     * are now living in, so each nudge throws that sleep away and starts the loop over: it nudges
     * whoever is listening at once, in the new zone, and then aims a fresh sleep at the new
     * midnight. `flatMapLatest` rather than a race inside the sleep, so there is no moment in the
     * loop where a change can land unheard.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun dayTurns(
        zone: () -> ZoneId,
        clock: Clock,
        zoneChanges: Flow<Unit>,
    ): Flow<Unit> = zoneChanges.startingNow().flatMapLatest {
        flow {
            while (true) {
                emit(Unit)
                // Read after the answer has been given, so the sleep is aimed from where the runner
                // is now rather than from where they were when the last one was set.
                val here = zone()
                val nextMidnight = LocalDate.now(clock.withZone(here))
                    .plusDays(1).atStartOfDay(here).toInstant().toEpochMilli()
                // Never negative and never zero: a clock that has jumped past the next midnight
                // would otherwise spin this loop, and the day is re-read at the top anyway.
                delay((nextMidnight - clock.millis()).coerceAtLeast(1L))
            }
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
                ranAtUtcOffsetSeconds = it.ranAtUtcOffsetSeconds,
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
     * cost the others nothing, and it stays interrupted for the next launch to try again. What one
     * Run's rescue is, is [finishFromRecord] — the same rescue a teardown asks for by name
     * ([rescueRunLostToTeardown]), so a Run put back at the moment it was lost and a Run put back a
     * launch later come out identical.
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
        val settings = settingsRepository ?: return@withLock
        val interruptedIds = sessionDao.getInterruptedSessionIds(startedBeforeMillis)
        if (interruptedIds.isEmpty()) return@withLock

        val historyProfile = settings.userSettingsFlow.first().historyHrProfile
        val rescued = interruptedIds.count { finishFromRecord(it, historyProfile) }

        // Only once, and only if something moved: the snapshot is a copy of the whole database, and
        // a launch that rescued nothing should not pay for one.
        if (rescued > 0) refreshHistoryBackup?.invoke()
    }

    /**
     * Finishes the one Run a service teardown left recording, at the moment it was left (#309).
     *
     * The same rescue as the launch pass above, asked for a Run by name. The pass exists for a Run
     * whose *process* died, and it is safe because a process cannot be recording a Run older than
     * itself — but a service can be taken down inside a process that goes on living, and then the
     * pass's own rule keeps it away: the Run began after this process did, so it is outside the
     * query, and the row sits at `endTime = 0` for as long as the process lasts. That is what
     * happened in #309 — the Run was still unfinished two hours later with the app used since.
     *
     * What makes this safe is not a clock but the caller: the only caller is the teardown of the
     * service that was recording this Run ([com.example.runningapp.run.runLostToTeardown]), so
     * there is nothing left to be recording it. Nothing here may be called about a live Run, and
     * [finishFromRecord] will not touch a Run that already has an end time either way.
     *
     * Returns whether the Run was put back, so the caller can say so in the Run Journal — a
     * `run-finalized` from here is the answer to a `service-destroyed` that names a live Run.
     *
     * Hands the snapshot to [bookAfterRunWork] rather than taking one here, and hands it over the
     * moment the row is stamped rather than when this returns. Whatever ended the service can end
     * the process at any point after that stamp, and a Run that is stamped finished is a Run the
     * launch pass will never look at again — so an in-process copy that had not finished, or not
     * started, would leave the Downloads snapshot permanently one Run behind, and a Clear storage
     * in that window would restore a history without this Run in it. The normal finalization has
     * always answered this the same way; the teardown rescue is the same finish arriving by another
     * door, so it gets the same durable handoff.
     *
     * The in-process copy is what is left when that handoff does not happen — either because
     * nothing durable is wired to take it (tests, the archive's read-only container) or because the
     * booking threw. One rule for both, and it is written as one: the fallback is owed whenever the
     * booking did not come back, because a rescued Run with neither snapshot behind it is exactly
     * the Run a Clear storage would lose. A copy taken here and now may not outlive the teardown,
     * but a copy that might happen beats one that certainly will not.
     */
    suspend fun rescueRunLostToTeardown(runRowId: Long): Boolean = statedProfile.withLock {
        val settings = settingsRepository ?: return@withLock false
        val historyProfile = settings.userSettingsFlow.first().historyHrProfile
        var booked = false
        val rescued = finishFromRecord(runRowId, historyProfile, onRowFinished = { rowId ->
            // Set after the call and not before it, so a booking that throws leaves this false and
            // the fallback below owns the snapshot. The throw itself is swallowed by
            // [finishFromRecord] — the row is finished by then and must stay finished.
            bookAfterRunWork?.invoke(rowId)
            booked = bookAfterRunWork != null
        })
        if (rescued && !booked) refreshHistoryBackup?.invoke()
        rescued
    }

    /**
     * Takes away the row of a Run that was torn down before it recorded a single second (#314).
     *
     * The Run's row is inserted asynchronously, and a teardown can arrive while that insert is
     * still in flight. The insert is not cancelled — it runs on a scope that outlives the service,
     * and by the time the teardown could reach for it the row is often already committed — so what
     * the teardown does instead is wait for it and settle what it produced. A Run that had banked
     * seconds has them written to that row and is rescued like any other
     * ([rescueRunLostToTeardown]). A Run that had banked nothing has this.
     *
     * Left alone, such a row is a Run that cannot be rebuilt and cannot be finished: every query
     * reads `endTime = 0` as "still recording" and steps around it, and the launch pass offers it
     * to [finishedFromRecord], which refuses it for exactly the right reason — a Run with nothing
     * in it is not a Run, and a recovery path must never be the thing that puts something into
     * history. So it sits there for good, tried again at every launch. Taking it away is the other
     * half of that same rule: a row that will never become a Run should not go on being one of the
     * things the app is holding.
     *
     * Refuses on anything but that exact shape. A row with an end time is a Run somebody finished —
     * a finalize that beat the teardown to it — and totals are not evidence of samples, so its
     * emptiness elsewhere proves nothing. A row with a sample or a fix against it is a Run with a
     * record, and deleting it would take the record with it.
     *
     * Those refusals are the delete's own conditions rather than checks taken before it
     * ([SessionDao.deleteSessionIfItRecordedNothing]), because the teardown's waits for the Run's
     * writers are bounded and a bounded wait can end with a writer still going: a drain that gives
     * up, or a fix from a looper asked to quit and never joined. A read and then a delete would be
     * a decision about a row that can change in between, and the row it deleted would be a Run
     * with its record inside it. As one statement there is no such in-between.
     *
     * A sample and a fix are the whole of the test because they are the whole of what a rebuild
     * reads: with neither, [finishedFromRecord] refuses the row, and it will refuse it at every
     * launch from now until the phone is replaced. A banked Interval or a Pause does not change
     * that. Those are bookkeeping about seconds — how the Workout was going, where the clock
     * stopped — and neither says a second was ever written down, so a row holding only those is
     * still a row that can never become a Run. They go with it, which the database does itself:
     * every table that hangs off a Run is `ON DELETE CASCADE`.
     *
     * Deletes the row directly rather than through [deleteSession]. That door rolls back everything
     * a Run in history stands under — the coach's provenance, the record book, the segments, the
     * history snapshot — and this row has never been in history, has never been finished, has never
     * been scored and has never been shown to the coach. There is nothing standing on it to roll
     * back, and this runs inside a teardown of a process that may be about to end.
     *
     * @return whether a row was taken away, so the caller can say so in the Run Journal.
     */
    suspend fun discardRunThatRecordedNothing(runRowId: Long): Boolean {
        val discarded: Int
        try {
            discarded = sessionDao.deleteSessionIfItRecordedNothing(runRowId)
        } catch (e: Exception) {
            // The row stays exactly as it is, which is where it was before this was tried. Nothing
            // here is worth taking a dying process down for.
            Log.w("InterruptedRun", "Could not discard the empty row of run $runRowId", e)
            return false
        }
        if (discarded == 0) {
            // Somebody wrote to the row between the teardown deciding to settle it and this
            // statement — a sample or a fix from a producer the bounded waits could not see the
            // end of, or a finalize that beat both. The row has a record now, so it is a Run like
            // any other and the launch pass has it.
            Log.w("InterruptedRun", "Run $runRowId had recorded something after all; its row stays")
            return false
        }
        Log.w("InterruptedRun", "Discarded run $runRowId: its row landed after the service was destroyed and it had recorded nothing")
        return true
    }

    /**
     * One Run put back from what it wrote down, and everything that hangs off a Run being finished.
     *
     * Never throws: a Run that cannot be rebuilt costs the caller nothing and stays interrupted for
     * the next launch to try again — which for the pass means the Runs after it in the list are
     * still rescued, and for a teardown means the loss is no worse than it already was.
     *
     * @param historyProfile what to band against where the Run carries no Reserve of its own (#228).
     * A Run that carries one is rebuilt on *that*: it is the Reserve the Run was recorded and
     * coached under, which is the one its seconds mean anything against. Neither global number would
     * do — the one in force is wrong for a Run started before a future-only Max HR correction, and
     * the one history is banded against is wrong for every Run started after it.
     * @param onRowFinished run the instant the Run's totals reach its row, before any of the work
     * that hangs off them. This is where a caller whose process may be about to end puts whatever
     * has to outlive it, and the reason it is here rather than at the call site is that the
     * measuring and scoring below take real time on a real database — a handoff made after this
     * function returns would leave the whole of that time as a window in which the Run is finished
     * on disk and spoken for by nobody. The launch pass passes nothing: its process is not dying,
     * and it snapshots once for the whole list instead of once per Run. Called from IO, which is
     * what lets it be something that blocks. A throw here is swallowed: the row is finished by this
     * point and must stay finished, whatever the handoff made of it.
     * @return whether the Run's totals reached its row.
     */
    private suspend fun finishFromRecord(
        runRowId: Long,
        historyProfile: HrProfile,
        onRowFinished: ((Long) -> Unit)? = null,
    ): Boolean {
        val samples = sampleDao ?: return false
        try {
            val session = sessionDao.getSessionById(runRowId) ?: return false
            // A Run that already has an end time is a Run somebody finished, and totals derived
            // from the record are not an improvement on the ones the Run itself banked. The launch
            // pass only ever asks about Runs with no end time; the teardown asks about the Run it
            // was holding, and a finalize that beat it there is exactly the case this declines.
            if (session.endTime != 0L) return false
            // Read once and gated here rather than through [getTrackPointsForMap], because the
            // rebuild wants both: every fix says when the Run was recording, the accepted ones
            // say where it went. See [finishedFromRecord].
            val track = trackPointDao?.getTrackPointsForSessionOnce(runRowId).orEmpty()
            val finished = session.finishedFromRecord(
                samples = samples.getSamplesForSessionOnce(runRowId),
                track = track,
                mappedTrack = track.acceptedForMap(),
                profile = session.bandedOnHrProfile() ?: historyProfile,
                bankedIntervals = intervalStatDao
                    ?.getIntervalStatsForSession(runRowId)
                    .orEmpty()
                    .isNotEmpty(),
            ) ?: return false
            sessionDao.updateSession(finished)
            try {
                onRowFinished?.invoke(runRowId)
            } catch (e: Exception) {
                // Outside the rescue's own catch on purpose: this Run is in history now, and a
                // handoff that failed must not turn that into "could not rescue" and leave the row
                // to a launch pass that will not have it.
                Log.w("InterruptedRun", "Rescued run $runRowId but could not book its after-run work", e)
            }
            Log.w(
                "InterruptedRun",
                "Rescued run $runRowId: duration=${finished.durationSeconds}s " +
                    "distance=${"%.2f".format(finished.distanceKm)}km avgBpm=${finished.avgBpm}"
            )
        } catch (e: Exception) {
            Log.w("InterruptedRun", "Could not rescue run $runRowId; leaving it for next launch", e)
            return false
        }
        try {
            // After the row is finished, not before: this measures the same stored track and
            // rewrites avgPaceMinPerKm over moving time, which is the pace the app quotes (#163).
            computeMovingTime(runRowId)
        } catch (e: Exception) {
            // Its own attempt, because the row is already finished by this point and will never
            // be offered to this pass again. Failing here leaves movingTimeSeconds null, which
            // is the state [backfillMovingTime] picks up at the next launch — so the Run is in
            // history with everything else it needs, and the one number it is missing is
            // already somebody's job.
            Log.w("InterruptedRun", "Rescued run $runRowId but could not measure its moving time", e)
        }
        try {
            // A rescued Run has just finished, however long ago it was run, so it is scored like
            // any other (#49). Its own attempt for the same reason as the moving time above: the
            // row is already in history, and a book that cannot be written must not undo that.
            // Marked as scored by the same call, and only once the scoring has returned, so a
            // failure here leaves the Run owing one for the launch pass to pay (#210).
            scoreAndMarkRecords(runRowId)
        } catch (e: Exception) {
            Log.w("InterruptedRun", "Rescued run $runRowId but could not score its records", e)
        }
        try {
            // And put to the Segments, for the same reason and on the same terms as the scoring
            // above (#70): a rescued Run has just finished, however long ago it was run. Marked as
            // timed by the same call and only once it has returned, so a failure here leaves the
            // Run owing a walk for the launch pass to pay.
            timeRunAgainstSegments(runRowId)
        } catch (e: Exception) {
            Log.w("InterruptedRun", "Rescued run $runRowId but could not time it against the segments", e)
        }
        try {
            // And its shape taken, on the same terms again (#73). A failure here leaves the Run with
            // no shape row, which is the debt itself, so the next launch pass takes it.
            shapeRun(runRowId)
        } catch (e: Exception) {
            Log.w("InterruptedRun", "Rescued run $runRowId but could not take its shape", e)
        }
        return true
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
     * Puts a newly cut Segment to every Run in history, so it is born with its efforts and its PR
     * (#70).
     *
     * Minutes of GPS arithmetic on a long history, and the caller is expected to be somewhere that
     * survives the screen the Segment was cut on ([com.example.runningapp.AppContainer]).
     */
    suspend fun timeSegmentAgainstHistory(segmentId: Long) {
        segmentTiming?.timeAgainstHistory(segmentId)
    }

    /**
     * Puts one Run to every Segment there is (#70) — what a Run gets when it finishes, and again
     * whenever the runner's word about it changes ([markAsWalk]).
     *
     * Safe to call again: the pass replaces a pair's efforts rather than adding to them
     * ([SegmentEffortDao.replaceEffortsOf]).
     */
    suspend fun timeRunAgainstSegments(sessionId: Long) {
        segmentTiming?.timeAgainstEverySegment(sessionId)
    }

    /**
     * Pays whatever the Segments and the Runs owe each other, at launch (#70).
     *
     * What it finds is a Segment cut before efforts existed, or either side of a walk that a process
     * being reclaimed cut short. On an ordinary launch it reads two empty lists and returns.
     */
    suspend fun payWhatSegmentTimingOwes() {
        segmentTiming?.payWhatIsOwed()
    }

    /**
     * The one walk of Runs against Segments, over this repository's own DAOs.
     *
     * Lazy, and null wherever Segments are not wired, so nothing here opens a table the archive's
     * read-only container has no use for.
     */
    private val segmentTiming: SegmentTiming? by lazy {
        val segmentRows = segmentDao ?: return@lazy null
        val effortRows = segmentEffortDao ?: return@lazy null
        SegmentTiming(object : SegmentTimingStore {
            override suspend fun segments() = segmentRows.getAllSegments()
            override suspend fun segment(segmentId: Long) = segmentRows.getSegment(segmentId)
            override suspend fun segmentsMissingHistory() = segmentRows.getSegmentsMissingHistory()
            override suspend fun runs() = sessionDao.getAllSessions()
            override suspend fun run(sessionId: Long) = sessionDao.getSessionById(sessionId)
            override suspend fun runsMissingTiming() = sessionDao.getSessionIdsMissingSegmentTiming()

            // The same accuracy-gated fixes the map, the splits and the record book are built from,
            // so a wild fix the Run itself refused cannot put an effort on a Segment nobody ran.
            override suspend fun track(sessionId: Long) = getTrackPointsForMap(sessionId)

            override suspend fun replaceEfforts(
                segmentId: Long,
                sessionId: Long,
                efforts: List<SegmentEffort>,
            ) = effortRows.replaceEffortsOf(segmentId, sessionId, efforts)

            override suspend fun markSegmentTimed(segmentId: Long) = segmentRows.setHistoryTimed(segmentId)
            override suspend fun markRunTimed(sessionId: Long) = sessionDao.setSegmentsTimed(sessionId)
        })
    }

    /**
     * Takes one Run's shape, so it can be matched to the others (#73) — what a Run gets when it
     * finishes, and again whenever the runner's word about it changes ([markAsWalk]).
     *
     * Safe to call again: a Run has one shape row and a second reading replaces the first.
     */
    suspend fun shapeRun(sessionId: Long) {
        runShaping?.shapeRun(sessionId)
    }

    /**
     * Takes the shape of every Run that has never had one, at launch (#73) — the whole of history on
     * the first launch after this shipped, and nothing at all on every launch afterwards.
     */
    suspend fun payWhatRunShapesOwe() {
        runShaping?.payWhatIsOwed()
    }

    /**
     * Every Run that holds a shape, watched — the field a Run's page matches itself against (#73).
     *
     * Empty wherever shapes are not wired, which shows the same thing a runner with one Run sees:
     * no matched runs.
     */
    fun shapedRunsFlow(): Flow<List<RunShapeCandidate>> =
        runShapeDao?.getShapedRunsFlow() ?: flowOf(emptyList())

    /** The one taking of Run shapes, over this repository's own DAOs. Null wherever it is not wired. */
    private val runShaping: RunShaping? by lazy {
        val shapeRows = runShapeDao ?: return@lazy null
        RunShaping(object : RunShapeStore {
            override suspend fun run(sessionId: Long) = sessionDao.getSessionById(sessionId)
            override suspend fun runsMissingShapes() = sessionDao.getSessionIdsMissingRunShapes()

            // The same accuracy-gated fixes the map, the splits and the Segments are built from, so
            // a wild fix the Run itself refused cannot bend a route out of the group it belongs to.
            override suspend fun track(sessionId: Long) = getTrackPointsForMap(sessionId)

            // Read again inside the transaction that writes, `scoreRecordsUnlessOvertaken`'s rule
            // and for its reason (#210): the database takes one writer at a time, so either the
            // mark that overtook this measurement has committed by then and this sees it, or it
            // commits afterwards and its own re-shaping has the last word. Cheaper than a lock, and
            // nothing is made to wait behind seconds of arithmetic.
            override suspend fun putShapeUnlessTheRunMoved(
                sessionId: Long,
                shape: RunShape?,
                measuredAs: RunnerSession,
            ): Boolean {
                var written = false
                inTransaction {
                    val now = sessionDao.getSessionById(sessionId) ?: return@inTransaction
                    if (!now.shapesAs(measuredAs)) return@inTransaction
                    shapeRows.putShape(runShapeRowOf(sessionId, shape))
                    written = true
                }
                return written
            }
        })
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
            // The same efforts, banked whole, in the same commit that ranks them (#75). One
            // transaction because they are one measuring: a book written without them would leave
            // the Records section a top ten short of a Run it has already given a medal to, and
            // nothing afterwards goes back for either.
            bankEfforts(sessionId, efforts)
            earned = rewritten.filter { it.sessionId == sessionId }
        }
        return earned
    }

    /**
     * Banks what one Run is worth at the Records it contested, over whatever the last measuring of
     * it said (#75).
     *
     * Called only from inside the transaction that writes the record book, never on its own: these
     * rows and the medals are one measuring, and a caller free to write one without the other is a
     * caller free to make them disagree.
     *
     * A re-scoring replaces this Run's rows rather than joining them, which the row's own key does
     * ([RunEffortRow] is keyed by the Run and the Record) — so scoring a Run twice leaves it holding
     * one claim at each Record rather than racing itself, the same promise [standingsAfter] keeps
     * for the medals.
     *
     * Only the Records the Run still contests are touched. A Record it has stopped contesting
     * altogether — a stated time withdrawn, a Walk marked — is not this path's to clear and never
     * was: those go through the rebuild, which wipes the Record whole and writes back only what
     * still stands.
     */
    private suspend fun bankEfforts(sessionId: Long, efforts: List<BestEffort>) {
        runEffortDao?.putEfforts(efforts.map { RunEffortRow(sessionId, it.type, it.value) })
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
        var scored = 0
        sessionIds.forEach { sessionId ->
            try {
                scoreAndMarkRecords(sessionId)
                scored++
            } catch (e: Exception) {
                Log.w("Records", "Could not score run $sessionId; leaving it for next launch", e)
            }
        }
        if (sessionIds.isNotEmpty()) {
            Log.d("Records", "Scored $scored of ${sessionIds.size} run(s) the book had missed")
        }
        // This pass is the one that pays off a wholesale fill, so it is the one that hands the debt
        // back — and only once it has been through every Run it found, which is why it no longer
        // returns early on an empty list: a launch that finds nothing owing is a launch that has
        // just finished the fill, or a launch after one where the migration un-scored nothing at
        // all. Anything that cuts the loop short — the process reclaimed, the scope cancelled —
        // leaves the fill standing for the next launch to finish, which is the whole reason the
        // fact is in the database. See [handBackTheWholesaleFill] for why a Run that could not be
        // measured does not hold it up.
        handBackTheWholesaleFill()
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
                // And the wholesale fill with them (#75): this pass rewrote the whole of
                // `run_efforts` in the transaction that just committed, so whatever fill was
                // outstanding — the one the v36 to v37 migration raised, or one a restored archive
                // arrived still owing — has been paid in full by a book built over all of history at
                // once. On the declining paths above nothing is written and the fill stands, which is
                // right: the table was left as it was, and as it was is what the debt describes.
                handBackTheWholesaleFill()
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

            // Every changed Run's banked claims re-taken, whether or not it held a medal (#75).
            //
            // The book above is mended only where a place moved, which is all it can be: beyond
            // bronze it remembers nothing, so there is nothing there to go stale. The banked rows
            // are the opposite — they hold every claim a Run ever made, and a Run that has stopped
            // making one leaves a row nothing else would ever look at again. A Run marked a Walk
            // that placed fourth at 5 km is exactly that: it loses no medal, so `losing` is empty
            // and the rebuild does nothing, and its time would stand in that Record's top ten for
            // ever.
            //
            // Asked of the Run rather than of the Records, because what changed is what the Run is
            // worth: re-measuring it whole is the only reading that can say a Record it used to
            // contest is one it no longer does.
            val rebanked = rebankEfforts(sessionIds)
            repaired = repairRecordBook(losing, remeasured = sessionIds) && rebanked.landed
            // A second snapshot, because the one above was taken before either of these ran and is
            // now behind whatever they wrote. Owed by the re-banking as much as by the mend (#75):
            // a fourth-place Run marked a Walk moves no medal, so `losing` is empty and the book is
            // never rebuilt — and its banked rows still went. Restored from a snapshot taken before
            // that, the Walk would climb back into the Records top ten and its trend, which is the
            // very thing this change took it out of.
            //
            // On what actually moved rather than on having got here at all, because the backup is a
            // whole copy of history and every edit to a Run's feel or its note comes through this
            // path. A re-measuring that came back with the same claims has left the snapshot as good
            // as it was.
            if (losing.isNotEmpty() || rebanked.movedRows) refreshHistoryBackup?.invoke()
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
    /**
     * Re-takes what [sessionIds] are worth at every Record, replacing whatever was banked for them
     * (#75). Returns whether it landed and whether it moved anything — see [Rebanking].
     *
     * Its own attempt and its own transaction, like [repairRecordBook]: the Runs have already
     * changed by the time this runs, and a re-banking that cannot be written must not take a Walk
     * mark down with it. Left undone it leaves the debt owed, which the next launch's seeding pass
     * pays against a book and a set of rows nobody is moving.
     *
     * A Run that is *gone* re-banks to nothing and needs no help: its rows cascaded away with it
     * ([RunEffortRow]), and it is not in history to be measured. The delete path therefore reaches
     * here and finds nothing to do, which is the right answer rather than a special case.
     *
     * Whole rather than at named Records, and that is the point of it: only a whole measure can say
     * that a Record the Run used to contest is one it no longer does.
     *
     * A Run that moved while it was being measured is left exactly as it stands, and leaves the debt
     * owed — see the abandonment below.
     */
    private suspend fun rebankEfforts(sessionIds: List<Long>): Rebanking {
        val dao = runEffortDao ?: return Rebanking(landed = true, movedRows = false)
        // Raised outside the `try` and never lowered, because a re-banking that threw on the third
        // Run has still moved the first two: what is on disk is stale from the first row that
        // differed, whether or not the rest of the work got there.
        var movedRows = false
        // Raised by a Run that moved out from under the measuring, for the same reason [landed] is
        // lowered by a throw: the rows this pass was going to write were never written, so the Run
        // is still owed a re-banking and the debt has to outlive this call. Per pass rather than per
        // Run, because the debt is paid by one reseed of the whole book either way.
        var overtaken = false
        return try {
            sessionIds.forEach { sessionId ->
                val session = sessionDao.getSessionById(sessionId)
                // Measured outside the transaction below, which is the same split [rebuildRecords]
                // makes: reading and measuring a track is real work, and the database's write lock
                // is not the place to do it.
                val stated = session?.let { statedEffortsOf(sessionId) }.orEmpty()
                val efforts = session
                    ?.let { effortsAt(it, RecordType.entries, stated) }
                    .orEmpty()
                val rows = efforts.map { RunEffortRow(sessionId, it.type, it.value) }
                inTransaction {
                    // The Run and what it has been told it holds are asked for again, inside the
                    // transaction that replaces its rows, and the replacement is abandoned if either
                    // has moved (#75). [scoreRecordsUnlessOvertaken]'s rule, against the same window
                    // and for a sharper reason: a *second* edit landing after this pass measured the
                    // Run scores itself and banks its own rows, and this one committing afterwards
                    // out of a reading taken before it would delete them and put the older claims
                    // back. Nothing later would find it. A withdrawn fourth-place claim re-stated in
                    // that window is the whole of it: the effort held no medal, so `losing` is empty
                    // and no rebuild ever visits the Record again.
                    //
                    // Inside, because the database takes one writer at a time — either the newer
                    // edit has committed by now and this reads it, or it commits afterwards and its
                    // own banking has the last word. Cheaper than a lock, and nothing is made to
                    // wait behind a walk of a track.
                    //
                    // A Run that was already gone when it was measured is not overtaken by still
                    // being gone: its rows went with it ([RunEffortRow]), and re-banking it to
                    // nothing is the right answer. One reappearing would be a different Run at the
                    // same id, which is nothing this reading can speak for either.
                    val now = sessionDao.getSessionById(sessionId)
                    val moved =
                        if (session == null) now != null
                        else now == null || !now.contestsAs(session) || statedEffortsOf(sessionId) != stated
                    if (moved) {
                        Log.d("Records", "Run $sessionId changed while what it is worth was being re-taken; leaving its claims")
                        overtaken = true
                        return@inTransaction
                    }
                    // Read inside the same transaction that replaces them, so what is compared is
                    // what is overwritten. Sets rather than lists: a re-measuring that came back
                    // with the same claims in another order has moved nothing the runner or the
                    // Records section could ever see.
                    if (dao.getEffortsForSession(sessionId).toSet() != rows.toSet()) movedRows = true
                    dao.deleteEffortsForSession(sessionId)
                    dao.putEfforts(rows)
                }
            }
            Rebanking(landed = !overtaken, movedRows = movedRows)
        } catch (e: Exception) {
            Log.w("Records", "Could not re-bank what ${sessionIds.size} run(s) are worth", e)
            Rebanking(landed = false, movedRows = movedRows)
        }
    }

    /**
     * What one pass of [rebankEfforts] did: whether it landed, and whether it changed anything (#75).
     *
     * Two answers rather than one because they are asked by different callers for different reasons.
     * [landed] is the debt — a re-banking that could not be written leaves history owing a full
     * reseed, exactly as a mend that could not be written does. Owed by a re-banking that *declined*
     * to be written too: a Run overtaken mid-measure is one whose rows this pass never replaced, and
     * the newer scoring behind it is the one that owns them now. [movedRows] is the snapshot on disk
     * — the backup is a whole copy of history and taking one is not free, so it is refreshed when
     * the rows behind the Records section actually moved and not on every change that reaches here.
     *
     * A pass can be both: one Run re-banked and the next one thrown on leaves rows moved and the
     * debt owed at the same time.
     */
    private data class Rebanking(val landed: Boolean, val movedRows: Boolean)

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
     * would be wiped by the rewrite — so the standing rows of any Run this pass cannot have the last
     * word on are carried in as claims of their own. Their stored value is the effort they were
     * awarded for, so they can be ranked beside the freshly measured ones without measuring again.
     *
     * Which Runs those are is decided by what history looked like when it was read, not by what the
     * measuring came back with. A Run still being recorded then *is* in the list and is worth
     * nothing until it finishes — which is exactly the Run most likely to finish and score itself
     * while this pass is still measuring — so its rows are kept. A Run that was already finished is
     * answered for by this pass whatever it measured to, including nothing at all, and its standing
     * rows go. That difference is the whole of it: a Run marked a Walk contests nothing, so it
     * measures to nothing, and judging the carry-in on emptiness instead would conflate it with the
     * Run nobody could measure yet and hand its old rows straight back. Its time would return to the
     * Records section at every reseed and no repeated pass could ever shift it (#75).
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
        val history = sessionDao.getAllSessions()
        val measured = withContext(Dispatchers.Default) {
            history.map { session ->
                RunEfforts(session.id, effortsAt(session, types, statedByRun[session.id].orEmpty()))
            }
        }
        // The Runs this pass has the last word on: the ones history showed as finished when it was
        // read, whether or not they turned out to be worth anything, plus the ones the rebuild was
        // called for. Everything else — a Run still being recorded, a Run that appeared after the
        // read — keeps whatever is standing for it. See the carry-in above.
        val measuredIds = history.filter { it.isFinished() }.map { it.id }.toSet() + remeasured

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
            // The efforts behind the book, rewritten on exactly the terms the book is (#75): the
            // Runs this pass measured, plus what is banked for the Runs it did not — which is the
            // same carry-in, decided by the same [measuredIds], so a Run that finished mid-measure
            // keeps the rows its own scoring wrote rather than being wiped by this one, and a Run
            // that was measured and found to be worth nothing loses the rows it used to hold.
            val carried = runEffortDao?.getEffortsOfTypes(types)
                .orEmpty()
                .filter { it.sessionId !in measuredIds }
            runEffortDao?.deleteEffortsOfTypes(types)
            runEffortDao?.putEfforts(
                measured.flatMap { run ->
                    run.efforts.map { RunEffortRow(run.sessionId, it.type, it.value) }
                } + carried
            )
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

    /**
     * One-shot read of a Run's Pauses, in the order it took them (#328).
     *
     * Empty for a Run recorded before they were written down, which is not the same claim as a Run
     * that took none — see [com.example.runningapp.export.RunFitActivity], which is the one reader
     * of these and where that difference is decided.
     */
    suspend fun getPauses(sessionId: Long): List<RunPause> =
        runPauseDao?.getPausesForSession(sessionId).orEmpty()

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
     * The course a Run set out to follow, in the order the Run is running it (#56, #57) — empty for
     * a Run following none.
     *
     * Reversed here, and nowhere else, when the runner said they were setting off the other way
     * round. The line drawn on the map does not care — the same ground in the same places — but how
     * far is left does, and it is read off this same list ([com.example.runningapp.routes.CourseLine]).
     * One reader of [com.example.runningapp.run.RunRoute.reversed] means the plan drawn and the
     * distance remaining cannot come to disagree about which way the runner turned.
     *
     * Watched through the Run's own row rather than taken as a reading, because a live Run's map is
     * built the moment the screen appears and the row may not exist yet: START inserts it on another
     * thread, and a map that asked once would draw no course for the whole of a routed Run.
     *
     * Empty is also what a Route deleted from the library gives back, and that is the honest answer
     * rather than a failure. A Route is a plan the runner keeps and may throw away, and throwing one
     * away costs a Run nothing (ADR 0014) — so the Run carries on, drawing where it is actually
     * going, with nothing left to draw the plan from.
     *
     * Which is why the Route is *watched* and not read. The library stays editable while the runner
     * is out on a course, and a Route deleted mid-Run moves nothing on the Run's own row — so a
     * reading taken once would never be asked for again, and the map would go on drawing a course
     * the library no longer holds. The promise made where deleting is offered has to be kept by the
     * screen that was relying on it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun routeLineForRunFlow(sessionId: Long): Flow<List<RoutePoint>> {
        val dao = routeDao ?: return flowOf(emptyList())
        return sessionDao.getSessionByIdFlow(sessionId)
            .map { it?.ranAlongRoute() }
            .distinctUntilChanged()
            .flatMapLatest { ranAlong ->
                if (ranAlong == null) {
                    flowOf(emptyList())
                } else {
                    dao.getRouteFlow(ranAlong.routeId).map { route ->
                        val course = route?.let { RoutePolyline.decode(it.polyline) }.orEmpty()
                        if (ranAlong.reversed) course.reversed() else course
                    }
                }
            }
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
     * **The Run's own Stage settlement is not re-run and does not have to be** — because it waits
     * for this mark rather than racing it (#297). The sheet carrying this switch goes up at STOP and
     * the Run's Stage is settled when the sheet closes, so a mark made there is in *before* the Run
     * is put to the Plan: it graduates nothing, and it reaches the coach named as a Walk. That is
     * what makes "a Walk graduates nothing" a promise the code keeps rather than a race it usually
     * wins; it used to be asked at STOP, seconds too early, which is the whole of #297.
     *
     * A mark made *afterwards* — on the Run's own page, an hour or three weeks later — is a
     * different thing and is still never replayed, the same rule a Stated Distance is under (#231,
     * ADR 0008). What it buys is every evaluation after it, where
     * [AiTrainingContext.requirementEvidenceRunIdsByTimestamp] leaves the Run out. A Stage already
     * graduated stays graduated.
     *
     * **The Segment-timing mark is lifted before the mark changes**, for the reason the scoring mark
     * is ([SessionDao.clearSegmentsTimed]): a Run is walked against the Segments once and never
     * revisited, so a Run that changes what it is worth to a leaderboard while still carrying the
     * mark is a Run the launch pass will walk straight past. Lifted first, every way the re-timing
     * below can end short — the process reclaimed, the scope cancelled, the walk throwing — leaves
     * the Run owing a walk that the next launch pays; the walk itself hands the mark back when it
     * has finished replacing every effort.
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

        // Every debt this mark raises, and the mark itself, in one transaction.
        //
        // The debts first: from here to the walks below the Run owes a Segment timing and a shape,
        // and every ending short of them leaves those debts standing rather than a leaderboard
        // nobody goes back for or a group holding a Walk. The cost of raising one needlessly is a
        // repeated walk at the next launch. A Walk covers no route, so marking one takes the Run
        // out of every group it was in and unmarking puts it back (#73).
        //
        // Committed *with* the mark rather than before it, because "before" is a window and a
        // measurement already in flight can land in it. Both passes re-read the Run inside the
        // transaction that writes, and abandon what they measured if it has moved
        // ([com.example.runningapp.segments.RunShapeStore.putShapeUnlessTheRunMoved]) — but a write
        // committing after the debt was raised and before the mark was reads the *old* answer,
        // finds nothing moved, and puts back the very row that was just deleted. The row's
        // existence is what tells the launch passes this Run has been dealt with, so nothing would
        // ever revisit it. Atomic, there is no "in between" to land in: the measurement commits
        // wholly before, and is deleted, or wholly after, and is abandoned.
        val write: suspend () -> Unit = {
            inTransaction {
                sessionDao.clearSegmentsTimed(sessionId)
                runShapeDao?.forgetShape(sessionId)
                sessionDao.setIsWalk(sessionId, isWalk)
                // And the debt this mark discharges, in the same breath as the mark that discharges
                // it (#371). Every writer of the column comes through here, so this is where a debt
                // ends whoever paid it. In the transaction for the reason the debts above are
                // raised in it: a mark that committed and a debt that survived it would be paid a
                // second time at the next launch, which for a debt against the runner's *own* later
                // tick means undoing them.
                walkMarkDebtDao?.forgetDebtFor(sessionId)
            }
        }
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

        // The Segments behind it are mended on the same terms as the record book, and by the same
        // reasoning (#70): a Walk holds no effort at any Segment, so marking one takes its times off
        // every leaderboard they are on, and unmarking measures them again. One call for both
        // directions, because the pass replaces a Run's efforts rather than adding to them.
        //
        // Guarded, because the mark is already written and is the thing the runner asked for: a
        // Segment scan that fails must not take the app down behind a switch that has already
        // flipped. The times it leaves standing are mended by the next scan of the same pair —
        // which the debt lifted above is what guarantees, since the mark is handed back only by the
        // walk that completes.
        try {
            timeRunAgainstSegments(sessionId)
        } catch (e: Exception) {
            Log.w("Walk", "Could not re-time run $sessionId against the Segments", e)
        }

        // Its shape again, on the same terms and for the same reason (#73). Guarded like the walk
        // above: the Run is out of its groups either way, and the shape it should hold now is taken
        // by the launch pass, which the row deleted before the mark is what guarantees.
        try {
            shapeRun(sessionId)
        } catch (e: Exception) {
            Log.w("Walk", "Could not take the shape of run $sessionId again", e)
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

    // --- The Run Summary one Run has been given (#76) ---

    /**
     * The words written about one Run, watched. Null until something has written any.
     *
     * Empty wherever no summary store is wired, which reads as a Run nobody has written about —
     * because there is nowhere it could have been written down.
     */
    fun runSummaryFlow(sessionId: Long): Flow<RunSummaryRow?> =
        runSummaryDao?.summaryFlow(sessionId) ?: flowOf(null)

    /**
     * Whether this Run has already been written about, asked and answered (#76).
     *
     * A one-shot rather than the watched read above, and asked for one thing only: the page's watch
     * says "nothing" from the moment it is set up until the store answers, so a Run that already
     * holds words looks, for that moment, exactly like a Run that holds none. The words are meant to
     * be written once and kept for ever, so the ask that would write them waits for this answer
     * first rather than acting on that moment.
     */
    suspend fun runSummaryWritten(sessionId: Long): Boolean =
        runSummaryDao?.summary(sessionId) != null

    /**
     * The medals one Run holds, read once (#76).
     *
     * A one-shot rather than the watched read the card is drawn from, because it answers a different
     * question: the card asks "what does this Run hold now" for ever, and this asks "what does it
     * hold at the instant these words are being written". Read after the Run's debts are paid
     * ([runSummaryFactsSettledFlow]), so the answer is the whole of what it holds.
     */
    suspend fun achievementsForRun(sessionId: Long): List<Achievement> =
        achievementDao?.getAchievementsForSessions(listOf(sessionId)) ?: emptyList()

    /**
     * Whether anything about this history is still being measured (#76).
     *
     * One question with one answer, because every debt behind it has the same consequence: while
     * any of them stands, what a Run is *worth* can still change. Everything a Run Summary says is
     * a comparison with the rest of history — the medals it holds, the place it took, how often the
     * route has been run — so a Run measured to the last metre can still be demoted by a Run nobody
     * has measured yet. The summary is written once and kept for ever, so that demotion would
     * arrive after the words describing the old answer had been fixed in place.
     *
     * The debts, and the pass behind each:
     *
     * - the wholesale record fill ([recordsBeingMeasuredFlow]), raised by the upgrade that added
     *   `run_efforts` and paid by the launch scoring pass ([scoreMissedRecords]);
     * - the per-run record scoring ([SessionDao.anyRecordScoringOwedFlow]), the same pass seen a Run
     *   at a time — a process that died mid-pass leaves finished Runs owing a scoring with no
     *   wholesale fill outstanding at all, and the rest of that pass rewrites the standings;
     * - the Segment walk ([SessionDao.anySegmentTimingOwedFlow]), paid by
     *   [com.example.runningapp.AppContainer.paySegmentTimingOnce], where an effort timed for
     *   another Run takes a medal off this one;
     * - the Segment's own walk of history ([SegmentDao.anySegmentHistoryWalkOwedFlow]), paid by the
     *   same pass from the other end, where a Segment nobody has walked yet holds no efforts at all
     *   — so this Run can be handed efforts and medals it did not have when the walk reaches it;
     * - the shapes ([SessionDao.anyRunShapeOwedFlow]), paid by
     *   [com.example.runningapp.AppContainer.takeRunShapesOnce], where a Run's group is every Run
     *   shaped like it, so a shape taken later moves the count of times the route has been run.
     *
     * All of these passes run on their own, off any screen's lifetime, which is why a page opened
     * moments after an upgrade can find its own Run marked and history not. A debt of this kind
     * added later belongs here, in this list, rather than in another arm of a gate somewhere.
     */
    fun historyBeingMeasuredFlow(): Flow<Boolean> = combine(
        recordsBeingMeasuredFlow(),
        sessionDao.anyRecordScoringOwedFlow(),
        sessionDao.anySegmentTimingOwedFlow(),
        segmentDao?.anySegmentHistoryWalkOwedFlow() ?: flowOf(false),
        sessionDao.anyRunShapeOwedFlow(),
    ) { fillOwed, scoringOwed, segmentWalkOwed, segmentHistoryWalkOwed, shapesOwed ->
        fillOwed || scoringOwed || segmentWalkOwed || segmentHistoryWalkOwed || shapesOwed
    }.distinctUntilChanged()

    /**
     * Whether this Run has been measured against everything its summary would describe (#76).
     *
     * The summary is written once and kept for ever, so it must not be written out of a half-measured
     * Run. A Run opened straight off the finish line is still being scored against the record book,
     * still being walked against the Segments, and still having its shape taken — and a summary
     * written in that window would say "no records" about a Run that took gold a second later, and
     * would go on saying it for the life of the Run.
     *
     * Two things have to be true, and that is the whole rule: **its own marks say the measuring of
     * *it* is done, and [historyBeingMeasuredFlow] says the measuring of everything it is ranked
     * against is.** Its own marks are what the passes hand back to the Run
     * ([RunnerSession.recordsScored], [RunnerSession.segmentsTimed]) plus its shape, which is marked
     * by the row existing at all ([RunShapeRow]). The other half is one read on purpose: every
     * history-wide debt is named in that one place, so a debt discovered later is added there and is
     * honoured here without this gate changing at all.
     */
    fun runSummaryFactsSettledFlow(sessionId: Long): Flow<Boolean> = combine(
        runOwnMeasuringDoneFlow(sessionId),
        historyBeingMeasuredFlow(),
    ) { itsOwnMeasuringDone, historyStillBeingMeasured ->
        itsOwnMeasuringDone && !historyStillBeingMeasured
    }.distinctUntilChanged()

    /**
     * Whether every pass has handed this one Run back its mark (#76) — the Run's half of
     * [runSummaryFactsSettledFlow].
     *
     * A Run that is gone, or still being recorded, is never done: there is nothing to measure yet,
     * and nothing to describe.
     */
    private fun runOwnMeasuringDoneFlow(sessionId: Long): Flow<Boolean> = combine(
        sessionDao.getSessionByIdFlow(sessionId),
        runShapeDao?.isShapedFlow(sessionId) ?: flowOf(true),
    ) { session, shaped ->
        session != null &&
            session.isFinished() &&
            session.recordsScored &&
            session.segmentsTimed &&
            shaped
    }.distinctUntilChanged()

    /**
     * Asks the model for a Run's summary and keeps what it says (#76).
     *
     * The prompt is built by the caller and is a pure function of stored facts
     * ([com.example.runningapp.ui.buildRunSummaryPrompt]); this is the half that consents, calls out,
     * and writes down.
     *
     * **Consent is asked twice, and both answers have to be yes.** The Run carries the answer the
     * runner gave when they pressed START ([RunnerSession.includeInAiTraining]), which is the rule
     * the coach keeps — a Run recorded under an opt-out is never sent anywhere, whatever the switch
     * says today. And the switch as it stands *now* is asked as well, which the coach has no need to
     * do: the coach only ever speaks about the Run that has just finished, while this reaches back
     * through a runner's whole history. A runner who turns sharing off and then browses their old
     * Runs has said, in the plainest way there is, that they do not want their runs sent — and every
     * old Run in the list was recorded while the switch was on.
     *
     * Testing mode counts as sharing being off, exactly as it does at START.
     *
     * A build with no model to ask is a refusal rather than a failure ([AiCoachClient.canBeAsked]):
     * a retry button is worth offering only where trying again could work.
     *
     * Nothing is written unless the model said something ([AiCoachClient.summariseRun]), so a Run
     * whose summary could not be got holds no summary rather than an empty one, and asking again is
     * a fresh ask. Asking again when one is already written *replaces* it, which is what makes the
     * runner's "write it again" mean what it says.
     *
     * A summary that is stored refreshes the history backup, and only then — a refusal or a failure
     * wrote nothing, and a snapshot for no change is a copy of the whole database for nothing. These
     * words were paid for and are kept for ever, so a Clear-storage restore from a stale snapshot
     * would cost the runner a second paid ask — or the summary altogether, if sharing has been
     * turned off since it was written and asking again would now be refused.
     */
    suspend fun writeRunSummary(sessionId: Long, prompt: String): RunSummaryOutcome {
        val summaries = runSummaryDao ?: return RunSummaryOutcome.REFUSED
        val client = aiCoachClient?.takeIf { it.canBeAsked } ?: return RunSummaryOutcome.REFUSED
        val session = sessionDao.getSessionById(sessionId) ?: return RunSummaryOutcome.REFUSED
        if (!session.isFinished() || !session.includeInAiTraining) return RunSummaryOutcome.REFUSED
        val settings = settingsRepository?.userSettingsFlow?.first() ?: return RunSummaryOutcome.REFUSED
        if (!settings.aiDataSharingEnabled || settings.testingModeEnabled) return RunSummaryOutcome.REFUSED

        val text = client.summariseRun(prompt) ?: return RunSummaryOutcome.FAILED
        summaries.put(
            RunSummaryRow(
                sessionId = sessionId,
                text = text,
                writtenAtMillis = System.currentTimeMillis(),
            )
        )
        refreshHistoryBackup?.invoke()
        return RunSummaryOutcome.WRITTEN
    }

    /**
     * The named ground one Run went over, with every rival effort at it (#71) — what the Run's
     * Segments card is built from.
     *
     * Watched rather than read once, for the reason the medals are: a Run opened straight off the
     * finish line is still being put to the Segments, and a Segment cut this morning is still
     * walking history behind the page. Empty wherever Segments are not wired, which is the same
     * thing a Run that crossed none shows.
     */
    fun segmentEffortsForRunFlow(sessionId: Long): Flow<List<RunSegmentEffortRow>> =
        segmentEffortDao?.getEffortsForRunFlow(sessionId) ?: flowOf(emptyList())

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
        //
        // And only where the claim that changed is the one the requirement is written in. A Run
        // can already hold a qualifying 5K stated long before any of this existed; letting a Mile
        // typed today re-ask the rule would graduate the Stage off that old claim, which is the
        // pass over history the rule refuses to make.
        //
        // And only where nobody else is going to read this claim for us (#297). A Run whose finish
        // sheet is still open has not been judged yet, and the settlement waiting on that sheet
        // reads every claim the Run holds — this one included — when it comes. Asking here first
        // would be the rule granting before the runner has said whether the Run was a walk, which is
        // the whole of what that wait exists for.
        //
        // "Nobody else" is two facts and not one (#318): the mark on the row says a settlement has
        // *finished*, and [beingJudged] says one is part-way through — which is a settlement that
        // has already asked the rule and will not ask it again. That KDoc is where the argument for
        // the second one lives; here it is enough that between the two there is no reader left, so
        // standing down on the row alone lost the claim for good.
        //
        // The marker also carries what that settlement is judging the Run *as*, and this reads it:
        // taking the claim's turn at the rule means taking the settlement's Run with it, stale
        // column and all ([asTheRunnerSaid]).
        //
        // **The marker is read first and the row second, and that order is the rule.** These two
        // reads are not one step and the settlement they are about runs on another thread, so the
        // one taken second is the fresher of the two — and it has to be the row. Read the row first
        // and a settlement can slip wholly between them: the row is seen unsettled, the settlement
        // then marks it and leaves the marker, and the marker read afterwards is clear, so the claim
        // stands down for a settlement that is already over and a row that will never be looked at
        // again. Taken this way round, a clear marker says no settlement was part-way through at
        // that instant, so any settlement beginning after it reads the claim — which was stored
        // before either read — and any settlement that ended before it left the mark the row read
        // second will show.
        //
        // Read rather than waited on, deliberately. Taking [settling] here would put a Gemini round
        // trip in front of a Save the runner is watching, for a lock this claim does not need. What
        // the lock is not needed for is the double grant: if the settlement and this claim both ask
        // the rule, the second is declined inside the write itself — [SettingsRepository] re-reads
        // the scope inside its own edit and a completed plan is checked again as it is stored — and
        // not by the rule's "the Stage has since moved" read, which two concurrent askers can both
        // pass. Two settlements never reach that, because [settling] serializes them; a claim and a
        // settlement can, and the writes are where it is caught.
        if (!worse) {
            val partWayThrough = beingJudged[sessionId]
            sessionDao.getSessionById(sessionId)?.let { stored ->
                if (!stored.stageSettled && partWayThrough == null) {
                    Log.d(
                        "StageRule",
                        "Run $sessionId has not been judged yet; its settlement will read this claim (#318)"
                    )
                    return@let
                }
                // Judged as the settlement in this window is judging it, and not as the column has
                // it: the Walk mark is a write that can fail, and the word the sheet gave that
                // settlement is the one thing that cannot (#297). Where no settlement is part-way
                // through there is no word to take, and the row is the only word there is.
                val judged = stored.asTheRunnerSaid(partWayThrough?.markedAsWalk)
                judged.ranUnderStageId?.let { graduateOnBestEffortRequirement(it, judged, answering = type) }
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
                // Whatever the runner said about these Runs goes with them: the id a deleted Run
                // gives up can be handed to the next Run Room writes, and a word left behind would
                // be the wrong runner's word about a different Run (#317).
                //
                // Here, and not before the delete is attempted, because a delete that throws leaves
                // the row standing — and a word dropped for a Run that is still there is the word
                // the settlement that follows was going to be judged on, which is the graduation on
                // a walk this whole change exists to stop. Nothing can be written under the freed id
                // before this: the rows have only just gone, and this runs first of everything that
                // follows the commit, uncancellable.
                runIds.forEach { theRunnersWordFor.remove(it) }
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
     * The Run that has just finished, carrying the weather it was run in — fetched here and now
     * where the after-run work has not got to it yet (#79, #83).
     *
     * That fetch is ordinarily [AfterRunRoutine]'s, but the routine books the whole Downloads
     * snapshot ahead of it and the settle path this sits on waits for neither: on an outdoor finish
     * the coach is usually asked while the fetch is still in flight, and a Run is asked about
     * exactly once and never again ([RunnerSession.stageSettled]) — so a debrief sent a moment too
     * early is a debrief that never mentions the headwind, and no later pass repairs it.
     * [retryMissingWeather] mends the row at the next launch, which is a fact for the run detail
     * page and comes far too late for the coach. So the fetch is pulled forward to here rather than
     * the settlement being made to wait on the worker: the settlement is what puts the runner's
     * next Workout on screen, and holding it behind a backup and an HTTP call would make every
     * outdoor finish wait on the weather.
     *
     * The just-finished Run alone. The two older Runs beside it in the prompt are settled history,
     * and weather missing from one of those is weather nobody could fetch rather than weather still
     * on its way.
     *
     * No fetch at all — and the Run handed straight back — for a treadmill Run, for a Run with no
     * fix to place, and for one whose weather is already stored: the same three conditions
     * [AfterRunRoutine] skips on, so whichever of the two arrives second simply finds the work done.
     *
     * [fetchAndSaveWeather] never throws, so an unreachable service leaves the field null and the
     * debrief goes out without it, exactly as it does with no fetch made at all.
     */
    private suspend fun weatheredIfTheFetchIsStillOwed(finalized: RunnerSession?): RunnerSession? {
        if (finalized == null || weatherClient == null) return finalized
        if (finalized.isTreadmill() || finalized.weatherTempC != null) return finalized
        val latitude = finalized.startLatitude ?: return finalized
        val longitude = finalized.startLongitude ?: return finalized

        fetchAndSaveWeather(finalized.id, latitude, longitude, finalized.startTime)

        // Read back from the row the fetch wrote, and take the five weather columns from it and
        // nothing else: everything else about this Run stays as it stood when it ended, which is
        // the whole of what [getAiTrainingContext] passes it in for.
        val stored = sessionDao.getSessionById(finalized.id) ?: return finalized
        return finalized.copy(
            weatherTempC = stored.weatherTempC,
            weatherFeelsLikeC = stored.weatherFeelsLikeC,
            weatherHumidityPercent = stored.weatherHumidityPercent,
            weatherWindSpeedKmh = stored.weatherWindSpeedKmh,
            weatherConditionCode = stored.weatherConditionCode
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
     *
     * The one thing about it that is not taken as it stood is the weather, which can still be on
     * its way when a Run is finished outdoors — see [weatheredIfTheFetchIsStillOwed].
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
        // The Plan as well as the Stage, because whether the runner has finished it is a fact about
        // the Plan and the Stage cannot answer it.
        val plan = TrainingPlanProvider.planHoldingStage(stageId)
            ?: throw IllegalArgumentException("Stage not found for id: $stageId")
        val stage = plan.stages.first { it.id == stageId }
        // Whether this Stage is the finished end of a finished Plan (#294). Read here rather than
        // passed in, because it is a fact about the Stage the coach is being asked about, and a
        // caller that forgot it would leave the coach aiming the runner at a bar they have cleared.
        //
        // The last Stage and no other. A completion is the end of the plan, and the coach is told
        // there is nothing after this Stage — said about a Stage there plainly is something after,
        // as an earlier Stage of a re-attached plan would be, that is a sentence the runner's own
        // plan contradicts.
        val planComplete = settingsRepository?.userSettingsFlow?.first()
            ?.planCompletion?.planId == plan.id && plan.stages.lastOrNull()?.id == stageId

        // The Stage's own Runs and no others, which is what a Stage is graduated on (#234) — see
        // [RunnerSession.ranUnderStageId].
        val storedRecentRuns = sessionDao.getLast3AiEligibleRunsOfStage(stageId)
        // The same Runs as they stand *now*: [asFinalized] is the Run that has just finished, which
        // the read above can have caught mid-write, and it stands in for one of these rows — it is
        // never another Run. Resolved once and read from twice below, so what the coach is shown and
        // what may graduate the Stage cannot describe different Runs (#275, #287).
        val finalizedRun = weatheredIfTheFetchIsStillOwed(asFinalized)
        val recentSessions = storedRecentRuns.map { stored ->
            if (stored.id == finalizedRun?.id) finalizedRun else stored
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
                },
                // How the Run felt, in the runner's own numbers and words (#78, #83). Read off the
                // same row as everything else here, which is what makes [asFinalized] carry it too:
                // the sheet asking for it is on screen while this read happens, so the Run is
                // described by what it was when it ended and never by what was typed since.
                perceivedEffort = session.perceivedEffort,
                // Blank is nothing written and a pasted essay is cut with an ellipsis on it, both
                // stated once in [noteForCoach] rather than here — the stored note stays whole, and
                // only the copy the coach is handed is bounded.
                note = noteForCoach(session.sessionNote),
                weather = session.weatherSummary()
            )
        }

        // The runner's own targets, and where a period that is still running has got to (#82, #83).
        // Read here at the moment the coach is asked, like the curves: there is nothing on the far
        // side of a sent prompt for a later emission to redraw.
        val goals = goalDao?.getAllGoalsFlow()?.first().orEmpty()
        val goalProgress = if (goals.isEmpty()) {
            // The read below is a whole pass over history, and with no goal to measure there is
            // nothing for it to answer. Skipped rather than computed and thrown away.
            emptyList()
        } else {
            goalProgressOf(
                goals = goals.map { it.toGoal() },
                runs = runVolumesFlow().first(),
                on = today,
                zone = zone
            ).map { progress ->
                AiGoal(
                    period = progress.goal.period.thisPeriod,
                    metric = progress.goal.metric.label,
                    // The card's own rounding, so the coach quotes the runner the numbers the
                    // runner is looking at ([goalAmountText]).
                    done = goalAmountText(progress.goal.metric, progress.done),
                    target = goalAmountText(progress.goal.metric, progress.goal.target),
                    unit = progress.goal.metric.unit
                )
            }
        }

        return AiTrainingContext(
            currentStageTitle = stage.title,
            graduationRequirement = stage.graduationRequirementText,
            requirementIsTheAppsToAnswer = stage.bestEffortRequirement != null,
            planComplete = planComplete,
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
                        !asFinalized.ranOn(zone).isAfter(today)
                    )
            ),
            stageWorkout = stageWorkout,
            goals = goalProgress
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
     * **When** this is asked is [settleStageForRun]'s question, and it is a separate one: this
     * decides what the Plan has to say about a Run, and that decides the moment the Run is ready to
     * be asked about. [finalizedRun] is the Run as the caller found it at that moment.
     *
     * [runType] and [finalizedRun] are passed straight through — see [evaluateAndAdjustPlan].
     */
    suspend fun settleStageAfterRun(
        stageId: String,
        runType: RunType?,
        finalizedRun: RunnerSession,
        /** The zone the runner's calendar days are in — which day a finished plan is recorded on. */
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        // Its own attempt, for the reason the record book's scoring is one: the Run is already
        // saved by the time this is called, and a graduation that cannot be written must not cost
        // the runner the coach's debrief below. `finalizeRun`'s scope has no handler of its own, so
        // an unhandled read or write here would end the process rather than skip a progression.
        try {
            graduateOnBestEffortRequirement(stageId, finalizedRun, zone = zone)
        } catch (e: Exception) {
            Log.w("StageRule", "Could not settle stage=$stageId after run ${finalizedRun.id}", e)
        }
        evaluateAndAdjustPlan(stageId, runType, finalizedRun)
    }

    /**
     * Every Run whose word from the "How did that feel?" sheet is still coming (#297).
     *
     * **A Run leaves this set only when its own word is settled**, and until it does, no settlement
     * carrying no word may judge it. A *set* and not one slot, because more than one Run can be
     * owing its word at once: the sheet is taken off screen the moment the runner answers it, and
     * the answer's writes and settlement run on afterwards on a process-wide scope, while STOP has
     * already re-armed START. A runner who saves run 1 and immediately starts and stops run 2 has
     * two Runs waiting, and a single slot would let run 2 overwrite run 1's — after which a
     * wordless settlement reaching run 1 would judge it off the `isWalk` still on its row and could
     * graduate a Stage on a walk, which cannot be taken back. One id per Run owing a word is a rule
     * that has no smaller-than-it case left.
     *
     * In memory and deliberately not stored: it says what is happening in *this* process, and a
     * process that dies takes its sheets down with it — after which nothing is still coming and the
     * launch pass is right to settle. Persisting it would turn a sheet the runner never saw again
     * into a Stage question held open for ever.
     *
     * Concurrent, because it is added to from the STOP that raises a sheet and read and cleared from
     * the settlements, which are several coroutines.
     */
    private val awaitingTheRunnersWord: MutableSet<Long> =
        Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())

    /**
     * The runner's word about a Run whose settlement could not use it, kept for the next one (#317).
     *
     * [finishSheetClosed]'s wait for the finalize is bounded, and a finalize blocked on a slow
     * recorder write outlasts it: the wait then returns a row that is still unfinished, which
     * [settleUnderSettling] rightly declines to judge. Without this, that is where the word ended.
     * The finalize landing afterwards writes the row whole — taking off the Walk mark the Save put
     * there — and settles the Run itself carrying no word, so the Run is judged off the
     * `isWalk = false` the finalize just restored and a Stage can be graduated on a walk, which
     * cannot be taken back.
     *
     * **The word is what outlives the settlement, not the gate.** Holding [awaitingTheRunnersWord]
     * closed instead would leave a Run nothing will ever settle — the sheet has been answered and
     * gone, and the gate is what every other settlement stands down for. So the gate opens exactly
     * when it always did, and what is kept is the one fact the opened gate promised was already in.
     *
     * Kept only while a settlement is still owed: written as the sheet closes, and forgotten by the
     * settlement that used it, by a Run that turns out to be settled already or gone, and by a
     * delete ([deleteRuns]) — a deleted Run's id can be handed to the next Run Room writes, and a
     * word left behind would be the wrong runner's word about a different Run.
     *
     * In memory, like [awaitingTheRunnersWord] and for the same reason: it says what is happening in
     * *this* process. What makes that safe is that the row itself is the durable copy of this word
     * — the finish sheet writes the mark on it, and `finalizeRun` reads the row back after its own
     * waits so that a mark written during them is carried into the full-row write rather than
     * undone by it (#317). A process that dies leaves the mark standing, and the launch pass judges
     * off a row that agrees with the runner.
     *
     * **What is knowingly left** is the instant inside `finalizeRun` between that read and its
     * write: a mark landing there is overwritten, and this word is then the only copy, so a process
     * dying between the write and the settlement below would lose it. It is an instant rather than
     * the seconds it used to be, and the alternative — a stored debt — would hold a Stage open for
     * ever on a word that a process which died with the sheet up will never be given.
     *
     * Null is not stored — a sheet that said nothing about the Walk is a dismissal, and then the
     * row is the only word there is ([finishSheetClosed]).
     *
     * Concurrent, because the sheet writes it and the settlements read and clear it, and those are
     * different coroutines.
     */
    private val theRunnersWordFor: ConcurrentHashMap<Long, Boolean> = ConcurrentHashMap()

    /**
     * The finish sheet has been put on screen for a Run (#297) — called as STOP is pressed, before
     * the Run has finished finalizing.
     *
     * This is the gate, and it is a gate rather than a wait on purpose: the finish cannot know how
     * long the runner will look at the sheet, and a settlement that timed out would be a promise
     * kept on a stopwatch. Registered *before* the stop is issued, so the finish that follows can
     * only ever find it already here.
     */
    fun finishSheetOpened(sessionId: Long) {
        awaitingTheRunnersWord.add(sessionId)
    }

    /**
     * The runner has finished with the sheet — Save or dismissed — so their word about the Run is
     * in and the Stage can be settled (#297).
     *
     * Dismissing counts, and has to: a runner who swipes the sheet away has said the Run was what
     * it looks like, and holding the graduation until the next launch over a sheet they declined
     * would be the app sulking. What Save adds is only that the mark, the note and any stated
     * distance are written first — the caller sees to that order, because they are its writes.
     *
     * [markedAsWalk] is the runner's word about what the Run was, carried into the settlement rather
     * than left to be read back off the column — see [settleStageForRun]. Null is "the sheet said
     * nothing about it", which is a dismissal, and then the row is the only word there is.
     *
     * **It waits for the Run to be finished**, the same wait every other door off this sheet makes
     * ([markAsWalk], [stateDistance]) and for the same reason: the sheet is on screen from the
     * moment STOP is pressed while `finalizeRun` is still writing the row. A runner who dismisses it
     * inside that second would otherwise find a Run with no end time, which is a Run nothing here
     * can judge — and the Stage would then hold until the next launch. Save reaches the wait having
     * already made it and returns on the first read.
     *
     * Called through [finishSheetAnswered], which is what sees to that order and to the gate being
     * closed whether or not the writes land.
     *
     * **The gate is opened and the word is settled as one step, under [settling].** That is the
     * whole shape of this function and the rule [awaitingTheRunnersWord] exists to keep: the gate says
     * "a word about this Run is still coming", so it may not read as open until the settlement
     * carrying that word owns the lock. Opening it first and settling after left a gap — the wait
     * below genuinely suspends — and the finish's own settlement, which carries no word, could take
     * the lock inside it, judge the Run off the `isWalk` on the row and graduate a Stage that cannot
     * be taken back (#297). Every settlement without a word tests the gate under this same lock
     * ([settleOneStage]), so there is no moment at which one can see the gate open and the word not
     * yet in.
     *
     * The wait is made *before* the lock rather than inside it, because it can last seconds and no
     * other Run's settlement should queue behind a row that is still being written. Waiting with the
     * gate still closed is what the gate is for: a settlement arriving during the wait declines and
     * leaves the debt here, which is exactly what it would have done a moment earlier.
     *
     * **The wait can run out, and then the word is kept rather than spent** (#317). A finalize
     * blocked on a slow recorder write outlasts the wait, which returns the row still unfinished —
     * a row no settlement may judge. The gate still opens, because a gate held closed over a sheet
     * that has been answered and gone is a Run nothing will ever settle; what stands in its place is
     * [theRunnersWordFor], which hands this word to the settlement that follows — the finalize's
     * own, moments later, which would otherwise judge the Run off the `isWalk = false` its full-row
     * write had just restored — which `finalizeRun` no longer does to a mark that was already on
     * the row when it read it, so this covers the narrower case of a mark written after that read.
     */
    suspend fun finishSheetClosed(
        sessionId: Long,
        markedAsWalk: Boolean? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        finalizeWaitStepMillis: Long = 250L,
    ) {
        val finalized = awaitFinalized(sessionId, finalizeWaitStepMillis)
        val settled = settling.withLock {
            // Written down before the gate opens and inside the same lock, so no settlement can see
            // the gate open and the word neither settled nor kept. The settlement below forgets it
            // again if it uses it; what it leaves standing is a word the wait ran out on (#317).
            //
            // Only where there is a Run to owe it: a wait that found no row at all is a Run that has
            // gone, and a word kept about it would be waiting for an id Room can hand to the next
            // Run written — the wrong runner's word about a different Run.
            if (markedAsWalk != null && finalized != null) theRunnersWordFor[sessionId] = markedAsWalk
            awaitingTheRunnersWord.remove(sessionId)
            finalized != null && settleUnderSettling(sessionId, zone, markedAsWalk)
        }
        // Outside the lock, for the reason [settleStageForRun] gives: the snapshot copies the whole
        // database and no other settlement should wait on that.
        if (settled) refreshHistoryBackup?.invoke()
    }

    /**
     * The runner's answer to the sheet: what the answer had to store, and then the close (#297).
     *
     * One door rather than two calls at the caller, because the order between them is a rule and not
     * a convenience — the Stage may only be settled once the answer is in, and [writes] is whatever
     * else that exit stored, a Save's effort, note and any stated distance, or nothing at all for a
     * dismissal.
     *
     * **The Walk mark is named here rather than left inside [writes]**, because it is not one write
     * among several: it is the word the settlement reads, and the whole reason the Stage waited for
     * this sheet (#297). As an anonymous statement in the block it shared the block's fate — an
     * effort or a stated distance that threw took the mark down with it, and the settlement then
     * read the `isWalk = false` still on the row and could graduate a Stage on a walk, which cannot
     * be taken back. Named, it gets its own attempt, and its value goes to [finishSheetClosed]
     * whether or not the attempt landed.
     *
     * **Nothing in the runner's answer may leave this function throwing**, which is the other reason
     * this exists. It is called on a process-wide scope whose [kotlinx.coroutines.SupervisorJob]
     * only stops one child's failure reaching its siblings — it does not handle the failure, so a
     * throw out of here reaches the default handler and takes the app down, and takes the
     * settlement with it. Failures are logged instead, because by here the sheet is off screen and
     * there is nobody to tell. That is one rule and it covers every step, so it is stated once, as
     * the guard around the whole body. Cancellation is not a failure and goes on out.
     *
     * The two guards inside the body are not a second copy of that rule; they are a different rule
     * — **a step that fails must not skip the steps after it**. The answer does as much of itself as
     * it can: an effort that throws must still let the Walk mark be attempted, and neither may cost
     * the Run its close, because a throw on the way to [finishSheetClosed] would leave
     * [awaitingTheRunnersWord] holding this Run for the life of the process — the finish has already been
     * and gone, and the launch pass runs once, so nothing left would ever settle it. Settling on
     * what did land is the smaller loss than never settling at all.
     *
     * The outer guard therefore catches only what the close itself throws, and there the loss is
     * real: the sheet state is gone and the gate may already be clear, so this Run's settlement
     * waits for the next launch's pass rather than being retried here. A retry in this process would
     * be a second copy of that pass, and a database that has just refused a read is not a database
     * worth asking twice in the same second. Closing the gate is [finishSheetClosed]'s to do and not
     * this function's, because there it is the same step as the settlement that carries the word —
     * see the rule there.
     */
    suspend fun finishSheetAnswered(
        sessionId: Long,
        markedAsWalk: Boolean? = null,
        zone: ZoneId = ZoneId.systemDefault(),
        finalizeWaitStepMillis: Long = 250L,
        writes: suspend () -> Unit,
    ) {
        try {
            try {
                writes()
            } catch (e: Exception) {
                Log.w("StageRule", "Could not store the finish sheet's answer for run $sessionId; settling on what landed", e)
            }
            if (markedAsWalk != null) {
                // Last of the answer's writes, so the snapshot it takes carries the others, and its
                // own attempt so nothing above can cost the Run its mark. [markAsWalk] refuses a
                // change of nothing itself, which is why the switch is handed over as it stands
                // rather than only when it is on: deciding that here would be the same rule twice.
                try {
                    markAsWalk(sessionId, markedAsWalk, finalizeWaitStepMillis)
                } catch (e: Exception) {
                    Log.w("StageRule", "Could not store the Walk mark for run $sessionId; settling on the runner's word", e)
                }
            }
            finishSheetClosed(sessionId, markedAsWalk, zone, finalizeWaitStepMillis)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.e("StageRule", "The finish sheet's answer for run $sessionId did not complete; the next launch's pass owes this Run its settlement", failure)
        }
    }

    /**
     * Puts a finished Run to the Plan — the app's graduation rule and then the coach — once the
     * runner can no longer change what the Run was (#297).
     *
     * **The judgement waits for the finish sheet.** A Walk graduates nothing, because a Walk holds
     * no Best Effort at all; but the Walk mark is the runner's own word and it arrives *on the
     * finish sheet*, seconds after STOP. Settling at STOP therefore settled the Run before the one
     * fact that can withdraw it from the judgement — so an outdoor activity covering a qualifying
     * 5 km could advance the Stage a moment before the app was told it was a walk, and a graduation
     * cannot be taken back. The rule now asks when the sheet resolves; while the sheet is open this
     * returns having done nothing, leaving the debt for [finishSheetClosed] to pay.
     *
     * **A Run nobody was shown a sheet for is settled here and now.** A STOP from the notification
     * opens no sheet, so there is no word still coming, and waiting for one would hold the Run's
     * graduation and the coach's debrief until the next launch.
     *
     * **[markedAsWalk] is the runner's word, and it beats the column.** The sheet hands it over
     * rather than trusting the settlement to read it back, because the read and the write are two
     * steps and only one of them can fail: a mark that could not be stored would otherwise be judged
     * as the `isWalk = false` left standing, and a Stage graduated on a walk cannot be taken back
     * (#297). The row is still what is judged in every other respect — only this one column is
     * answered by the word. Null is "nobody said", which is every caller but the sheet: the finish,
     * and the launch pass, where the row is all there is and the sheet's writes have long since
     * landed or been lost with the process.
     *
     * **The row is read back rather than handed over, and that is the change** (#231, ADR 0008).
     * The finish used to hand its own copy over so that a distance typed into the sheet could not
     * join the judgement of the Run it belonged to — a race, won by whichever of the two was
     * quicker. Waiting for the sheet dissolves the race instead of keeping it: everything the sheet
     * says is always in, because the judgement is what waited for it. What that lets in that matters
     * is the Walk mark, which is the whole point, and the runner's effort and note, which the coach
     * was always meant to have.
     *
     * It lets a stated distance in too, and that still graduates nothing — but the guard is worth
     * naming exactly, because it is not the one it looks like. A stated distance *does* reach
     * [bestEffortsOf], as the Run's `LONGEST_DISTANCE`; what it can never reach is a graduation,
     * because [BestEffortRequirement] refuses to be written at anything but a fixed distance
     * (`record.distanceMeters != null`), so the two records a distance can move are the two no
     * requirement may be written in. A treadmill Run's *fastest* efforts come only from a Stated
     * Best Effort read off the console, never from a distance over a duration. So the typo #231
     * guarded against is barred by that `require` and not by this wait — and a requirement stated in
     * a distance or a duration, if one is ever written, has to answer the question again.
     *
     * **Once.** [RunnerSession.stageSettled] is written when the settlement returns, and a Run
     * already carrying it is left alone: a graduation cannot be taken back, so a second grant is not
     * a harmless repeat. Written after rather than before, as the record book's mark is (#210), so
     * a process that dies part-way leaves the debt standing rather than losing the Run its
     * graduation for good. The cost of that choice is the narrow case where the coach's write lands
     * and the mark does not, which spends one more Gemini call at the next launch and overwrites a
     * Prescription slot with another answer about the same Run.
     *
     * **A Run still being recorded keeps its debt** rather than being judged on totals that are
     * only as much of it as has happened so far.
     *
     * **A Run finished under testing mode is settled, and settled as nothing.** Both halves decline
     * it — the rule refuses to grant and the coach refuses to be asked — and the mark still goes on,
     * so a desk test leaves no debt for a later launch to pay once testing mode is off. That is the
     * behaviour a Run finished under testing mode has always had, said once here rather than left
     * as a third copy of the check.
     *
     * **One settlement at a time**, under [settling]. Two callers can reach the same Run — the
     * finish and the sheet, when the runner answers the sheet before the finish gets here — and
     * "read the mark, then write it" is not one step: both would read a debt and both would grant.
     * The rule's own "the Stage has since moved" guard would decline the second, and the plan's
     * completion is checked again inside its write, so neither could grant twice; but the second
     * would still spend a Gemini call on a Run already judged. The lock makes the read and the write
     * one step and the question is put once.
     *
     * That same lock is what the wait above is written in terms of, and it has to be: **the gate
     * does not open until the settlement carrying the runner's word owns the lock**. The gate says a
     * word is still coming, so a settlement that finds it clear must be able to trust that the word
     * is already in — which is only true if opening the gate and settling the word are one step
     * ([finishSheetClosed]) and the gate is tested inside the lock ([settleOneStage]). Two steps with
     * anything in between is a window for this settlement, carrying no word, to judge the Run off the
     * `isWalk` on the row.
     *
     * **A sheet the runner walks away from is left waiting, deliberately.** The Stage then settles
     * whenever they come back to it, or at the next cold start — which arrives on its own, because
     * Android reclaims a backgrounded process soon enough. The alternative considered was treating
     * *leaving the app* as an answer: it settles sooner and it takes the sheet away, so a phone call
     * landing in the second after STOP would cost the runner their effort, their note and, on a
     * treadmill, the only prompt that ever asks how far they went. A stage message arriving late is
     * the smaller loss than a distance that is never asked for, and the Run's own page carries all
     * four for ever either way.
     *
     * **The one hole left** is a process that dies with the sheet on screen. The sheet is restored
     * with the Activity, but this gate is in memory and is not, so the launch pass settles the Run
     * before the restored sheet can be answered — and a Walk ticked into it lands too late, exactly
     * as it did before #297. Living with it beats the alternatives: a stored gate would hold a Stage
     * open for ever on a sheet nobody will ever answer, and the pass is what keeps a Run from never
     * being judged at all.
     */
    suspend fun settleStageForRun(
        sessionId: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        markedAsWalk: Boolean? = null,
    ) {
        // The mark is history the same way a Score is not: it is not derivable from anything the
        // snapshot holds, so a copy taken without it restores a Run that has been judged as a Run
        // that has not. Every snapshot this Run has behind it was taken before now — the after-run
        // work is booked at the finish and the feel sheet writes its own — so without this the
        // Downloads copy always says `stageSettled = false`, and a Clear-storage restore would
        // spend another Gemini call on this Run and overwrite its debrief and Prescription with a
        // second answer about it. Outside the lock, because it copies the whole database and no
        // other settlement should wait on that (#297).
        if (settleOneStage(sessionId, zone, markedAsWalk)) refreshHistoryBackup?.invoke()
    }

    /**
     * A settlement nobody has spoken for: the gate, and then the settlement itself, as one step —
     * and whether it wrote the mark (#297).
     *
     * Split from [settleStageForRun] only over who owes the snapshot: one Run settled at its own
     * door owes one each time, and the launch pass owes one for the whole pass however many Runs it
     * settles — the rule [rescueInterruptedRuns] already keeps, for the same reason.
     *
     * **This is the door for a settlement that carries no word**, which is every one but the sheet's,
     * and so it is the door that tests the gate. The test is inside [settling] and not before it,
     * because the sheet opens the gate and settles the word under that same lock: tested outside, a
     * gate cleared a moment before the word's settlement had the lock would read as "nothing is
     * coming" and let this one judge the Run off the column (#297). The sheet's own settlement goes
     * straight to [settleUnderSettling] from inside the lock it already holds — [settling] is not
     * reentrant, and there is nothing left for it to test.
     *
     * [markedAsWalk] is the runner's word where there is one — see [settleStageForRun].
     */
    private suspend fun settleOneStage(
        sessionId: Long,
        zone: ZoneId,
        markedAsWalk: Boolean? = null,
    ): Boolean {
        // A word the sheet's own settlement could not use, because its wait for the finalize ran out
        // on an unfinished row (#317). This settlement is the one that follows, so the word is its
        // to carry — and it beats the column here for the reason it beats it everywhere
        // ([settleStageForRun]): the column is a write that can fail, and this is the case where it
        // was undone, by the very finalize that called this.
        val word = markedAsWalk ?: theRunnersWordFor[sessionId]
        if (markedAsWalk == null && word != null) putTheWordBackOnTheRow(sessionId, word)
        return settling.withLock {
            if (sessionId in awaitingTheRunnersWord) {
                Log.d("StageRule", "Run $sessionId still has its finish sheet open; the Stage waits (#297)")
                return@withLock false
            }
            settleUnderSettling(sessionId, zone, word)
        }
    }

    /**
     * Writes a kept word back onto the row the finalize overwrote, before the Run is judged (#317).
     *
     * Judging on the word is only half of what the word is owed. The finalize's full-row write also
     * takes the mark off the Run's own page, and it scores the Run against the record book a moment
     * before this — so a Walk left unmarked keeps medals no Walk may hold. [markAsWalk] is the one
     * door that writes the mark and mends everything standing on it, so it is the door used here;
     * it refuses a change of nothing itself, so a word the row already agrees with costs nothing.
     *
     * Outside [settling] and before it, because the mend walks the record book and no other Run's
     * settlement should queue behind that. Guarded, because the judgement below is the irreversible
     * half and must not be lost to a mend that failed: a mark that cannot be written leaves the row
     * disagreeing with the runner, which is the state the word is carried separately for.
     */
    private suspend fun putTheWordBackOnTheRow(sessionId: Long, markedAsWalk: Boolean) {
        // Only onto a row the finalize has finished writing. A Run still being recorded is a Run
        // this mend would be spent on twice — the full-row write is still to come and would take the
        // mark straight back off — and [markAsWalk] would sit out its own wait to do it. The word
        // is kept either way, and the settlement that follows the finalize is the one that mends.
        if (sessionDao.getSessionById(sessionId)?.isFinished() != true) return
        try {
            markAsWalk(sessionId, markedAsWalk)
        } catch (e: Exception) {
            Log.w("StageRule", "Could not put run $sessionId's Walk mark back on its row; judging on the word", e)
        }
    }

    /**
     * The settlement itself, with [settling] already held, and whether it wrote the mark (#297).
     *
     * Callers own the lock so that the gate and the settlement can be one step — see
     * [finishSheetClosed] for the rule and [settleOneStage] for the gate.
     *
     * [markedAsWalk] is the runner's word where there is one — see [settleStageForRun].
     */
    private suspend fun settleUnderSettling(
        sessionId: Long,
        zone: ZoneId,
        markedAsWalk: Boolean?,
    ): Boolean {
        val run = sessionDao.getSessionById(sessionId)
        // A Run still owing a settlement is the one case that keeps the word: it is the case the
        // word is kept for (#317). A Run that is gone, or already judged, owes nothing and no later
        // settlement may be handed a word about it.
        if (run == null || run.stageSettled) {
            theRunnersWordFor.remove(sessionId)
            return false
        }
        if (!run.isFinished()) {
            Log.d("StageRule", "Run $sessionId is not finished; leaving its Stage owing a settlement")
            return false
        }
        // From here on this Run is being judged, and it says so — because from here on a claim
        // arriving has no reader but itself (#318). Set before the rule is asked and cleared only
        // once the mark is on the row, so the two together cover every instant in which this
        // settlement's judgement is made and the row does not yet say so.
        //
        // **It carries the runner's word and not just the Run's id**, because a claim landing in
        // this window now judges the Run itself and must judge the same Run this settlement is
        // judging. The word beats the column (#297) and the column is a write that can fail, so a
        // marker that named only the id would hand the claim the stale `isWalk = false` the
        // settlement is deliberately ignoring — and a Stage graduated on a walk cannot be taken
        // back.
        beingJudged[sessionId] = UnderJudgement(markedAsWalk)
        try {
            val stageId = run.ranUnderStageId
            if (stageId != null) {
                // Judged on the runner's word about the Walk, not on the column — the column is a
                // write that can fail and the word cannot (#297). Everything else is the row as
                // stored, and where the two agree this is the row itself.
                settleStageAfterRun(
                    stageId,
                    runTypeOf(stageId, run.ranUnderWorkoutId),
                    run.asTheRunnerSaid(markedAsWalk),
                    zone
                )
            }
            // Marked even where there was no Stage to settle: the column says the question has been
            // put and cannot be put again, and a Run that ran under no Stage has had its answer.
            // With whatever mark the row still owes, as one step — see [settleAndOweAnyWalkMark].
            settleAndOweAnyWalkMark(sessionId, markedAsWalk)
            // And only now is the word spent (#317). Forgotten before the judgement it would have
            // been lost by a judgement that threw — which leaves the Run owing a settlement its row
            // still says it owes, and the next one to pay it would have nothing but the column the
            // word exists to beat.
            theRunnersWordFor.remove(sessionId)
        } finally {
            // A settlement that threw before the mark landed leaves the Run owing one, and a claim
            // reaching it after that is right to stand down again: the launch pass will read it.
            // So this is cleared however the settlement ended, and the row is what says which of
            // the two happened.
            beingJudged.remove(sessionId)
        }
        return true
    }

    /**
     * Marks the Run settled, and writes down any Walk mark its row still owes, as one step (#371).
     *
     * The judgement is made on the runner's word and the row is written separately, and the two
     * attempts at that write are both guarded — a mend that fails must not cost the Run the
     * judgement, which is the irreversible half ([putTheWordBackOnTheRow],
     * [finishSheetAnswered]). So a settlement can arrive here right about the Stage and holding a
     * word the column disagrees with, and the mark below is what makes that disagreement permanent:
     * from here the Run is settled, and every launch pass reads it as dealt with. History, the
     * fitness figures, the record book and the Segments then go on treating a Walk as a Run for
     * ever, and the runner's only remedy is a switch they have no reason to know they need to touch.
     *
     * **One transaction where there is a debt, and a bare write where there is not** — the argument
     * for the first is [WalkMarkDebtRow]'s and is not repeated here.
     *
     * **The row is read again here** rather than reused from the top of [settleUnderSettling],
     * because a mark landing between the two is a mark that is on the row: [settling] holds off
     * other settlements and holds off nothing else, and the runner can reach the switch on the Run's
     * own page while a Long Run's judgement is inside a Gemini round trip. The read is still outside
     * the transaction, and the narrow window that leaves is harmless in both directions: a mark
     * landing after it that *agrees* with the word raises a debt the pass pays as a no-op and drops,
     * and one that disagrees is the column, which the word beats anyway (#297).
     *
     * **A debt that cannot be written throws, and is meant to.** The write it replaces was a bare
     * one that threw the same way, and everything downstream is built on that: the word is spent
     * only once the judgement returns (#317), so a settlement that throws here leaves the Run owing
     * one, with the word still standing for the next pass to judge on. Swallowing the failure and
     * marking the Run settled anyway would spend the word on a judgement that was never recorded —
     * and the next pass would then judge the same Run off the `isWalk = false` this one was
     * deliberately ignoring, which is a Stage graduated on a walk.
     *
     * **Only where there is a word and the row disagrees with it.** No word is every settlement but
     * the sheet's, and there the column is all there is and is by definition what the Run says it
     * is. A row that is gone owes nothing to anybody. And a word the row already agrees with is the
     * ordinary case — the mark landed — which is why this is a comparison and not "the mend threw":
     * both attempts at the write fail the same way and are guarded in different places, and stating
     * the rule at the judgement covers whichever of them it was.
     */
    private suspend fun settleAndOweAnyWalkMark(sessionId: Long, markedAsWalk: Boolean?) {
        val owedMark = markedAsWalk?.takeIf { word ->
            sessionDao.getSessionById(sessionId)?.isWalk?.let { onTheRow -> onTheRow != word } == true
        }
        // The ordinary settlement is the bare write it has always been. Nothing is owed, so there is
        // nothing to commit with it, and wrapping it anyway would put two new ways to fail
        // ([WalkMarkDebtDao.owe] and the read above) in front of every judgement the app makes, for
        // the sake of the one in ten thousand that needs them.
        if (owedMark == null) {
            sessionDao.setStageSettled(sessionId)
            return
        }
        Log.w(
            "Walk",
            "Run $sessionId is being judged as ${if (owedMark) "a Walk" else "a Run"} and its row " +
                "still disagrees; the mark is owed and the next launch pays it (#371)"
        )
        inTransaction {
            walkMarkDebtDao?.owe(WalkMarkDebtRow(sessionId = sessionId, isWalk = owedMark))
            sessionDao.setStageSettled(sessionId)
        }
    }

    /**
     * Every Run a settlement has begun judging and has not yet marked settled (#318).
     *
     * The pair to [RunnerSession.stageSettled] and not a substitute for it: the column says a
     * settlement has *finished*, this says one is part-way through. A Stated Best Effort saved in
     * between reads a row that still says unsettled, and the settlement that would have read the
     * claim has already asked the rule and will never ask again — so without this the claim stood
     * down for a reader that no longer existed, and the mark that followed put the Run beyond the
     * launch pass too. The window is not a few instructions: on a Long Run it is a Gemini round
     * trip, beginning the moment the finish sheet disappears, which is exactly when the runner is
     * free to type a treadmill's console into the Run's page.
     *
     * In memory, like [awaitingTheRunnersWord] and for the same reason: it says what is happening in
     * *this* process, and a process that dies mid-settlement leaves a Run whose row still owes a
     * settlement, which is what the launch pass is for.
     *
     * Concurrent, because the settlements write it and a claim on the runner's own screen reads it,
     * and those are different coroutines. Read rather than locked against — see [stateBestEffort]
     * for why a claim may not wait out a settlement.
     */
    private val beingJudged: ConcurrentHashMap<Long, UnderJudgement> = ConcurrentHashMap()

    /**
     * What a settlement part-way through is judging its Run as (#318) — the value side of
     * [beingJudged].
     *
     * A wrapper and not the bare [Boolean] because the word has three states and a map value has
     * two: absent is "no settlement is part-way through", and present-with-null is "one is, and the
     * sheet said nothing about the Walk", which is a dismissal and leaves the row the only word
     * there is ([finishSheetClosed]).
     */
    private data class UnderJudgement(
        /** The runner's word about the Walk, exactly as [settleUnderSettling] was given it. */
        val markedAsWalk: Boolean?,
    )

    /**
     * This Run as the runner said it was, where their word and the column disagree (#297, #318).
     *
     * Stated once because two readers now need it: the settlement that was handed the word, and a
     * claim landing while that settlement is part-way through, which takes the word back off
     * [beingJudged]. The column is a write that can fail and the word cannot, so where there is a
     * word it wins; null is no word, and then the row stands as stored.
     */
    private fun RunnerSession.asTheRunnerSaid(markedAsWalk: Boolean?): RunnerSession =
        if (markedAsWalk != null && markedAsWalk != isWalk) copy(isWalk = markedAsWalk) else this

    /**
     * Held while a Run is being put to the Plan, so the debt is read and paid as one step (#297).
     *
     * Across Runs and not per Run, which is broader than it strictly has to be and costs nothing:
     * a settlement is a couple of reads and at most one Gemini round trip, and two Runs are only
     * ever settled at once by the launch pass, which walks them one at a time anyway.
     */
    private val settling = Mutex()

    /**
     * The kind of Run a Run was, recovered from the Workout it followed (#297).
     *
     * The finish had it to hand — it is the Workout's own [WorkoutTemplate.runType], read off the
     * config the Run was pinned with at START — and a settlement paid at the next launch has only
     * the row. Both must reach the same answer or a Long Run settled late would slip past the gate
     * that decides whether the coach is asked at all (#176). They do, because the row's
     * [RunnerSession.ranUnderWorkoutId] is written from that same Workout.
     *
     * Null is "no Workout recorded" — a Run with no plan attached, one that skipped today's plan, or
     * one recorded before v30 — and null is what the gate reads as "not a Run the coach adjusts".
     */
    private fun runTypeOf(stageId: String, workoutId: String?): RunType? {
        if (workoutId == null) return null
        return TrainingPlanProvider.stageById(stageId)
            ?.workouts?.firstOrNull { it.id == workoutId }
            ?.runType
    }

    /**
     * Settles the Stage of every finished Run the finish left owing one, at launch (#297).
     *
     * The finish sheet is what normally closes the question, and it is in the app's process: a
     * process killed between STOP and the sheet — or a runner who walked away from a sheet the next
     * launch will not show again — leaves a Run finished, in history, and never put to the Plan.
     * This is the launch that puts it.
     *
     * **Not a pass over history.** Every Run recorded before this shipped arrives already settled
     * (`MIGRATION_30_31`), so the list can only hold Runs finished since — and the rule itself still
     * declines any Run recorded under a Stage the runner has since left, which is what a Run old
     * enough to be a surprise will almost always be. Forwards only is ADR 0016's, and this keeps it.
     *
     * One Run at a time, each marked as its settlement lands, so a pass cut short keeps what it paid
     * for. Failures are logged and never thrown: a Stage that cannot be settled is not a reason to
     * take the app down on the way to the first screen.
     */
    suspend fun settleStagesMissedAtTheFinish(zone: ZoneId = ZoneId.systemDefault()) {
        val sessionIds = sessionDao.getSessionIdsOwingStageSettlement()
        if (sessionIds.isEmpty()) return
        var settled = 0
        sessionIds.forEach { sessionId ->
            try {
                if (settleOneStage(sessionId, zone)) settled++
            } catch (e: Exception) {
                Log.w("StageRule", "Could not settle the Stage of run $sessionId; leaving it for next launch", e)
            }
        }
        Log.d("StageRule", "Settled the Stage of $settled of ${sessionIds.size} run(s) the finish had missed")
        // Once for the pass, and only if a mark was written: the snapshot is a copy of the whole
        // database, so a pass that settled ten Runs owes one copy and a pass that settled none owes
        // nothing — the rule [rescueInterruptedRuns] keeps. What is owed is the same as at the
        // single Run's own door: a snapshot taken before these marks restores Runs that have been
        // judged as Runs that have not, and each would be judged again (#297).
        if (settled > 0) refreshHistoryBackup?.invoke()
    }

    /**
     * Puts back every Walk mark a settlement left owed, at launch (#371).
     *
     * The debt is raised by the settlement that judged a Run on a word its row disagreed with, in
     * that settlement's own transaction ([settleAndOweAnyWalkMark]), and this is the pass that pays
     * it. Until it does, the Run's own page, the fitness figures, the record book and the Segments
     * all describe a Run the runner has already told the app was a Walk.
     *
     * **Through [markAsWalk] and not through the column**, because putting the word back is never
     * one write: a Walk hands its medals back to the record book and its times back to every Segment
     * leaderboard it stands on, and unmarking one measures both again. That door is the only place
     * those mends are stated, and it discharges the debt itself, in the transaction that writes the
     * mark — so this pass never deletes a row it has not paid for, and a row whose mark somebody
     * beat it to costs one no-op.
     *
     * **It runs at launch and only at launch, so the ordinary debt is paid one launch late.** The
     * mend that fails does so at the finish of a Run, which is after this pass has read its list —
     * so the Run's own page says "run" until the next cold start. Knowingly kept: an in-process
     * retry would be a second copy of this pass, and a database that has just refused a write is not
     * one worth asking twice in the same second (the rule [finishSheetAnswered] keeps). Android
     * reclaims a backgrounded process soon enough that the next launch is not far away.
     *
     * One Run at a time, each discharged as its mark lands, so a pass cut short keeps what it paid
     * for. Failures are logged and never thrown, the rule every launch pass here keeps: a mend that
     * fails again is not a reason to take the app down on the way to the first screen, and the debt
     * it leaves standing is the next launch's — which is the whole point of writing it down.
     *
     * **The history snapshot is not refreshed**, and deliberately: [markAsWalk] does not refresh it
     * at the runner's own door either, so a pass that did would be a rule this door invented for
     * itself. A snapshot holding the unmarked row is restored still owing this debt, because the
     * debt is in the same file — and this pass runs at the launch that follows.
     */
    suspend fun payWalkMarkDebts() {
        val owed = walkMarkDebtDao?.owed().orEmpty()
        if (owed.isEmpty()) return
        var paid = 0
        owed.forEach { debt ->
            try {
                markAsWalk(debt.sessionId, debt.isWalk)
                // And the debt is discharged here, not only inside [markAsWalk]'s own transaction.
                // That transaction covers the mark that changes something, which is this pass's
                // ordinary case; this covers the one it does not — a row that already agrees, which
                // [markAsWalk] refuses as a change of nothing. That happens when the runner has
                // reached the switch on the Run's own page first, or when a previous pass's process
                // died between its mark and this delete. Without it such a debt would stand for
                // ever, retried at every launch for the life of the app. Doing it here rather than
                // at that early return is what keeps every *other* caller of [markAsWalk] — the
                // feel sheet saving an unchanged switch, above all — costing no write at all.
                walkMarkDebtDao?.forgetDebtFor(debt.sessionId)
                paid++
            } catch (cancellation: CancellationException) {
                // Not a failure, and not this pass's to swallow: the runner has backed out of the
                // Activity this scope belongs to. Caught by name and rethrown so the loop stops
                // where it is rather than logging a "failure" per remaining Run and carrying on
                // inside a cancelled scope — the debts it has not reached are still written down.
                throw cancellation
            } catch (e: Exception) {
                Log.w("Walk", "Could not put run ${debt.sessionId}'s Walk mark back on its row; leaving it for next launch", e)
            }
        }
        Log.d("Walk", "Put the Walk mark back on $paid of ${owed.size} run(s) whose settlement could not")
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
     *
     * **On the plan's last Stage it records a Plan Completion instead of advancing** (#294). There
     * is no Stage to move to, so what is granted is the end of the plan: the plan, the day and the
     * time, written once and never again, beside the sentence that says so. The runner keeps that
     * Stage, its Workouts and their standing Prescription — see [PlanCompletion].
     *
     * [answering] is the record a single stated claim just changed, where that is what prompted the
     * ask: the rule then declines unless it is the record the requirement is written in, so an
     * unrelated claim cannot cash in evidence the Run has held all along. Null is "the Run itself
     * just finished" — the finalize path, where every claim it holds is equally new.
     */
    private suspend fun graduateOnBestEffortRequirement(
        stageId: String,
        run: RunnerSession,
        answering: RecordType? = null,
        /**
         * The zone the runner's calendar days are in. Only a finished plan reads it, for the day it
         * records (#294) — everything else here is arithmetic on seconds.
         */
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        val settingsRepo = settingsRepository ?: return false
        val plan = TrainingPlanProvider.planHoldingStage(stageId) ?: return false
        val stageIndex = plan.stages.indexOfFirst { it.id == stageId }
        val stage = plan.stages[stageIndex]
        // The Stage's requirement is a judgement, so it stays the coach's — stage 1's "4 weeks of
        // consistent Zone 2 training" is met by no measurement this could take.
        val requirement = stage.bestEffortRequirement ?: return false
        if (answering != null && answering != requirement.record) return false

        val settings = settingsRepo.userSettingsFlow.first()
        // Testing mode erases the coach's work and blocks it from writing more; a Stage advanced
        // under it would be the one write from a desk test that outlives the desk test.
        if (settings.testingModeEnabled) return false
        // The Run was recorded under a Stage the runner has since left (#234): its evidence belongs
        // to that Stage and answers nothing about this one. The same guard [evaluateAndAdjustPlan]
        // makes, for the same reason.
        if (stageId != settings.activeStageId) return false
        // Deliberately *not* gated on [RunnerSession.includeInAiTraining]. That switch is consent to
        // send this Run to Gemini — "AI training data sharing" is what Settings calls it — and this
        // rule sends nothing anywhere: it reads the Run's own Best Effort and writes the app's own
        // words. Refusing here would mean a runner who never turns AI sharing on can never leave
        // stage 2, because the coach is now forbidden from granting a requirement written in
        // numbers (ADR 0016): a privacy choice would silently become a plan that cannot progress.

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
            // A failed Test states the gap and changes nothing else (#292). Only a Test says it:
            // any Run can hold a Best Effort short of the bar, and telling a runner they were
            // "2:41 off" after an easy Tuesday is a verdict on a run that was never an attempt.
            //
            // It reaches into nothing. The Test is a Quality Run, so the coach is not asked about
            // it at all (ADR 0006) and no Prescription of any kind is written; this is one sentence
            // in the same slot the app writes a graduation into, and the effort it names has
            // already reset the three weeks by being in history.
            val testWorkoutId = stage.testWorkout?.id
            if (testWorkoutId != null && run.ranUnderWorkoutId == testWorkoutId) {
                settingsRepo.setLatestDebrief(
                    missedTestMessage(requirement, seconds),
                    DebriefAuthor.APP,
                    CoachWriteScope(settings.activePlanId, settings.activeStageId)
                )
            }
            return false
        }

        val nextStage = plan.stages.getOrNull(stageIndex + 1)
        val scope = CoachWriteScope(settings.activePlanId, settings.activeStageId)
        if (nextStage == null) {
            // The last Stage of the plan: the runner has finished the whole thing (#294).
            //
            // Nothing to advance to, and the Prescription is deliberately left standing — clearing
            // it would delete the runner's standing numbers and leave them in a Stage that never
            // moved. They stay in this Stage, running its Workouts; what changes is that it is
            // recorded as finished, so the screen stops calling it something to achieve and the
            // coach stops being told to aim them at a time they have already run.
            //
            // Once: a Plan already recorded as complete is not completed again, so a second
            // qualifying Run does not move the recorded day or time and does not congratulate the
            // runner twice. Checked here so the rule plainly declines rather than granting into a
            // write that quietly does nothing, and checked again inside the write itself
            // ([SettingsRepository.completePlan]), where nothing can change between the two.
            if (settings.planCompletion?.planId == plan.id) {
                Log.d(
                    "StageRule",
                    "Run ${run.id} clears the bar of stage=$stageId again, but plan=${plan.id} is " +
                        "already complete; nothing to grant (#294)"
                )
                return false
            }
            settingsRepo.completePlan(
                completion = PlanCompletion(
                    planId = plan.id,
                    // The day of the Run, not of this write, and the Run's own day rather than its
                    // start re-read here (#304). They are the same afternoon except when they are
                    // not — a Run finished at 00:05, a stated Best Effort typed the next morning,
                    // a runner who has since flown — and the fact being recorded is about the Run.
                    // This is the one day in the app that is never re-derived, so [zone] is only
                    // ever the fallback for a Run recorded before v32.
                    completedOnEpochDay = run.ranOn(zone).toEpochDay(),
                    // Whole seconds, as a Best Effort is ranked and as the runner reads it off a
                    // clock. The time is theirs, never the bar it was enough for.
                    seconds = seconds.roundToInt(),
                ),
                message = planCompleteMessage(stage.title, requirement, seconds, plan.name),
                scope = scope,
            )
            Log.i(
                "StageRule",
                "Run ${run.id} is worth ${seconds.toLong()}s at ${requirement.record} and finishes " +
                    "the last stage=$stageId of plan=${plan.id} (#294)"
            )
            return true
        }
        // Written by the app and not by the coach. This decision is already made, and handing a
        // made decision to a model is inviting it to editorialise its way into disagreeing with a
        // fact; it also means the graduation still lands offline and with no Gemini key.
        //
        // And stamped as the app's, so the screen says so too (#296): the slot is shared with the
        // coach's debrief and the card names whoever is in it. Told it is the coach's, a runner with
        // AI sharing switched off is congratulated by a coach they never turned on.
        //
        // The message and the move go in one write, which is [SettingsRepository.graduateStage]'s
        // rule and matters most here: since #318 this can be running beside the settlement's own
        // coach round trip, and a coach reply landing between two writes would overwrite the
        // congratulation with words about the Stage the runner is being moved off.
        settingsRepo.graduateStage(
            nextStage.id,
            graduationMessage(stage.title, requirement, seconds, nextStageTitle = nextStage.title),
            DebriefAuthor.APP,
            scope
        )
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
        nextStageTitle: String,
    ): String = "${stageComplete(stageTitle, requirement, seconds)} Next up: $nextStageTitle."

    /**
     * What the runner is told when the Stage they just finished was the last one: the same fact, and
     * then the end of the plan said out loud (#294).
     *
     * The closing sentence goes exactly where "Next up" would. Until now the *absence* of "Next up"
     * was the only signal that anything had ended, and silence at the one moment the whole plan
     * exists to produce is the failure this ticket is about.
     *
     * The plan's own name, as the runner chose it off the Training Plan screen, rather than a
     * shortened one: a rule for trimming a name is a rule that gets a name wrong.
     */
    private fun planCompleteMessage(
        stageTitle: String,
        requirement: BestEffortRequirement,
        seconds: Double,
        planName: String,
    ): String =
        "${stageComplete(stageTitle, requirement, seconds)} That's the whole plan: $planName, done."

    /** The half both messages open with: the time the runner ran, and what it just finished. */
    private fun stageComplete(
        stageTitle: String,
        requirement: BestEffortRequirement,
        seconds: Double,
    ): String {
        val distance = requirement.distanceLabel
        return "You ran $distance in ${asClock(seconds)}. $stageTitle complete."
    }

    /**
     * What the runner is told when the Test they just ran did not clear the bar (#292): the number
     * they ran, and how far off it was.
     *
     * The gap is measured to the slowest time that would have passed
     * ([BestEffortRequirement.withinSeconds]) — the time they have to reach, which on a bar written
     * as "under 30 minutes" is 29:59 and not 30:00. It is stated and nothing more: no
     * encouragement, no advice, and nothing about the next Run, because a Test that came up short
     * is a measurement and the plan does not move on it.
     */
    private fun missedTestMessage(requirement: BestEffortRequirement, seconds: Double): String {
        val distance = requirement.distanceLabel
        val gap = (seconds - requirement.withinSeconds).coerceAtLeast(0.0)
        return "You ran $distance in ${asClock(seconds)}. ${asClock(gap)} off the bar."
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
     * [finalizedRun] is the Run this evaluation is about, as its row stood when the Run was put to
     * the Plan — which is once the finish sheet had closed (#297), not at STOP. So everything the
     * runner typed into that sheet is in it: the Walk mark, which is the point, and their effort,
     * note and any stated distance along with it. The row is handed over rather than looked up here
     * because *which* Run this is about must not be guessed at: this reads the last three Runs out
     * of the database on its way to the coach, and "the most recent finalized session" stops being
     * this Run the moment a restored future-dated row or a clock moved back can sort after it.
     *
     * The sheet used to be a race — the judgement was made at STOP and a number typed quickly
     * enough would join it, so the row was frozen to keep it out (#231, ADR 0008). Waiting for the
     * sheet dissolves that race rather than freezing against it: the sheet's answers are always in,
     * and a stated distance still graduates nothing by itself, because a treadmill Run's Best Effort
     * comes only from a Stated Best Effort read off the console and never from a distance over a
     * duration ([bestEffortsOf]).
     *
     * **A judgement made once, and never replayed.** A distance or a mark that arrives *later* —
     * on the Run's own page, an hour or three weeks on — does not re-run this: it is a judgement
     * about one Run under the Stage in force at that moment, which the Run writes down for itself
     * ([RunnerSession.ranUnderStageId], #234) so it can be shown to that Stage and to no other. What
     * a later statement buys the coach is every evaluation *after* it — which is where an indoor
     * winter was going missing.
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
            // The Run this evaluation is *about*, asked of the row that was handed over rather than
            // of whichever row sorts latest. Consent belongs to a Run, and "the most recent
            // finalized session" is only the same Run while no other row can sort after it — a
            // restored future-dated session or a clock moved back is enough to make them different,
            // and then an opted-out Run's context would be sent on a shareable row's say-so. The
            // fallback stands for the callers that hand over no Run (#290).
            val consentingRun = finalizedRun ?: sessionDao.getMostRecentFinalizedSession()
            if (consentingRun?.includeInAiTraining == false) {
                Log.d(
                    "AiCoach",
                    "Skipping AI evaluation: the run is excluded from AI training. stageId=$stageId"
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
                val plan = TrainingPlanProvider.planHoldingStage(stageId)

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
                // forward ([SettingsRepository.graduateStage]), so a graduation
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
                    // The coach's own words, come back from Gemini about a Stage whose requirement
                    // is a judgement — so the card names the coach over them (#296). Written with
                    // the move and not before it: this lock holds off a delete, and it holds off no
                    // other writer of the same slot ([SettingsRepository.graduateStage]).
                    settingsRepo.graduateStage(
                        nextStageId,
                        clampedResponse.coachMessage,
                        DebriefAuthor.COACH,
                        scope
                    )
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
