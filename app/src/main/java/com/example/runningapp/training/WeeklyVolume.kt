package com.example.runningapp.training

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * One finished Run as the weekly bars see it: when it began, and the three things a week can be
 * counted in.
 *
 * A separate view of a Run from [ScoredRun] and not an extension of it, because the two feeds are
 * not the same set of Runs. The curves are built from Runs that have an Effort Score and nothing
 * else; volume is built from every finished Run, because a Run with no Strap on it still covered
 * ground and still took an hour.
 *
 * [effortScore] is nullable here for that reason — null is "nothing was measured", which is not a
 * zero and is not the same as an easy hour that scored 0.
 */
data class VolumeRun(
    val startedAtMillis: Long,
    val distanceKm: Double,
    val timeSeconds: Long,
    val effortScore: Int?,
)

/**
 * One week of training, totalled — the height of one bar, whichever measure is showing.
 *
 * [startingOn] is always a Monday. The week the runner is in is included and totals only what has
 * happened in it so far, which is what makes the last bar shorter than the ones before it on any
 * day but a Sunday night.
 *
 * [effortScore] keeps the distinction [VolumeRun] makes: null is a week nothing measured — no Run in
 * it wore a Strap — while 0 is a week that was measured and scored nothing, which is what a week of
 * walking recorded on a Strap comes to.
 */
data class TrainingWeek(
    val startingOn: LocalDate,
    val distanceKm: Double,
    val timeSeconds: Long,
    val effortScore: Int?,
)

/**
 * What the weekly bars are counting. One chart with a toggle rather than three charts, so the weeks
 * stay in the same place and only the heights change.
 */
enum class WeeklyMeasure(val label: String, val unit: String) {
    DISTANCE("Distance", "km"),
    TIME("Time", "hours"),
    // "Effort Score" in full and never the bare word: "Effort" on screen is already how the runner
    // rates a Run out of ten (CONTEXT.md).
    EFFORT_SCORE("Effort Score", "");

    /**
     * The week's total in the unit this measure is read in — kilometres, hours, or Score.
     *
     * A week with nothing measured draws at zero height, the same as a week measured at zero: the
     * bar has no way to say "unknown". What the two mean apart is said in words instead, by the
     * screen, when every bar in the range is flat.
     */
    fun amountOf(week: TrainingWeek): Double = when (this) {
        DISTANCE -> week.distanceKm
        TIME -> week.timeSeconds / 3_600.0
        EFFORT_SCORE -> (week.effortScore ?: 0).toDouble()
    }
}

/**
 * Every week from the one the runner's first Run fell in to the one containing [through], each with
 * its totals.
 *
 * Weeks start Monday, the convention every training week in this app and most others runs on — a
 * runner's "big weekend" is the end of a week, not the start of one.
 *
 * Weeks nobody ran in come back as zeroes rather than being left out. The bars are drawn side by
 * side with nothing but their order to place them in time, so a missing week would silently close
 * the gap and draw a fortnight off as two hard weeks in a row.
 *
 * Runs after [through] are ignored rather than folded into the last week, the same guard
 * [progressCurve] keeps against a phone whose clock has moved.
 */
fun weeklyVolumeOf(
    runs: Iterable<VolumeRun>,
    through: LocalDate,
    zone: ZoneId,
): List<TrainingWeek> {
    val lastWeek = through.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val totals = HashMap<LocalDate, TrainingWeek>()
    runs.forEach { run ->
        // Dated, not weeked: a Run stamped Sunday while the runner is on Wednesday shares the
        // current week's Monday, so a week-level guard would let tomorrow into today's bar.
        val ranOn = Instant.ofEpochMilli(run.startedAtMillis).atZone(zone).toLocalDate()
        if (ranOn.isAfter(through)) return@forEach
        val weekStart = ranOn.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val soFar = totals[weekStart] ?: emptyWeek(weekStart)
        totals[weekStart] = soFar.copy(
            distanceKm = soFar.distanceKm + run.distanceKm,
            timeSeconds = soFar.timeSeconds + run.timeSeconds,
            // A Run with no Score adds nothing and leaves the week's Score as it found it, so a week
            // of unmeasured Runs stays null rather than being credited with a zero it did not earn.
            // The same distinction the Score itself makes (#61).
            effortScore = when {
                run.effortScore == null -> soFar.effortScore
                else -> (soFar.effortScore ?: 0) + run.effortScore
            },
        )
    }

    var week = totals.keys.minOrNull() ?: return emptyList()
    val weeks = mutableListOf<TrainingWeek>()
    while (!week.isAfter(lastWeek)) {
        weeks += totals[week] ?: emptyWeek(week)
        week = week.plusWeeks(1)
    }
    return weeks
}

private fun emptyWeek(startingOn: LocalDate) =
    TrainingWeek(startingOn = startingOn, distanceKm = 0.0, timeSeconds = 0, effortScore = null)

/**
 * The stretch of weeks a [ProgressRange] shows, sharing the picker with the curves above.
 *
 * Whole weeks only: a week that began before the range did is dropped rather than drawn with the
 * days inside the range alone. A bar holding four days of a seven-day week is a light week nobody
 * had, and the first bar is exactly the one a runner compares the rest against.
 */
fun List<TrainingWeek>.within(range: ProgressRange, endingOn: LocalDate): List<TrainingWeek> {
    val from = range.startOn(endingOn)
    return filter { !it.startingOn.isBefore(from) && !it.startingOn.isAfter(endingOn) }
}
