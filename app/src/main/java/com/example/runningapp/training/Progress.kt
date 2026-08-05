package com.example.runningapp.training

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp

/**
 * How many days of training each curve remembers — 42 for Fitness, 7 for Fatigue, the
 * industry-standard time constants (`docs/research/training-load-model.md`, #21).
 *
 * The pair is the whole point: the same days of training read through a long memory and a short one,
 * so the gap between them says whether the runner is carrying more work than they have absorbed.
 */
private const val FITNESS_DAYS = 42.0
private const val FATIGUE_DAYS = 7.0

/** Above this much Form the runner is fresh, below its negative they are fatigued. */
private const val FORM_BAND = 10.0

/**
 * One finished Run as the curves see it: when it began, and what it cost ([effortWeightOf]).
 *
 * The start is what places a Run on a calendar day, so a Run that crosses midnight belongs wholly to
 * the day it set off on — the day the runner would say they ran.
 */
data class ScoredRun(
    val startedAtMillis: Long,
    val effortScore: Int,
)

/**
 * One day of the runner's training, as the Progress screen reads it.
 *
 * [fitness] and [fatigue] are the two averages after this day's effort has landed. [form] is the
 * difference between *yesterday's* two, which is the convention and not an off-by-one: Form is meant
 * to answer "how fresh am I this morning", and this morning cannot yet know what today will cost.
 */
data class ProgressDay(
    val date: LocalDate,
    val fitness: Double,
    val fatigue: Double,
    val form: Double,
)

/** What a Form number means in a word, per the bands in `docs/research/training-load-model.md`. */
enum class FormVerdict(val word: String) {
    FRESH("fresh"),
    NEUTRAL("neutral"),
    FATIGUED("fatigued"),
}

/**
 * How far back the charts look. Shared by every chart on the Progress screen, so one pick moves them
 * all rather than each keeping its own idea of the window.
 */
enum class ProgressRange(val label: String, private val months: Long) {
    THREE_MONTHS("3m", 3),
    SIX_MONTHS("6m", 6),
    ONE_YEAR("1y", 12);

    fun startOn(endingOn: LocalDate): LocalDate = endingOn.minusMonths(months)
}

/**
 * What each calendar day cost, adding up the Runs that started on it.
 *
 * Days with no Runs are absent rather than zero — the curve below fills them in, and a map of every
 * day since the runner's first would be mostly zeroes standing for nothing that happened.
 */
fun dailyEffortOf(runs: Iterable<ScoredRun>, zone: ZoneId): Map<LocalDate, Int> {
    val byDay = LinkedHashMap<LocalDate, Int>()
    runs.forEach { run ->
        val day = Instant.ofEpochMilli(run.startedAtMillis).atZone(zone).toLocalDate()
        byDay[day] = (byDay[day] ?: 0) + run.effortScore
    }
    return byDay
}

/**
 * The Fitness and Fatigue curves over every day from the runner's first Run to [through].
 *
 * Worked out on read from the Runs' own stored Scores, every time, rather than kept in a table of
 * its own (#60). The arithmetic is one multiply-add per day per curve, so a decade of history is
 * still a few thousand operations — and a stored curve would be a second copy of the truth that
 * every re-score, deletion and backfill would have to remember to correct.
 *
 * Each day's step is the standard exponential update, `today = yesterday + (effort − yesterday) × k`
 * with `k = 1 − e^(−1/τ)`. Rest days are not skipped: they enter as an effort of 0 and pull both curves
 * down, which is exactly how rest becomes freshness. Both curves start from nothing on the day
 * before the first Run — the runner's history begins where their records do, and starting anywhere
 * else would be inventing training nobody did.
 *
 * Runs after [through] are ignored rather than folded into the last day, so a Run stamped in the
 * future — a phone whose clock has moved — cannot bend today's numbers.
 */
fun progressCurve(
    runs: Iterable<ScoredRun>,
    through: LocalDate,
    zone: ZoneId,
): List<ProgressDay> {
    val effortByDay = dailyEffortOf(runs, zone).filterKeys { !it.isAfter(through) }
    val firstDay = effortByDay.keys.minOrNull() ?: return emptyList()

    val fitnessStep = 1 - exp(-1 / FITNESS_DAYS)
    val fatigueStep = 1 - exp(-1 / FATIGUE_DAYS)

    val days = mutableListOf<ProgressDay>()
    var fitness = 0.0
    var fatigue = 0.0
    var day = firstDay
    while (!day.isAfter(through)) {
        // Read before the update, so Form is yesterday's answer — and 0 on the first day, when
        // there was no yesterday to have trained in.
        val form = fitness - fatigue
        val effort = (effortByDay[day] ?: 0).toDouble()
        fitness += (effort - fitness) * fitnessStep
        fatigue += (effort - fatigue) * fatigueStep
        days += ProgressDay(date = day, fitness = fitness, fatigue = fatigue, form = form)
        day = day.plusDays(1)
    }
    return days
}

/**
 * The stretch of a curve a [ProgressRange] shows.
 *
 * A window onto the whole curve and never a curve recomputed from the window: the first day shown
 * carries every day of training before it, which is what makes a three-month view of a two-year
 * runner start high instead of climbing from zero all over again.
 */
fun List<ProgressDay>.within(range: ProgressRange, endingOn: LocalDate): List<ProgressDay> {
    val from = range.startOn(endingOn)
    return filter { !it.date.isBefore(from) && !it.date.isAfter(endingOn) }
}

/** Which band a Form number falls in: fresh above +10, fatigued below −10, neutral between. */
fun formVerdictOf(form: Double): FormVerdict = when {
    form > FORM_BAND -> FormVerdict.FRESH
    form < -FORM_BAND -> FormVerdict.FATIGUED
    else -> FormVerdict.NEUTRAL
}
