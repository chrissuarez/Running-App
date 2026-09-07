package com.example.runningapp.ui

import com.example.runningapp.RunType
import com.example.runningapp.TrainingPlanProvider
import com.example.runningapp.data.RunnerSession
import com.example.runningapp.data.isFinished
import java.util.Locale

/**
 * Narrowing History to one Run Type, and saying what those Runs covered (#447).
 *
 * The question this answers is a route-drawing one: *how long a loop should I draw for a Quality
 * day?* A circular route has to be drawn to a length up front — unlike an out-and-back, where the
 * runner just turns round — so the number has to come from somewhere, and the only honest source is
 * the runner's own Runs of that Run Type.
 *
 * Pure and outside the composables, for the reason [RouteSuggestion]'s arithmetic is: the numbers
 * the runner is shown are the feature, so they are pinned by unit tests rather than by scrolling
 * History on a phone.
 */

/**
 * How a History row is filed for the purpose of narrowing the list.
 *
 * Three of these are the plan's own [RunType]s. The fourth, [OTHER], is an explicit state and not a
 * leftover: it is named on screen, it is selectable like the rest, and every Run that is not one of
 * the three Run Types lands in it. That matters because History's promise is that nothing disappears
 * from it — every Run the unfiltered list shows is shown by exactly one of these four.
 *
 * [label] is the word on the chip, and it is the word the handbook uses.
 */
enum class RecordedRunType(val label: String) {
    LONG("Long"),
    EASY("Easy"),
    QUALITY("Quality"),
    OTHER("Other"),
}

/**
 * Which Run Type a *recorded* Run is filed under — the one place History asks the question.
 *
 * A stored Run holds no Run Type of its own. It holds the Stage and the Workout it was started under
 * ([RunnerSession.ranUnderStageId], [RunnerSession.ranUnderWorkoutId]), and the Run Type is the
 * Workout's ([com.example.runningapp.WorkoutTemplate.runType]), recovered by
 * [TrainingPlanProvider.runTypeOfRecordedRun] — the same recovery the pre-run picker and the coach's
 * gate use, asked once here so History cannot reach a third answer.
 *
 * There are exactly four cases, and each is [OTHER] for its own stated reason:
 *
 * 1. **A Run under a Workout the plan still holds** → that Workout's [RunType]. The ordinary case.
 * 2. **An Open Run**, or any Run with a null `ranUnderWorkoutId` — one started with no plan
 *    attached, one that skipped the plan that day, or one recorded before the column existed →
 *    [OTHER]. It genuinely has no Run Type. Sweeping it into Easy because it was slow, or into Long
 *    because it was far, would be the app inventing a history the runner never recorded.
 * 3. **A Run under a Workout id no plan holds any more** — the plan was edited, or the Run was made
 *    under the Desk Test plan → [OTHER], for the same reason. The lookup returns nothing, and
 *    nothing is the answer; guessing which of three Run Types a deleted Workout was is a guess dressed
 *    as a fact.
 * 4. **A Run the runner marked a Walk** ([RunnerSession.isWalk]) → [OTHER], *even where the Workout
 *    is still there and still says Quality*. A Walk completes no prescribed workout, graduates no
 *    Stage and contests no record, so the plan did not get the session it asked for. It also breaks
 *    the one thing this screen is for: walked ground is not run ground, and a walked "Quality day"
 *    dragged into the typical Quality distance would send the runner out to draw a loop shorter
 *    than any Quality day they have actually run.
 *
 * The alternative considered for (4) was to keep a Walk under its Workout's Run Type and leave it out of
 * the distances only. It was declined because it puts two different answers on one screen — the row
 * would be listed as a Quality day by the very filter whose summary refuses to count it — and a
 * runner reading "Usually 7.2 km" over a list of five rows would be reading it off four.
 */
fun recordedRunTypeOf(session: RunnerSession): RecordedRunType {
    if (session.isWalk) return RecordedRunType.OTHER
    return when (
        TrainingPlanProvider.runTypeOfRecordedRun(session.ranUnderStageId, session.ranUnderWorkoutId)
    ) {
        RunType.LONG -> RecordedRunType.LONG
        RunType.EASY -> RecordedRunType.EASY
        RunType.QUALITY -> RecordedRunType.QUALITY
        null -> RecordedRunType.OTHER
    }
}

