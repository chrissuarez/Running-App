package com.example.runningapp.training

import com.example.runningapp.isBeyondAnyonesToday
import com.example.runningapp.ranOn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * The stretch of time a Goal is asked of, and starts over at the end of (#82).
 *
 * Weeks start Monday, the same convention the weekly bars run on — a runner's big weekend is the end
 * of a week, not the start of one. [label] names the goal in a list of them; [thisPeriod] is how the
 * period is said on the bar itself, where "This week" reads as a runner would say it.
 */
enum class GoalPeriod(val label: String, val thisPeriod: String) {
    WEEK("Weekly", "This week"),
    MONTH("Monthly", "This month"),
    YEAR("Annual", "This year");

    /** The first day of the period [date] falls in. */
    fun startOn(date: LocalDate): LocalDate = when (this) {
        WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        MONTH -> date.withDayOfMonth(1)
        YEAR -> date.withDayOfYear(1)
    }
}

/**
 * What a Goal is counted in.
 *
 * The three a runner states a period in without being asked which they mean — how far, how long, how
 * often. [unit] is the word after the number on the bar, and the one the target is typed in.
 */
enum class GoalMetric(val label: String, val unit: String) {
    DISTANCE("Distance", "km"),
    TIME("Time", "hours"),
    // "Runs" and not "sessions": every finished Run counts here, walk and treadmill alike, and Run
    // is the word this app has for one (CONTEXT.md).
    COUNT("Runs", "runs");

    /**
     * What one Run adds to this measure.
     *
     * A Run with nothing recorded but a clock — a treadmill Run whose distance was never stated —
     * adds its hour and its one to the count, and nothing at all to the distance. That is not a Run
     * being left out: it is a distance nobody measured, and crediting it with a guess would be worse
     * than counting it as the nothing it recorded (ADR 0008).
     *
     * The rule is about the distance and never about the treadmill. A Stated Distance is the number
     * the runner read off the console, and it counts everywhere a measured one does — their pace,
     * their weekly volume, the coach, the record book (#231). A goal that ignored it would be the
     * one place in the app calling the runner's own reading of their run less than a run.
     */
    fun amountOf(run: VolumeRun): Double = when (this) {
        DISTANCE -> run.distanceKm
        TIME -> run.timeSeconds / 3_600.0
        COUNT -> 1.0
    }
}

/**
 * A target the runner has set themselves: this much of this measure, every period, until they say
 * otherwise (#82).
 *
 * Recurring is the whole of it — there is no end date and no per-period copy. A weekly 40 km is one
 * row that every week is read against, so the goal renews at each Monday without the runner touching
 * it, and editing it changes what *this* period is measured against as well as the ones to come.
 *
 * [target] is in the metric's own [GoalMetric.unit] — kilometres, hours, or Runs.
 */
data class Goal(
    val id: Long,
    val period: GoalPeriod,
    val metric: GoalMetric,
    val target: Double,
)

/**
 * How far into a Goal the runner is, in the period they are in now.
 *
 * Worked out on read from the Runs themselves, every time, rather than kept in a table beside the
 * goal — the same argument as the curves (#60). A stored total would be a second copy of the truth
 * that every deleted Run, corrected treadmill distance and restored backup would have to remember to
 * correct, and the arithmetic is one pass over a period's Runs.
 *
 * [done] is what was actually done, uncapped: a runner who ran 25 km against a 20 km week should see
 * the 25. [fraction] is what the bar draws, which stops at full.
 */
data class GoalProgress(
    val goal: Goal,
    val periodStart: LocalDate,
    val done: Double,
) {
    /** How full the bar is, 0 to 1 — a target of nothing is left empty rather than dividing by it. */
    val fraction: Double get() = if (goal.target > 0.0) (done / goal.target).coerceIn(0.0, 1.0) else 0.0

    val met: Boolean get() = goal.target > 0.0 && done >= goal.target
}

/**
 * Where each Goal stands on the day [on], in the order the goals were given.
 *
 * Only the period the runner is in now: a goal card is about the week they are having, and last
 * week's is over. Runs are placed by the day they *set off* on, so a Run that crossed midnight
 * belongs wholly to the day the runner would say they ran — the same rule the curves and the weekly
 * bars keep (#60).
 *
 * Runs stamped beyond any runner's [on] are ignored rather than counted early, the same guard
 * [progressCurve] and [weeklyVolumeOf] keep against a phone whose clock has moved — and beyond
 * *anyone's* today rather than beyond this phone's, because a runner who has flown west holds a Run
 * honestly a day ahead of it (#304, [isBeyondAnyonesToday]).
 */
fun goalProgressOf(
    goals: Iterable<Goal>,
    runs: Iterable<VolumeRun>,
    on: LocalDate,
    zone: ZoneId,
): List<GoalProgress> {
    val goalList = goals.toList()
    if (goalList.isEmpty()) return emptyList()

    val daysRunOn = runs.mapNotNull { run ->
        val day = ranOn(run.startedAtMillis, run.ranAtUtcOffsetSeconds, zone)
        if (day.isBeyondAnyonesToday(on)) null else day to run
    }

    return goalList.map { goal ->
        val from = goal.period.startOn(on)
        val done = daysRunOn
            .filter { (day, _) -> !day.isBefore(from) }
            .sumOf { (_, run) -> goal.metric.amountOf(run) }
        GoalProgress(goal = goal, periodStart = from, done = done)
    }
}
