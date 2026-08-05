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
 */
data class TrainingWeek(
    val startingOn: LocalDate,
    val distanceKm: Double,
    val timeSeconds: Long,
    val effortScore: Int,
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

    /** The week's total in the unit this measure is read in — kilometres, hours, or Score. */
    fun amountOf(week: TrainingWeek): Double = when (this) {
        DISTANCE -> week.distanceKm
        TIME -> week.timeSeconds / 3_600.0
        EFFORT_SCORE -> week.effortScore.toDouble()
    }
}

/** The Monday of the week this instant fell in, in the runner's own zone. */
private fun weekStartOf(startedAtMillis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(startedAtMillis)
        .atZone(zone)
        .toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

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
        val weekStart = weekStartOf(run.startedAtMillis, zone)
        if (weekStart.isAfter(lastWeek)) return@forEach
        val soFar = totals[weekStart] ?: emptyWeek(weekStart)
        totals[weekStart] = soFar.copy(
            distanceKm = soFar.distanceKm + run.distanceKm,
            timeSeconds = soFar.timeSeconds + run.timeSeconds,
            // A Run with no Score adds nothing, so a week of unmeasured Runs comes out at no Effort
            // rather than being credited with a zero it did not earn. The same distinction the
            // Score itself makes (#61).
            effortScore = soFar.effortScore + (run.effortScore ?: 0),
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
    TrainingWeek(startingOn = startingOn, distanceKm = 0.0, timeSeconds = 0, effortScore = 0)

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