/**
 * The rows the list shows: all of them where [type] is null, and only that Run Type where it is not.
 *
 * Null is the screen's own default and it is the list that exists today, in the order it exists in
 * today — untouched, not re-sorted, nothing dropped. That is deliberate: a runner who has never
 * touched a chip must see exactly the History they have always seen.
 */
fun historyRowsOfType(rows: List<HistoryRow>, type: RecordedRunType?): List<HistoryRow> {
    if (type == null) return rows
    return rows.filter { recordedRunTypeOf(it.session) == type }
}

/**
 * How many Runs must have a distance before one of them is called the *typical* one.
 *
 * Three, the same bar and for the same reason as [ROUTE_SUGGESTION_MIN_RUNS]: a single Run is not a
 * typical distance, it is a day — the day it rained, or the day the runner cut it short — and three
 * is the smallest count at which the middle value is a middle rather than the only value.
 *
 * Below it the block still prints the distances it has; it just does not call any of them usual.
 */
const val HISTORY_TYPICAL_MIN_RUNS = 3

/**
 * What the Runs now on screen covered — the summary drawn above a narrowed History (#447).
 *
 * It describes **the rows being shown and nothing else**. It asks the database nothing extra and
 * reaches past the list for nothing: History shows the last 20 sessions, so this is a summary of
 * those, and a summary that quietly answered off a wider set would be a number the runner cannot
 * check against the very list it is sitting on top of.
 *
 * [finishedRuns] counts every *finished* row and [measuredRuns] only the ones that contributed a
 * distance, so the difference between the two is exactly "finished Runs with no distance" and is
 * printed rather than hidden — see [historyDistanceDetail]. A Run still being recorded is in
 * neither count: it is not a Run with no distance, it is a Run that has not happened yet, and
 * counting it in the first number would have the line report it as the second.
 */
data class HistoryDistanceSummary(
    /** The finished Runs on screen, measured or not. */
    val finishedRuns: Int,
    /** The finished Runs a distance was actually taken from. */
    val measuredRuns: Int,
    /** The middle distance, in km — null below [HISTORY_TYPICAL_MIN_RUNS]. */
    val typicalKm: Double?,
    /** The shortest distance, in km — null where nothing was measured. */
    val shortestKm: Double?,
    /** The longest distance, in km — null where nothing was measured. */
    val longestKm: Double?,
)

/**
 * The distances the given rows came out at.
 *
 * **What counts.** A finished Run with a distance above nought. A treadmill Run counts on its Stated
 * Distance exactly like any other (#82 / #282) — the runner typed in what the console showed, and it
 * is stored in the same [RunnerSession.distanceKm] column, so there is nothing here to special-case.
 *
 * **What does not.** A Run with no distance at all — a treadmill Run nobody stated one for, or an
 * outdoor Run whose GPS recorded nothing — because nought is not a short run, it is an unknown, and
 * averaging it in would report a typical distance shorter than every Run it was built from. And a
 * Run still being recorded: its distance is however far the runner has got so far, which will be
 * wrong in a minute's time and is not what any of the other rows mean.
 *
 * The two exclusions are not the same exclusion and are not counted as one.
 * [HistoryDistanceSummary.finishedRuns] holds the finished rows, so the difference between it and
 * [HistoryDistanceSummary.measuredRuns] names the finished Runs that had no distance to give — and
 * the Run still being recorded is outside both, named nowhere, because there is nothing yet to say
 * about it.
 *
 * **The middle and not the average**, for the reason the route suggestion gives
 * ([recentMedianPaceMinPerKm]): what is being guarded against is one unusual Run, and an average has
 * no defence against one. A runner who once ran a half marathon on a Long day still gets the length
 * of their other Long days.
 */
fun historyDistanceSummary(rows: List<HistoryRow>): HistoryDistanceSummary {
    val measured = rows
        .map { it.session }
        .filter { it.isFinished() && it.distanceKm > 0.0 }
        .map { it.distanceKm }
        .sorted()
    return HistoryDistanceSummary(
        finishedRuns = rows.count { it.session.isFinished() },
        measuredRuns = measured.size,
        typicalKm = if (measured.size >= HISTORY_TYPICAL_MIN_RUNS) middleOf(measured) else null,
        shortestKm = measured.firstOrNull(),
        longestKm = measured.lastOrNull(),
    )
}

