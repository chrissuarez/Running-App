package com.example.runningapp.training

import com.example.runningapp.ranOn
import com.example.runningapp.BestEffortRequirement
import com.example.runningapp.distanceLabel
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
    /**
     * Where the runner's clock was when that Run set off, or null for a Run that never wrote it
     * down — see [com.example.runningapp.data.RunnerSession.ranAtUtcOffsetSeconds] (#304). The
     * line names a day, and the day it names is the Run's own.
     */
    val ranAtUtcOffsetSeconds: Int? = null,
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
    if (best == null || !best.clears(requirement)) return null
    val distance = requirement.distanceLabel
    val day = ranOn(best.runStartedAtMillis, best.ranAtUtcOffsetSeconds, zone)
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

/**
 * The one comparison the bar is ever answered by: [BestEffortRequirement.withinSeconds] is the
 * slowest time that still passes, so 29:59 clears 1799 and 25:00 does not clear 1499.
 *
 * Written once and borrowed by both lines the card can carry, because a polarity restated is a
 * polarity that can be restated wrongly — and the two would then be a congratulation and a
 * shortfall about the same time, on the same card.
 */
private fun HistoryBestEffort.clears(requirement: BestEffortRequirement): Boolean =
    seconds <= requirement.withinSeconds

/**
 * What the record book has to say about one Stage's timed bar (#446).
 *
 * Three answers and not two, because "the app may not say" and "nothing has ever been ranked here"
 * are different facts and only one of them may be printed. A nullable best effort collapses them,
 * and the card would then tell a runner with a 5K in the book that they have none — which is what
 * testing mode would have made it do, that being the one state where the record book is deliberately
 * held back ([com.example.runningapp.data.SessionRepository.barStandingFlow]).
 */
sealed interface BarStanding {
    /**
     * Say nothing about this bar at all: the Stage carries none, or the app is holding the record
     * book back. The card falls back to the requirement in words, exactly as it read before #293.
     */
    data object Silent : BarStanding

    /** The book holds no effort at this distance — nothing has ever been ranked here. */
    data object Unranked : BarStanding

    /** The best effort in the whole of history at this distance. */
    data class Ranked(val best: HistoryBestEffort) : BarStanding

    /** The effort itself where there is one, for the readers that only care about that. */
    val bestOrNull: HistoryBestEffort? get() = (this as? Ranked)?.best
}

/**
 * What the Stage card says about a bar written as a time that the runner has *not* beaten (#446):
 * how fast they have ever been at that distance, and how far off the bar that leaves them.
 *
 *     Your best 5 km is 31:40 — 1:41 off the bar.
 *
 * The card used to say the bar in words, and [alreadyBeatenLine] once the bar was already cleared,
 * and nothing whatever in between — so a runner two minutes off and a runner twenty seconds off
 * read the identical card. The gap is the sum the runner does in their head anyway.
 *
 * **[best] is the record book's, the same one [alreadyBeatenLine] reads.** All-time, whole history,
 * whatever kind of Run turned the time in, never a Walk, never a Run still going, measured off the
 * track or stated off a treadmill console. One source, so the two lines on one card can never
 * contradict each other about the same distance. Deliberately not narrowed to efforts set since the
 * runner entered the Stage: that would be a second rule about one shared measurement, and #257's
 * lesson is that the second reader is where such a rule breaks.
 *
 * **The congratulation wins, and this says nothing.** Where the bar is already beaten,
 * [alreadyBeatenLine] is the whole of what the card says about it: one card may not both
 * congratulate a time and measure a shortfall against it, and the beaten line is the one carrying
 * the fact the runner does not already know — that an old Run counts, and why it did not graduate
 * them. The two are exclusive by construction rather than by two flags kept in step: both ask
 * [clears] of the same [best], and they take opposite branches of it.
 *
 * [BarStanding.Unranked] is a distance nothing in history has ever been ranked at, which is not a
 * gap of any size — so it is said in words, with no figure and no zeroed bar. [BarStanding.Silent]
 * is the app declining to say anything, which is not the same fact and prints nothing.
 *
 * **It is a statement, never an offer** (ADR 0016). It names a time and a difference. Nothing here
 * grants, advances or promises anything, and no percentage is printed: a percentage of a time bar
 * is not a quantity that means anything.
 */
fun barShortfallLine(
    requirement: BestEffortRequirement,
    standing: BarStanding,
): String? {
    val distance = requirement.distanceLabel
    val best = when (standing) {
        BarStanding.Silent -> return null
        BarStanding.Unranked -> return "No $distance in your record book yet."
        is BarStanding.Ranked -> standing.best
    }
    if (best.clears(requirement)) return null
    val gap = best.seconds - requirement.withinSeconds
    return "Your best $distance is ${asClock(best.seconds)} — ${asClock(gap)} off the bar."
}
