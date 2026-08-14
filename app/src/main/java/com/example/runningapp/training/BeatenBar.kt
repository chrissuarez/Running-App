package com.example.runningapp.training

import com.example.runningapp.BestEffortRequirement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The best Run in history at one record distance, and when it was run (#293).
 *
 * Read off the record book rather than measured again here — the book already holds the fastest
 * effort at each distance, ranked by the one measurement the whole app agrees on
 * ([com.example.runningapp.analysis.bestEffortsOf]). That is also where the three edges of the
 * graduation rule live: never a Walk, never a Run still going, measured off a track or stated off a
 * treadmill console. Borrowing them is the point — a rule applied in one reader of a shared
 * measurement is a bug waiting for the second reader.
 */
data class HistoryBestEffort(
    /** The effort itself, in seconds, exactly as the record book ranks it. */
    val seconds: Double,
    /** When the Run holding it started — what the line names it by. */
    val runStartedAtMillis: Long,
)

/**
 * What the Stage card says when the runner has already beaten its bar (#293), or null when they
 * have not.
 *
 * The graduation rule looks forwards only, deliberately: a pass over history that jumped the runner
 * two Stages on launch, on evidence recorded under different rules, is the highest-stakes version
 * of the one act the app can never undo
 * ([ADR 0016](docs/adr/0016-a-requirement-stated-in-numbers-is-not-the-coachs-to-judge.md)). But
 * that leaves a runner with a sub-30 5K in history staring at a Stage they have plainly beaten with
 * no idea why it does not count, and silence there reads as a bug. So the bar is said out loud
 * rather than granted in silence.
 *
 * **It is a statement, not an offer.** It says the Run happened and that a new one would graduate.
 * Nothing here changes any state, and nothing in the wording may suggest the app is about to hand
 * anything over: the moment it reads as an offer, the runner is owed a grant the rule will not make.
 *
 * **It scans the whole of history, whatever kind of Run turned the time in.** Under the rule as it
 * now stands an old Open Run with a qualifying 5K *would* count if it happened today, so hiding it
 * would be the card disagreeing with the rule — and the rule is the thing that is true. Measured
 * and stated 5Ks alike, and never a Walk: [best] comes from the record book, which settles all
 * three the same way the rule does.
 *
 * [best] is the *best* effort in history and not merely one that clears, so the line names the Run
 * the runner would think of first. Null is a distance nothing in history has ever been ranked at,
 * which says nothing rather than nothing-yet.
 */
fun alreadyBeatenLine(
    requirement: BestEffortRequirement,
    best: HistoryBestEffort?,
    today: LocalDate,
    zone: ZoneId,
): String? {
    // The same comparison the rule itself makes, and in the same direction: [withinSeconds] is the
    // slowest time that still passes, so there is no polarity here to get wrong separately.
    if (best == null || best.seconds > requirement.withinSeconds) return null
    val distance = requirement.record.label.removePrefix("Fastest ")
    val day = Instant.ofEpochMilli(best.runStartedAtMillis).atZone(zone).toLocalDate()
    return "Your $distance on ${asDay(day, today)} was ${asClock(best.seconds)} — " +
        "fast enough for this stage. Run one now and it counts."
}

/**
 * The day a Run happened, as the runner would say it: "14 June", and "14 June 2024" once it is not
 * this year any more.
 *
 * The year is left off the recent case because it is noise there, and put back on the old one
 * because "14 June" for a Run two summers ago is the card quietly overstating how recently the bar
 * was beaten.
 */
private fun asDay(day: LocalDate, today: LocalDate): String {
    val pattern = if (day.year == today.year) "d MMMM" else "d MMMM yyyy"
    return day.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

/** Whole seconds as a runner reads them off a clock: "27:41". */
internal fun asClock(seconds: Double): String {
    val whole = seconds.roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(whole / 60, whole % 60)
}
