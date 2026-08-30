package com.example.runningapp.training

import com.example.runningapp.isBeyondAnyonesToday
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * How many weeks of a Stage's training record the coach is shown one by one (#289).
 *
 * A bound on the prompt and not on the answer: the record's length and its total are stated
 * separately ([StageTrainingRecord.weeksTrained], [StageTrainingRecord.qualifyingRuns]), so a Stage
 * that has run longer than this is never described as a shorter one. Twelve because the longest
 * requirement any plan here writes is four weeks, and three times that is room for a runner to have
 * stayed in a Stage a good while without the list becoming a wall of numbers.
 */
private const val WEEKS_SHOWN = 12

/**
 * One training week of a Stage: the Monday it began on, and how many of the Stage's qualifying Runs
 * fell in it (#289).
 *
 * A week nobody ran in is a week with zero, kept rather than left out — a gap is exactly what a
 * requirement about *consistent* training is asking about, and a list with the empty weeks removed
 * would draw a fortnight off as two weeks in a row.
 */
data class StageWeek(
    val startingOn: LocalDate,
    val qualifyingRuns: Int,
)

/**
 * The Stage's training record: what the app has actually recorded under this Stage, week by week
 * (#289).
 *
 * It exists because the coach is shown at most three Runs
 * ([com.example.runningapp.data.SessionDao.getLast3AiEligibleRunsOfStage]) and every eligible
 * session competes for those three slots, while only a Long Run triggers an evaluation at all
 * ([com.example.runningapp.isCoachAdjusted]). A runner doing a Long, an Easy and a Walk each week
 * therefore never presents more than about a week of training, and a requirement written in weeks —
 * "4 weeks of consistent Zone 2 training" — can never be confirmed from the window it is judged in.
 * Worse than not granting: the coach reads the thin window as a thin history and tells the runner
 * their block is only just beginning.
 *
 * So the counting is done here rather than left to the model. What the coach is handed is a fact the
 * app measured — the same Runs the graduation guard would accept as evidence, counted — and the
 * judgement it is asked for is the one the plan actually leaves open: whether that record is
 * *consistent*. See
 * [ADR 0019](docs/adr/0019-the-app-counts-the-training-the-coach-judges-the-consistency.md), which
 * also records the three designs this one was chosen over.
 *
 * [daysSinceFirstRun], [weeksTrained] and [qualifyingRuns] describe the whole Stage. [weeks] is the tail
 * of it, at most [WEEKS_SHOWN] long.
 */
data class StageTrainingRecord(
    /** The day the Stage's first qualifying Run fell on, or null when it has had none. */
    val firstRunOn: LocalDate?,
    /**
     * How many days have passed since the first qualifying Run: zero on the day of it, one the day
     * after. Zero when there has been no qualifying Run at all.
     *
     * Elapsed and not inclusive, because [weeksTrained] divides it and a graduation cannot be taken
     * back. Counting the first day as one puts four weeks on the board on day 28 — twenty-seven
     * days after training began — and a week the runner has not finished is not a week they trained.
     *
     * Measured to today and not to the last Run, because a Stage whose training stopped three weeks
     * ago is not a three-weeks-shorter Stage — it is a Stage with three empty weeks on the end, and
     * that is the half of "consistent" a total can never say.
     */
    val daysSinceFirstRun: Int,
    /** Every qualifying Run of the Stage, counted — including any in weeks [weeks] does not list. */
    val qualifyingRuns: Int,
    /** The most recent [WEEKS_SHOWN] weeks or fewer, oldest first, ending with the week in progress. */
    val weeks: List<StageWeek>,
    /**
     * How many Monday-starting weeks the record touches, first qualifying Run's week through the
     * week in progress. Only ever used to say whether [weeks] is the whole record or its tail.
     *
     * It is deliberately not told to the coach as a length of training, because it is not one: a
     * calendar week turns over on a Monday whatever day the runner started on. See [weeksTrained].
     */
    val calendarWeeksSpanned: Int,
) {
    /** Nothing to tell the coach: this Stage has no qualifying Run behind it at all. */
    val isEmpty: Boolean get() = weeks.isEmpty()

    /**
     * How many FULL weeks of training the record covers: whole seven-day weeks elapsed since the
     * first qualifying Run. Zero until the seventh day after it, four on the twenty-eighth.
     *
     * This, and never the number of week rows in [weeks], is what a requirement written in weeks is
     * answered from — [calendarWeeksSpanned] counts buckets, not training. A first Run on a Sunday
     * followed by Runs on the next three Mondays touches four buckets fifteen days in, and a coach
     * handed that as "four weeks" could grant an irreversible graduation nearly a fortnight early.
     */
    val weeksTrained: Int get() = daysSinceFirstRun / 7

    /**
     * Whether [weeks] is only the tail of the record — a Stage longer than [WEEKS_SHOWN] weeks.
     *
     * Said to the coach rather than left to be noticed, because the listed weeks then add up to
     * less than [qualifyingRuns] and a model handed a mismatch nobody explained will reconcile it
     * itself — most likely by reading the smaller number as the true one, which is the whole fault
     * this record exists to undo.
     */
    val weeksAreATail: Boolean get() = weeks.size < calendarWeeksSpanned

    companion object {
        val NONE = StageTrainingRecord(
            firstRunOn = null,
            daysSinceFirstRun = 0,
            qualifyingRuns = 0,
            weeks = emptyList(),
            calendarWeeksSpanned = 0,
        )
    }
}

/**
 * Build the Stage's training record from the days its qualifying Runs fell on (#289).
 *
 * [days] is one entry per qualifying Run — the Stage's own structured, non-Walk, shareable Runs, as
 * chosen by `getAiEvidenceRunDaysOfStage`. Which Runs qualify is decided in the query rather than
 * here, so the count and the graduation guard's own filter cannot drift apart.
 *
 * A Run dated further ahead than any clock could put it is dropped, exactly as [weeklyVolumeOf]
 * drops one: a phone whose clock has slipped by months must not add empty months to the record and
 * report a Stage as mostly untrained.
 */
fun stageTrainingRecordOf(
    days: Iterable<LocalDate>,
    through: LocalDate,
): StageTrainingRecord {
    val counted = days.filterNot { it.isBeyondAnyonesToday(through) }
    val firstRunOn = counted.minOrNull() ?: return StageTrainingRecord.NONE
    val counts = counted.groupingBy { it.mondayOfWeek() }.eachCount()

    // Through the week the runner is in now, or the week of the last Run where that is later — a
    // Run one day ahead of the phone can fall on the far side of a Monday, and a Run counted in
    // [qualifyingRuns] and then left out of the range would be a total no week accounts for.
    val weeks = weeksFrom(firstRunOn, maxOf(through, counted.max()))
        .map { week -> StageWeek(week, counts[week] ?: 0) }

    return StageTrainingRecord(
        firstRunOn = firstRunOn,
        // Elapsed days, not touched buckets and not an inclusive span: the length of training the
        // coach is given has to be one neither the calendar nor an off-by-one can inflate.
        daysSinceFirstRun = ChronoUnit.DAYS.between(firstRunOn, through).coerceAtLeast(0).toInt(),
        qualifyingRuns = counted.size,
        weeks = weeks.takeLast(WEEKS_SHOWN),
        calendarWeeksSpanned = weeks.size,
    )
}
