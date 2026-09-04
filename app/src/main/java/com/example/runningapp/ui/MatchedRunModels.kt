package com.example.runningapp.ui

import com.example.runningapp.data.RunShapeCandidate
import com.example.runningapp.data.decoded
import com.example.runningapp.data.formatMinutesPerKm
import com.example.runningapp.ranOn
import com.example.runningapp.routes.CourseShape
import com.example.runningapp.routes.courseRecognising
import com.example.runningapp.segments.runsMatch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What a Run's page says about the other times the runner has run the same route (#73).
 *
 * Pure and outside the composables, the Segments screens' bargain ([segmentEffortsUi]): the words
 * are the feature, and the group behind them is worked out here rather than in SQL because the
 * geometry rule that decides it lives in one place ([runsMatch]).
 *
 * The group is **every Run that matches the one being looked at**, and it is worked out afresh on
 * every read rather than stored. Nothing to go stale is the whole design: delete a Run and the next
 * read has one fewer; mark one a Walk and it leaves; take the shape of a Run twice and the answer
 * does not move.
 */

/** One Run of a group, as its row prints. */
data class MatchedRunUi(
    val sessionId: Long,
    val startTime: Long,
    /** The day the runner ran it, in their own day rather than the phone's (#304). */
    val date: LocalDate,
    val dateLabel: String,
    val distanceLabel: String,
    val timeLabel: String,
    val paceLabel: String,
    /**
     * The pace the trend is drawn against, or null where the Run never worked one out — history from
     * before the app measured pace, and a Run whose clock and distance leave nothing to divide.
     */
    val paceMinPerKm: Double?,
    /** Whether this is the Run whose page is open, so the list can say which one the runner is on. */
    val isThisRun: Boolean,
)

/** A Run's group: the Runs in it, oldest first, and where in them this Run stands. */
data class MatchedRunsUi(
    val runs: List<MatchedRunUi>,
    /** Which of them this Run is, counting from the first — the number the card is built on. */
    val position: Int,
    /**
     * The saved course this ground turns out to be, or null where the library holds none like it
     * (#74).
     *
     * Called a *course* rather than a route in code, the rule CONTEXT.md's Matched Runs entry states:
 * nothing about a group is a Route, and a name borrowed to print is not one either. The runner still
 * reads the word "route", because that is their word for the ground.
 *
 * A name is all that is kept of it, because naming is all the card does: the runner reads "your
     * 3rd run on the Cuckoo Trail" instead of "on this route", which is the same fact told in the
     * runner's own words rather than the app's. Null is not a failure — a group is a fact about two
     * recordings and stands on its own whether or not anybody ever drew the line (#73).
     */
    val courseName: String? = null,
) {
    val count: Int get() = runs.size
}

/**
 * The Runs matching [sessionId], or null where there is no group to show.
 *
 * Null rather than an empty group for the two cases that mean "nothing to say": this Run holds no
 * shape at all — a treadmill Run, a Walk, one recorded before there were tracks — and this Run is
 * the only one that has ever gone this way. A card saying "your 1st run on this route" would be a
 * card on nearly every page in history, telling the runner something they already know.
 *
 * Matched against the subject one at a time rather than chained through each other. Two Runs are in
 * a group because they both look like *this* Run, never because they each look like a third one:
 * chaining would let a fortnight of slowly drifting starts join the far end of a park to the near
 * end of it, and the runner would be shown a route nobody has run.
 */
fun matchedRunsUi(
    candidates: List<RunShapeCandidate>,
    sessionId: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    /**
     * The saved courses the ground could turn out to be (#74). Empty is the answer for a runner with
     * an empty library, and it is also what every caller gave before the library was joined up.
     */
    courses: List<CourseShape> = emptyList(),
): MatchedRunsUi? {
    val subject = candidates.firstOrNull { it.sessionId == sessionId } ?: return null
    val subjectShape = subject.decoded() ?: return null

    val matched = candidates
        .filter { it.sessionId == sessionId || it.decoded()?.let { shape -> runsMatch(subjectShape, shape) } == true }
        // Sorted here as well as in the query behind it, so this says what the page shows rather
        // than inheriting it from whichever reader happens to have supplied the rows.
        .sortedBy { it.startTime }
    if (matched.size < 2) return null

    return MatchedRunsUi(
        runs = matched.map { run ->
            val day = ranOn(run.startTime, run.ranAtUtcOffsetSeconds, zone)
            MatchedRunUi(
                sessionId = run.sessionId,
                startTime = run.startTime,
                date = day,
                dateLabel = MATCHED_RUN_DATE_FORMAT.format(day),
                distanceLabel = String.format(Locale.UK, "%.2f km", run.distanceMeters / 1000.0),
                timeLabel = formatDuration(run.movingTimeSeconds ?: run.durationSeconds),
                paceLabel = paceLabelOf(run.avgPaceMinPerKm),
                paceMinPerKm = run.avgPaceMinPerKm.takeIf { it > 0.0 },
                isThisRun = run.sessionId == sessionId,
            )
        },
        position = matched.indexOfFirst { it.sessionId == sessionId } + 1,
        // Asked of the Run whose page this is, not of the group. The group's Runs are in it because
        // they each match this one, so this one is the only shape every member is known to agree
        // with — asking a different member could name a course the runner is not looking at.
        courseName = courseRecognising(subjectShape, courses)?.name,
    )
}

