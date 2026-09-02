package com.example.runningapp.ui

import com.example.runningapp.data.RouteRunRow
import com.example.runningapp.ranOn
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What a Route's own page says about the Runs remembered on it (#420).
 *
 * Pure and outside the composable, the bargain [routeRowSubtitle] and [segmentEffortsUi] make: what
 * the runner reads is the feature, so it is pinned by a unit test rather than by opening the page.
 *
 * **Remembered, never guessed.** A Run is on this course because the app wrote the course's id on
 * the Run — at START, from the course the runner picked (`RunnerSession.ranAlongRouteId`, #56), or
 * at the moment the course was traced off that very Run (#55). Nothing here matches a shape to a
 * course: two Runs are matched to each other by geometry elsewhere (#73), and that rule deliberately
 * never reaches into the library.
 *
 * **A Walk holds no best time.** A course's best is a record over one piece of ground, exactly as a
 * Segment's PR is, and the app's rule for both is that a Walk contests no record
 * ([com.example.runningapp.data.RunnerSession.isWalk], #275). The Walk keeps its row and is told
 * why ([ROUTE_RUN_WALK_NOTE]).
 */

/**
 * How far off a course's own length a Run may be and still be raced against the others.
 *
 * A tenth, and the reason for a band at all is that a Run is never exactly the course: a phone's
 * fixes wander, a runner overshoots the finish, and the course itself is a thinned line rather than
 * the ground. Outside it the Run is not a slower attempt at this course — it is a different outing
 * that happened to set off on it — and crowning one would hand the runner a best time they never ran.
 */
const val ROUTE_BEST_DISTANCE_TOLERANCE = 0.10

/** One Run on a course, as the page prints it. */
data class RouteRunUi(
    val sessionId: Long,
    /** The day the runner ran it, in their own day rather than the phone's (#304). */
    val dateLabel: String,
    /** How far the Run itself went, which is not the course's own length and is not meant to be. */
    val distanceLabel: String,
    val timeLabel: String,
    /**
     * The clock the best and the average are worked out from — the Run's moving time where it has
     * one and its elapsed time where it does not, which is the clock its own page shows.
     */
    val elapsedSeconds: Long,
    /**
     * Whether this Run is one of the Runs the best and the average are drawn from.
     *
     * False is printed rather than hidden ([notCountedNote]). A Run that vanished from its own
     * course's page with no explanation reads as lost data.
     */
    val countsForBest: Boolean,
    /**
     * Why it takes no part, in the runner's words — null exactly where [countsForBest] is true.
     *
     * A reason rather than a flag, because there is more than one and they are not the same news:
     * "you did not run this far" is about the outing, and "you called this a walk" is about a mark
     * the runner made and can unmake on the Run's own page.
     */
    val notCountedNote: String?,
    /** Whether this is the quickest of the Runs that count — the one the page calls the best. */
    val isBest: Boolean,
)

/**
 * Every remembered Run on one course as the page shows them: newest first, with the quickest of the
 * ones that count marked.
 *
 * Built from the one list the page reads, so the best time at the top and the row marked best
 * further down cannot be two different answers taken a moment apart — the rule [segmentEffortsUi]
 * keeps for the same reason.
 *
 * A tie leaves the best with the earlier Run, the rule the record book keeps
 * ([com.example.runningapp.analysis.recordBookOf]): a best is the runner's until somebody actually
 * beats it, and matching a time you already ran is not beating it.
 */
fun routeRunsUi(
    rows: List<RouteRunRow>,
    routeDistanceMeters: Double,
    zone: ZoneId = ZoneId.systemDefault(),
): List<RouteRunUi> {
    // Worked out once here rather than per row, and stated as a distance rather than a ratio so the
    // boundary is the same arithmetic in both directions.
    val tolerance = routeDistanceMeters * ROUTE_BEST_DISTANCE_TOLERANCE

    /** Why this Run takes no part in the best or the average, or null where it does. */
    fun whyNotCounted(row: RouteRunRow): String? {
        // A Walk contests no record, and a best time over one course is a record over one piece of
        // ground exactly as a Segment's PR is ([RunnerSession.isWalk],
        // [com.example.runningapp.segments.mayHoldSegmentEfforts], #275). Asked first, because it is
        // the runner's own word about the outing and it is true whatever ground they covered.
        if (row.isWalk) return ROUTE_RUN_WALK_NOTE
        // A course with no length of its own has no band, so nothing is inside it. Reachable: a row
        // is only ever measured off the line it was kept with, and a line that measured nothing is
        // a course nothing can be raced over.
        if (routeDistanceMeters <= 0.0) return ROUTE_RUN_NOT_COUNTED_NOTE
        // A Run with no clock cannot be raced whatever ground it covered — the Run that died in its
        // first seconds, and treadmill history with nothing on it.
        if (row.clockSeconds() <= 0L) return ROUTE_RUN_NOT_COUNTED_NOTE
        if (abs(row.distanceKm * 1_000.0 - routeDistanceMeters) > tolerance) {
            return ROUTE_RUN_NOT_COUNTED_NOTE
        }
        return null
    }

    // The app's one rule for placing times over one piece of ground ([quickestFirst]), rather than
    // a fourth spelling of it: a tie leaves the best with the earlier Run, because matching a time
    // you already ran is not beating it.
    val best = rows.filter { whyNotCounted(it) == null }.minWithOrNull(
        quickestFirst(
            elapsed = { it.clockSeconds() },
            startedAt = { it.startTime },
            rowId = { it.sessionId },
        )
    )

    return rows
        // Sorted here as well as in the query behind it, so this says what the page shows rather
        // than inheriting it from whichever reader happens to have supplied the rows.
        .sortedByDescending { it.startTime }
        .map { row ->
            val day = ranOn(row.startTime, row.ranAtUtcOffsetSeconds, zone)
            val why = whyNotCounted(row)
            RouteRunUi(
                sessionId = row.sessionId,
                dateLabel = ROUTE_RUN_DATE_FORMAT.format(day),
                distanceLabel = routeDistanceLabel(row.distanceKm * 1_000.0),
                timeLabel = formatDuration(row.clockSeconds()),
                elapsedSeconds = row.clockSeconds(),
                countsForBest = why == null,
                notCountedNote = why,
                isBest = row.sessionId == best?.sessionId,
            )
        }
}

/**
 * The quickest Run of a list [routeRunsUi] has already built, or null where none of them counts.
 *
 * Read off the built list rather than measured again off the rows, so the time at the top of the
 * page and the row marked best further down are one answer to one question.
 */
fun routeBestOf(runs: List<RouteRunUi>): RouteRunUi? = runs.firstOrNull { it.isBest }

/**
 * The average of the Runs that count, or null where none of them does.
 *
 * Only the ones inside the band, for the reason only they can hold the best: an average that took in
 * a two-kilometre jog down a five-kilometre course would say the runner is quicker at the course
 * than they have ever been.
 */
fun routeAverageTimeLabel(runs: List<RouteRunUi>): String? {
    val counted = runs.filter { it.countsForBest }
    if (counted.isEmpty()) return null
    return formatDuration((counted.sumOf { it.elapsedSeconds }.toDouble() / counted.size).roundToLong())
}

/** How many Runs the runner has been remembered on this course. */
fun routeRunCountLabel(runs: Int): String =
    if (runs == 1) "1 run on this route" else "$runs runs on this route"

/** What the page calls the two numbers drawn from the Runs that count. */
const val ROUTE_BEST_TIME_TITLE: String = "Best time"
const val ROUTE_AVERAGE_TIME_TITLE: String = "Average time"

/** What the page calls the list under them. */
const val ROUTE_RUNS_TITLE: String = "Your runs on this route"

/**
 * Why a Run in the list has no part in the best or the average: it did not cover this course.
 *
 * Said on the row itself rather than left to be worked out from two distances, because the runner's
 * question at that moment is "why is my quickest time not the best" and the answer is here.
 */
const val ROUTE_RUN_NOT_COUNTED_NOTE: String = "Not counted — too far off this route's distance"

/**
 * The other reason: the runner marked this outing a Walk.
 *
 * Its own words rather than [ROUTE_RUN_NOT_COUNTED_NOTE], because it is different news. The
 * distance note is about the outing and nothing can be done about it; this is about a mark the
 * runner made on the finish sheet and can unmake on the Run's own page, and saying "too far off
 * this route's distance" about a lap they walked the whole way round would simply be untrue.
 */
const val ROUTE_RUN_WALK_NOTE: String = "Not counted — marked as a walk"

/**
 * What the page says where Runs have been remembered on this course but not one of them came close
 * enough to its length to be raced.
 *
 * Said rather than left as two missing numbers: the runner can see their times in the list, so a
 * page with no best on it owes them the reason. It points at the rows rather than naming one cause,
 * because there is more than one ([ROUTE_RUN_NOT_COUNTED_NOTE], [ROUTE_RUN_WALK_NOTE]) and the rows
 * are where each Run's own is written.
 */
const val NO_COUNTED_ROUTE_RUNS_MESSAGE: String =
    "No best time yet. None of these runs counts towards one — each row says why."

/**
 * What the page says where no Run has ever been remembered on this course.
 *
 * Names how a Run comes to be on a course, because there are exactly two ways and neither is
 * obvious: pick the course before you set off, or save the run you have already been for as one.
 */
const val NO_ROUTE_RUNS_MESSAGE: String =
    "No runs on this route yet. Pick it before you start a run, or save a run you have already " +
        "been for as a route, and it will show up here."

/** The clock this Run is raced on: its moving time where it has one, its elapsed where it does not. */
private fun RouteRunRow.clockSeconds(): Long = movingTimeSeconds ?: durationSeconds

private val ROUTE_RUN_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
