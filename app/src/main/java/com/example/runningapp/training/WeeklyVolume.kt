package com.example.runningapp.training

import com.example.runningapp.isBeyondAnyonesToday
import com.example.runningapp.ranOn
import java.time.LocalDate
import java.time.ZoneId

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
    /**
     * Where the runner's clock was when this Run set off, or null for a Run that never wrote it
     * down — see [com.example.runningapp.data.RunnerSession.ranAtUtcOffsetSeconds] (#304). It is
     * what places the Run on a calendar day, so the day survives the runner flying home.
     */
    val ranAtUtcOffsetSeconds: Int? = null,
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
 *
 * [runsWithoutScore] is the third state, and the one a total alone cannot hold (#247): a week can be
 * measured *in part*. Its Effort Score then counts the Runs that wore a Strap and leaves out the
 * ones that did not, which reads low and always in the same direction — a runner who forgot the
 * Strap on their long day looks like they trained less than they did. Kept as a count of the Runs
 * left out rather than as a flag, because it is also what tells a week of rest (no Runs at all) from
 * a week of strapless Runs (Runs, and nothing measuring them) — two weeks that otherwise both come
 * to a null Score and are opposite news to anyone reading fatigue.
 */
data class TrainingWeek(
    val startingOn: LocalDate,
    val distanceKm: Double,
    val timeSeconds: Long,
    val effortScore: Int?,
    val runsWithoutScore: Int,
) {
    /**
     * Whether this week's Effort Score is short of what was actually run — a floor under the week
     * and never a ceiling.
     *
     * A week nothing measured is not this: it has no total to be short of, and says so already by
     * having no Effort Score at all.
     */
    val partlyMeasured: Boolean get() = effortScore != null && runsWithoutScore > 0
}

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
 * Runs stamped beyond any runner's [through] are ignored rather than folded into the last week —
 * the same guard [progressCurve] keeps against a phone whose clock has moved. Beyond *anyone's*
 * today rather than beyond this phone's, because a Run carries its own day now and a runner who has
 * flown west holds one honestly a day ahead of the phone (#304, [isBeyondAnyonesToday]).
 */
fun weeklyVolumeOf(
    runs: Iterable<VolumeRun>,
    through: LocalDate,
    zone: ZoneId,
): List<TrainingWeek> {
    val totals = HashMap<LocalDate, TrainingWeek>()
    runs.forEach { run ->
        // Dated, not weeked: a Run stamped Sunday while the runner is on Wednesday shares the
        // current week's Monday, so a week-level guard would let tomorrow into today's bar.
        val day = ranOn(run.startedAtMillis, run.ranAtUtcOffsetSeconds, zone)
        if (day.isBeyondAnyonesToday(through)) return@forEach
        val weekStart = day.mondayOfWeek()
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
            // Counted rather than inferred from the totals: a Run that recorded no heart rate still
            // adds its ground and its hour above, so nothing left in the week says how much of it
            // went unmeasured (#247).
            runsWithoutScore = soFar.runsWithoutScore + if (run.effortScore == null) 1 else 0,
        )
    }

    // The bars run through the week the runner is in, and through the week of the last Run taken in
    // if that is later. A Run one day ahead of the phone can fall on the far side of a Monday, and a
    // Run accepted above and then left out of the range would be counted and never drawn — with one
    // such Run and no others, the chart would come back empty.
    val lastWeek = maxOf(
        through.mondayOfWeek(),
        totals.keys.maxOrNull() ?: LocalDate.MIN,
    )

    val firstWeek = totals.keys.minOrNull() ?: return emptyList()
    return weeksFrom(firstWeek, lastWeek).map { totals[it] ?: emptyWeek(it) }
}

private fun emptyWeek(startingOn: LocalDate) =
    TrainingWeek(
        startingOn = startingOn,
        distanceKm = 0.0,
        timeSeconds = 0,
        effortScore = null,
        // No Runs at all, which is what tells a week of rest from a week of strapless Runs — both
        // come to no Effort Score, and they are opposite news (#247).
        runsWithoutScore = 0,
    )

/** How many short weeks are named one by one before the note counts them instead. */
private const val WEEKS_NAMED_BEFORE_COUNTING = 3

/**
 * What the Effort Score bars are not showing, in a sentence — or null when they are showing all of
 * it (#247).
 *
 * A bar has no way to draw "and some more that nobody measured", so a week holding a strapless Run
 * is drawn short and reads beside its neighbours as the lighter week the runner never had. Said in
 * words underneath instead, the same answer the screen already gives to a chart that is flat for
 * two different reasons.
 *
 * Every week with a Run nothing measured in it, not only the partly measured ones: a week whose
 * Runs were all strapless draws at nothing at all, which understates it further still.
 *
 * The weeks are named while there are few enough to find on the chart, and counted once there are
 * not: a sentence listing eight dates is one a runner reads past.
 *
 * [dateText] writes a week's Monday the way the chart's own axis writes it, so the dates in the
 * sentence are the labels under the bars and not a second date format.
 */
fun unmeasuredRunsNote(weeks: List<TrainingWeek>, dateText: (LocalDate) -> String): String? {
    val short = weeks.filter { it.runsWithoutScore > 0 }
    if (short.isEmpty()) return null
    val tail = "with no heart rate recorded, so their bars are short of what was run."
    if (short.size > WEEKS_NAMED_BEFORE_COUNTING) {
        return "${short.size} of these weeks held runs $tail"
    }
    val dates = short.map { dateText(it.startingOn) }
    if (dates.size == 1) {
        val runs = if (short.single().runsWithoutScore == 1) "a run" else "runs"
        return "The week of ${dates.single()} held $runs with no heart rate recorded, so its bar " +
            "is short of what was run."
    }
    return "The weeks of ${dates.dropLast(1).joinToString(", ")} and ${dates.last()} held runs $tail"
}

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