/**
 * What the card says: which run of this route this one is.
 *
 * The count from the start of the runner's history rather than "one of 14", because the fact worth
 * printing is that they have been here before and kept coming back. A Run in the middle of a group
 * says the number it was on the day it was run — the page is that Run's page, not today's.
 */
fun matchedRunHeadline(position: Int, courseName: String? = null): String =
    "Your ${ordinal(position)} run on ${whatTheGroundIsCalled(courseName)}"

/**
 * How many Runs have gone this way at all, under the headline.
 *
 * Always plural, because a group is never one: a Run nobody has repeated has no group and no card
 * ([matchedRunsUi]). A singular branch here would be a sentence nothing can print.
 */
fun matchedRunCountLabel(count: Int, courseName: String? = null): String =
    "$count runs on ${whatTheGroundIsCalled(courseName)}"

/** What the runner is told the card is, above the chart. */
const val MATCHED_RUNS_TITLE: String = "Matched runs"

/** Why the chart holds fewer points than the list does. */
const val MATCHED_RUNS_TREND_SUBTITLE: String = "Your quickest pace on each day you ran it."

/** What the page listing the group is called. */
fun matchedRunsListTitle(courseName: String? = null): String = "Runs on ${whatTheGroundIsCalled(courseName)}"

/**
 * What the ground is called in a sentence: the saved course's own name where the library holds it,
 * and "this route" where it does not (#74).
 *
 * One place, because the card, its count and the page's title all name the same ground and a runner
 * reading "your 3rd run on the Cuckoo Trail" above "12 runs on this route" would be entitled to think
 * they were two different things. The runner's name is used verbatim — it is theirs, and a course
 * called "the hill" reads as "your 3rd run on the hill", which is how they would say it.
 */
private fun whatTheGroundIsCalled(courseName: String?): String = courseName ?: "this route"

/** One day on the pace trend: when it was, how far into the chart it sits, and what it took. */
data class MatchedRunTrendPoint(
    val sessionId: Long,
    val date: LocalDate,
    /** Days since the first point, which is the x the chart is drawn against. */
    val dayOffset: Int,
    val paceMinPerKm: Double,
    val dateLabel: String,
    val paceLabel: String,
)

/**
 * The pace of a group's Runs over the calendar they were run on, oldest first — one point per day,
 * at that day's quickest.
 *
 * One point per day, and nothing at all below two days — the shared rule ([bestEachDay]), which the
 * Segment trend keeps too. Placed by the calendar rather than evenly, so a two-year gap is drawn as
 * a two-year gap.
 *
 * A Run with no pace is left out of the chart and stays in the list. It is not a slow Run; it is a
 * Run nothing measured a pace for, and plotting it as a zero would draw a cliff the runner never ran.
 */
fun matchedRunTrendPoints(runs: List<MatchedRunUi>): List<MatchedRunTrendPoint> {
    val bestPerDay = bestEachDay(
        runs.filter { it.paceMinPerKm != null },
        day = { it.date },
        better = quickestPaceFirst,
    )
    if (bestPerDay.isEmpty()) return emptyList()

    val firstDay = bestPerDay.firstKey()
    return bestPerDay.map { (date, run) ->
        MatchedRunTrendPoint(
            sessionId = run.sessionId,
            date = date,
            dayOffset = ChronoUnit.DAYS.between(firstDay, date).toInt(),
            paceMinPerKm = run.paceMinPerKm!!,
            dateLabel = run.dateLabel,
            paceLabel = run.paceLabel,
        )
    }
}

/**
 * What the trend chart is, said in one sentence for a runner who is being read the page.
 *
 * A chart is a picture, and a picture says nothing out loud. The two ends are what the chart is for:
 * the stretch of calendar it covers, and whether the pace at the end of it is quicker than the pace
 * at the start. Both ends are a day's quickest rather than a day's last, because that is what the
 * chart plots.
 */
fun matchedRunTrendDescription(points: List<MatchedRunTrendPoint>): String? {
    if (points.isEmpty()) return null
    val first = points.first()
    val last = points.last()
    return "Your quickest pace on this route from ${first.dateLabel} to ${last.dateLabel}: " +
        "${first.paceLabel} on the first day, ${last.paceLabel} on the latest."
}

/** The paces up the side of the chart, read back as the paces they are. */
fun matchedRunPaceAxisLabel(paceMinPerKm: Float): String = paceLabelOf(paceMinPerKm.toDouble())

/**
 * A tie on pace keeps the earlier Run, the rule the record book and the Segments both keep: matching
 * a pace you already ran is not beating it, and an order that left them tied would place them
 * differently on two reads of the same rows.
 */
private val quickestPaceFirst: Comparator<MatchedRunUi> =
    compareBy<MatchedRunUi> { it.paceMinPerKm }.thenBy { it.startTime }.thenBy { it.sessionId }

private fun paceLabelOf(paceMinPerKm: Double): String =
    if (paceMinPerKm <= 0.0) "--:-- /km" else "${formatMinutesPerKm(paceMinPerKm)} /km"

/** "1st", "2nd", "3rd", "14th" — the eleventh to the thirteenth being the exceptions they always are. */
private fun ordinal(position: Int): String {
    val suffix = if (position % 100 in 11..13) {
        "th"
    } else {
        when (position % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }
    return "$position$suffix"
}

private val MATCHED_RUN_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
