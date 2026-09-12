package com.example.runningapp.training

/**
 * What the active Stage's card says about the training already recorded under it (#445).
 *
 * The measurable half of a requirement written in weeks, said to the runner. [StageTrainingRecord]
 * (#289) has counted it since August — full weeks trained, qualifying Runs, and a count for every
 * week including the empty ones — and has only ever handed it to the coach. A runner standing in a
 * Stage whose bar is written in weeks had no way to know how many weeks the app thought they had
 * done.
 *
 * **It states, and never offers.** The same register as [alreadyBeatenLine] (#293): nothing here
 * grants a Stage, promises one, or counts down to one. The half of the bar that is arithmetic is
 * printed; the half that is a judgement is named as the coach's, out loud, because a single bar
 * merging the two would read as a promotion about to be handed over and a graduation cannot be
 * taken back (ADR 0016, ADR 0019).
 */
data class StageTrainingSummary(
    /**
     * The one-line count: `"3 of 4 full weeks trained — 9 qualifying runs"`, or the plain words for
     * a Stage nothing has been recorded under yet.
     *
     * Never a percentage and never a bar drawn at zero: "nothing recorded" and "measured at zero"
     * are different facts, and only the first is true of a Stage just entered.
     */
    val headline: String,
    /**
     * The weeks the card draws, oldest first, empty ones kept — [StageTrainingRecord.weeks], which
     * is the most recent twelve at most and not always the whole Stage. Empty for a Stage with no
     * qualifying Run behind it, which is the one case that draws no row at all.
     *
     * The gap is the point. A fortnight off is exactly what the word *consistent* asks about, and a
     * total hides it.
     *
     * Where these are only the tail, [weeksCaption] says so — see there.
     */
    val weeks: List<StageWeek>,
    /**
     * What the row of weeks is, in the runner's words: which end is which, and whether earlier
     * weeks have been left off it.
     *
     * The record keeps at most twelve weeks ([StageTrainingRecord.weeksAreATail]), and stating that
     * is not optional: past twelve weeks in one Stage the columns add up to less than the headline's
     * total, and a runner handed a mismatch nobody explained will reconcile it themselves — most
     * likely by reading the smaller number as the true one. The coach is told the same thing for the
     * same reason (ADR 0019). Null exactly when [weeks] is empty, and there is no row to caption.
     */
    val weeksCaption: String?,
    /**
     * Who decides the rest, in one sentence — always present beside a weeks figure, because that is
     * the bar with a judgement left in it, and the figure without the sentence is arithmetic
     * pretending to be the whole answer.
     *
     * Null exactly where no weeks figure is printed, which is the Stage with nothing recorded under
     * it: there is no "rest" for this line to be about. A Stage whose bar names no weeks has no
     * summary at all rather than one with this line missing.
     */
    val judgementLine: String?,
    /**
     * Which Runs the count is made of, in the runner's own words — including the one condition
     * nobody would guess, that a Run kept back from the coach is not counted here.
     *
     * A runner who finishes a Run and watches the number stand still is owed the reason on the
     * screen. The whole class is stated rather than that one member, because a caveat written as a
     * single example is wrong at its second one.
     */
    val countedLine: String,
)

/**
 * Build what the card says from the record the app has already counted (#445).
 *
 * [weeksRequired] is the Stage's own bar where that bar names a number of weeks
 * ([com.example.runningapp.PlanStage.weeksRequirement]), and null where it does not — a Stage whose
 * bar is a time, or the Desk Test plan's *"Complete 2 short run/walk repeats"*.
 *
 * **A null bar answers null**, and the card says nothing at all. Every figure here answers a bar
 * written in weeks, the count of Runs included: this record is the set the *coach* is handed, and
 * against a bar the coach does not judge it is a different set of Runs wearing the word
 * "qualifying". A Stage whose bar is a time is cleared by a Run this record excludes — see the
 * rule in the body — and the Desk Test plan's *"Complete 2 short run/walk repeats"* is a judgement
 * about something this does not measure either.
 *
 * [record] must be built from `SessionDao.getAiEvidenceRunDaysOfStage` — the graduation guard's own
 * filter — and never from a wider read. Two doors that answer "how many weeks" have to be fed the
 * same numbers, or one of them is misleading the runner about a promotion the app itself will never
 * reverse. The runner can move back by hand (ADR 0020, #235), which is a repair and not a reason to
 * be any less careful here: a graduation nobody notices is a graduation nobody repairs.
 */
fun stageTrainingSummaryOf(
    record: StageTrainingRecord,
    weeksRequired: Int?,
): StageTrainingSummary? {
    // A bar that names no weeks is answered by nothing this record holds, so the card says nothing
    // (Codex P2 on PR #450). It is not a screen refusing to say what it knows: what it knows is the
    // count behind a WEEKS bar, and against any other bar that count is a different set of Runs
    // wearing the word "qualifying".
    //
    // A Stage whose bar is a time is graduated by `graduateOnBestEffortRequirement`, which is
    // deliberately NOT gated on `includeInAiTraining` and asks nothing about the plan's Workouts
    // (ADR 0016): a Run kept back from the coach, or run off-plan, can clear that bar. This record
    // is `getAiEvidenceRunDaysOfStage` — shared, on-plan, run/walk mode — so a private 5K would
    // graduate the Stage while the "qualifying runs" figure beside it never moved. And a bar that
    // is neither weeks nor a time (the Desk Test plan's "Complete 2 short run/walk repeats") is a
    // judgement the coach makes about something this count does not measure either.
    //
    // Both cases are one rule: print this only where the bar it stands under is written in weeks.
    if (weeksRequired == null) return null

    val countedLine = "Counted here: runs you did from this plan's workouts in this stage, " +
        "longer than two minutes, not marked as a walk, and left shared with the coach. " +
        "A run you kept back from the coach is not counted."

    if (record.isEmpty) {
        return StageTrainingSummary(
            headline = "No qualifying runs recorded in this stage yet.",
            weeks = emptyList(),
            weeksCaption = null,
            judgementLine = null,
            countedLine = countedLine,
        )
    }

    val runs = record.qualifyingRuns
    val runsPart = "$runs qualifying ${runOrRuns(runs)}"

    val trained = record.weeksTrained
    val weeksPart = if (trained < weeksRequired) {
        // Below the bar the bar is the second number, which is the shape the runner reads as "how
        // far along am I".
        "$trained of $weeksRequired full weeks trained"
    } else {
        // At or past it the bar moves into a bracket rather than staying the denominator. "5 of 4
        // full weeks trained" reads as a fault in the app, and a card the runner distrusts is worse
        // than one that says less.
        "$trained full ${weekOrWeeks(trained)} trained (this stage asks for $weeksRequired)"
    }

    return StageTrainingSummary(
        headline = "$weeksPart — $runsPart",
        weeks = record.weeks,
        weeksCaption = if (record.weeksAreATail) {
            "Each column is a week — this week on the right. Only the most recent " +
                "${record.weeks.size} are drawn, so the columns add up to less than the total above."
        } else {
            "Each column is a week — the oldest on the left, this week on the right."
        },
        judgementLine =
            "Your coach judges whether that training has been consistent, after a Long Run.",
        countedLine = countedLine,
    )
}

private fun weekOrWeeks(n: Int) = if (n == 1) "week" else "weeks"

private fun runOrRuns(n: Int) = if (n == 1) "run" else "runs"