/**
 * The middle value of an already-sorted, non-empty list — the mean of the middle two where there is
 * no single middle.
 *
 * Written here rather than folded together with the median inside [recentMedianPaceMinPerKm] and the
 * one in `RunSummaryPrompt`. The three take different inputs and apply different bars before they
 * get to the arithmetic, and a shared helper would be four lines of sorting rules to keep in step
 * for the sake of saving two; the rule itself — middle value, mean of the middle pair — is stated in
 * each place it is used.
 */
private fun middleOf(sorted: List<Double>): Double {
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

/**
 * The big line: `Usually 7.2 km`, or null where nothing may be called usual.
 *
 * Null in two cases, and neither is a hedged number, because "usually" is a claim the app either
 * makes or does not:
 *
 * 1. **Too few Runs carry a distance** — [HISTORY_TYPICAL_MIN_RUNS] is the point at which the app is
 *    willing to make it. The detail line below still prints what was measured, so a runner with two
 *    Quality days sees those two rather than an empty block.
 * 2. **[RecordedRunType.OTHER]**, whatever the count. Other is not a Run Type, it is the absence of
 *    one, and its members have nothing in common that would make their distances one answer: an
 *    Open Run, a Run off the plan, a Run under a Workout no Plan holds any more, and a Walk. It is
 *    the same objection this screen refuses an unfiltered card for, and it applies with more force
 *    here — a Walk is kept out of the Quality Runs' usual distance precisely because walked ground
 *    is not run ground, and a "usually" drawn over Other would put it straight back into a middle
 *    value of its own.
 *
 * The range on the detail line stays, for Other as for the rest: shortest and longest is a statement
 * of what these Runs measured, and it claims nothing about what the next one would be.
 */
fun historyDistanceHeadline(
    type: RecordedRunType,
    summary: HistoryDistanceSummary,
): String? {
    if (type == RecordedRunType.OTHER) return null
    val typical = summary.typicalKm ?: return null
    return "Usually ${kmText(typical)} km"
}

/**
 * The small line under it: the spread, and how many of the Runs on screen had no distance to give.
 *
 * Three shapes, and each says only what it can:
 * - nothing measured → `No distance on any of these runs.`
 * - every measured Run the same length, or only one of them → `5.0 km`
 * - otherwise → `5.0–9.4 km`
 *
 * and where some *finished* Runs gave nothing, ` · 2 with no distance` is appended. That count is on
 * the line rather than left out because the whole block is a claim about a set, and a claim about
 * twelve Runs printed over a list of fourteen is a claim about the wrong set unless it says so.
 *
 * It counts only finished Runs with no distance, which is what the words say. A Run still being
 * recorded is not one of them — it has a distance, just not a final one — so it is left out of both
 * counts rather than swept into this one and described to the runner as a Run that measured nothing.
 */
fun historyDistanceDetail(summary: HistoryDistanceSummary): String {
    val shortest = summary.shortestKm
    val longest = summary.longestKm
    if (shortest == null || longest == null) return "No distance on any of these runs."
    val range = if (kmText(shortest) == kmText(longest)) {
        "${kmText(shortest)} km"
    } else {
        "${kmText(shortest)}–${kmText(longest)} km"
    }
    val unmeasured = summary.finishedRuns - summary.measuredRuns
    return if (unmeasured > 0) "$range · $unmeasured with no distance" else range
}

/**
 * A distance as this block writes it: one decimal place, always.
 *
 * One place where a History row's own `Dist` column takes two, and the difference is the point. This
 * block answers "how long a loop should I draw", which is answered to a hundred metres, and its
 * headline is a middle value taken over a handful of Runs — printing that as `7.24 km` would claim
 * ten-metre precision for a number that has none. The shortest and longest are written to the same
 * one place even though each *is* an exact measurement, because three numbers in one block written
 * to two different precisions read as two different sorts of claim.
 *
 * The trailing nought is kept — `7.0 km`, not `7 km` — so the numbers in the block stay one shape.
 *
 * Compared against, rather than rounded before comparing, in [historyDistanceDetail]: two Runs 40 m
 * apart both print `5.0 km`, and a range whose two ends print the same is written once.
 */
private fun kmText(km: Double): String = String.format(Locale.UK, "%.1f", km)
