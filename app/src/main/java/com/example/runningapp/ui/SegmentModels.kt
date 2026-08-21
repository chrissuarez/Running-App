package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import com.example.runningapp.data.RunSegmentEffortRow
import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentEffortRow
import com.example.runningapp.data.formatMinutesPerKm
import com.example.runningapp.ranOn
import com.example.runningapp.segments.SegmentCut
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What the Segments screens say (#69).
 *
 * Pure and outside the composables, the same bargain [routeRowSubtitle] and its neighbours make:
 * a refusal is the only thing a runner gets when a stretch cannot be kept, so the words are the
 * feature and are worth pinning in a test rather than reading off a phone.
 */

/**
 * How long a stretch is, in the unit a runner would say it in.
 *
 * Metres below a kilometre. A Segment is usually a hill or a straight — "320 m" is how the runner
 * thinks of one, and "0.32 km" makes them do arithmetic to read their own hill.
 */
fun segmentDistanceLabel(distanceMeters: Double): String =
    if (distanceMeters < 1_000.0) "${distanceMeters.roundToInt()} m"
    else String.format(Locale.UK, "%.2f km", distanceMeters / 1000.0)

/** The line under a Segment's name in the list: how far it goes, and nothing else yet. */
fun segmentRowSubtitle(segment: Segment): String = segmentDistanceLabel(segment.distanceMeters)

/**
 * What a Segment's page has to say about where it came from — which is nothing, unless the Run it
 * was traced from is gone.
 *
 * Null in the ordinary case on purpose. "Traced from one of your runs" is true of every Segment
 * there is, so printing it says nothing and costs the page a line. The deleted case is the one a
 * runner will wonder about, because the row now points nowhere and the page should say the place
 * itself is untouched.
 */
fun segmentSourceLabel(segment: Segment): String? =
    if (segment.sourceSessionId == null) {
        "Traced from a run you have since deleted. The segment itself is unaffected."
    } else {
        null
    }

/** How far into the Run a mark sits, for the line under the two handles. */
fun segmentMarkLabel(distanceMeters: Double): String =
    String.format(Locale.UK, "%.2f km", distanceMeters / 1000.0)

/**
 * What the creation screen says about the stretch currently marked out — the length of it, or why
 * it cannot be kept.
 *
 * One string for all three cases, because they occupy one line on the screen and a runner dragging
 * a handle is watching that line rather than hunting for a message somewhere else.
 */
fun segmentCutSummary(cut: SegmentCut): String = when (cut) {
    is SegmentCut.Cut -> "${segmentDistanceLabel(cut.distanceMeters)} of this run"
    SegmentCut.TooShort ->
        "Move the handles apart — a segment needs some ground between its start and its end."
    SegmentCut.SpansABreak ->
        "That stretch crosses a gap in the recording — the run paused, or lost signal. Move a " +
            "handle so both marks are on one unbroken piece of the track."
}

/** What the screen says when a Run has no unbroken stretch to cut a Segment out of at all. */
const val NO_STRETCH_TO_CUT_MESSAGE: String =
    "This run's track has no unbroken stretch to cut a segment from."

/** What the Segments screen says the moment one lands, so saving is visibly saving. */
fun segmentSavedMessage(name: String): String = "Saved “$name” to your segments."

// --- What a Segment's page says about the times run at it (#70) ---

/** One effort as the page prints it. */
data class SegmentEffortUi(
    val effortId: Long,
    val sessionId: Long,
    /** The day the runner ran it, in their own day rather than the phone's (#304). */
    val date: LocalDate,
    val dateLabel: String,
    val timeLabel: String,
    val paceLabel: String,
    /**
     * What the effort took, kept beside the words it was printed as.
     *
     * The page ranks these efforts and charts them as well as listing them (#72), and every one of
     * those readings has to settle a tie the same way. Carrying the numbers on the row the page
     * already holds is what makes that one answer rather than three readings of the same rows taken
     * a moment apart.
     */
    val elapsedMillis: Long,
    val startedAtMillis: Long,
    /** Whether this is the quickest of them all — the one the page calls the PR. */
    val isRecord: Boolean,
)

/**
 * Every effort at a Segment as the page shows them: newest first, with the quickest of them marked.
 *
 * Built from the one list the page reads, so the PR at the top and the row it belongs to further
 * down cannot be two different answers taken a moment apart.
 *
 * A tie leaves the record with the earlier effort, the rule the record book keeps
 * ([com.example.runningapp.analysis.recordBookOf]): a PR is the runner's until somebody actually
 * beats it, and matching a time you already ran is not beating it.
 */
fun segmentEffortsUi(
    efforts: List<SegmentEffortRow>,
    distanceMeters: Double,
    zone: ZoneId = ZoneId.systemDefault(),
): List<SegmentEffortUi> {
    val record = efforts.minWithOrNull(
        quickestFirst(
            elapsed = { it.elapsedMillis },
            startedAt = { it.startedAtMillis },
            effortId = { it.effortId },
        )
    )
    return efforts
        // Sorted here as well as in the query behind it, so this says what the page shows rather
        // than inheriting it from whichever reader happens to have supplied the rows.
        .sortedByDescending { it.startedAtMillis }
        .map { effort ->
            val day = ranOn(effort.startedAtMillis, effort.ranAtUtcOffsetSeconds, zone)
            SegmentEffortUi(
                effortId = effort.effortId,
                sessionId = effort.sessionId,
                date = day,
                dateLabel = EFFORT_DATE_FORMAT.format(day),
                timeLabel = formatDuration(effort.elapsedMillis.roundedToSeconds()),
                paceLabel = segmentPaceLabel(effort.elapsedMillis, distanceMeters),
                elapsedMillis = effort.elapsedMillis,
                startedAtMillis = effort.startedAtMillis,
                isRecord = effort.effortId == record?.effortId,
            )
        }
}

/**
 * The quickest effort of a list [segmentEffortsUi] has already built, or null where nobody has run
 * the Segment yet.
 *
 * Read off the built list rather than measured again off the rows, so the PR at the top of the page
 * and the row marked PR further down are one answer to one question — asked twice, they would be two
 * readings free to break the tie differently.
 */
fun segmentRecordOf(efforts: List<SegmentEffortUi>): SegmentEffortUi? = efforts.firstOrNull { it.isRecord }

/**
 * How many times the runner has been over a Segment.
 *
 * The count is the other half of what a PR means. "4:32" on its own says nothing about whether it
 * was the best of two attempts or of fifty.
 */
fun segmentEffortCountLabel(efforts: Int): String = if (efforts == 1) "1 effort" else "$efforts efforts"

/** What the page says where nothing has ever been run over the ground. */
const val NO_SEGMENT_EFFORTS_MESSAGE: String =
    "You have not run this segment yet. Every run you save from now on is checked against it."

/** How quickly an effort covered the Segment, in the unit a runner talks in. */
private fun segmentPaceLabel(elapsedMillis: Long, distanceMeters: Double): String {
    if (elapsedMillis <= 0L || distanceMeters <= 0.0) return "--:-- /km"
    val minutesPerKm = (elapsedMillis / 60_000.0) / (distanceMeters / 1_000.0)
    return "${formatMinutesPerKm(minutesPerKm)} /km"
}

/** Milliseconds as the seconds the page prints, rounded rather than cut off. */
private fun Long.roundedToSeconds(): Long = (this / 1000.0).roundToLong()

private val EFFORT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)


// --- What a Run's own page says about the Segments it went over (#71) ---

/**
 * One Segment this Run crossed, as the Run's page prints it.
 *
 * [medal] is the effort's place in that Segment's all-time top three, or null outside it — the same
 * three places, in the same metals, the achievements card hands out for the record book (#49). It is
 * the reason the card is worth a runner's attention: a list of times they already knew they ran is
 * not news, and "you have never been quicker up that hill" is.
 */
data class RunSegmentEffortUi(
    val effortId: Long,
    val segmentId: Long,
    val segmentName: String,
    val timeLabel: String,
    val paceLabel: String,
    val medal: Medal?,
)

/**
 * What one Run is worth at the Segments it went over, placed against every other effort ever run
 * at them (#71).
 *
 * [rows] is every effort at those Segments — this Run's and its rivals' — because a place cannot be
 * worked out from one Run's own efforts. Only the Run's own come back out; the rest are what decided
 * the medals.
 *
 * Listed in the order the runner went over the ground, so the card reads as the Run did rather than
 * as a league table. The same Run crossing one Segment twice therefore gets a row each, and can hold
 * two of the three places itself, which is exactly what happened out on the road.
 *
 * A tie leaves the place with the earlier effort, the rule the record book and a Segment's own page
 * both keep ([segmentEffortsUi]): a place is the runner's until somebody actually beats it, and
 * matching a time you already ran is not beating it.
 */
fun runSegmentEffortsUi(rows: List<RunSegmentEffortRow>, sessionId: Long): List<RunSegmentEffortUi> {
    val order = quickestFirst<RunSegmentEffortRow>(
        elapsed = { it.elapsedMillis },
        startedAt = { it.startedAtMillis },
        effortId = { it.effortId },
    )
    val placesBySegment = rows.groupBy { it.segmentId }
        .mapValues { (_, atSegment) ->
            atSegment.sortedWith(order).withIndex().associate { (place, row) -> row.effortId to place }
        }
    return rows
        .filter { it.sessionId == sessionId }
        .sortedBy { it.startedAtMillis }
        .map { row ->
            val place = placesBySegment[row.segmentId]?.get(row.effortId)
            RunSegmentEffortUi(
                effortId = row.effortId,
                segmentId = row.segmentId,
                segmentName = row.segmentName,
                timeLabel = formatDuration(row.elapsedMillis.roundedToSeconds()),
                paceLabel = segmentPaceLabel(row.elapsedMillis, row.distanceMeters),
                // Off the enum itself, the way the record book decides how deep it goes
                // ([com.example.runningapp.analysis.Medal]): a list of three written out here
                // would be a second answer to "how many places are worth a medal".
                medal = place?.let { Medal.entries.getOrNull(it) },
            )
        }
}

/**
 * How efforts at one Segment are placed against each other — quickest first, and a tie kept by
 * whoever ran it first.
 *
 * Written once and read by both pages that rank efforts, because a Segment's page calling one time
 * the PR while the Run's page hands the medal to another would be the same question answered twice.
 * The effort id is the last word so the order is total: two Runs *can* carry the same start instant,
 * and an order that left them tied would place them differently on two reads of the same rows.
 */
private fun <T> quickestFirst(
    elapsed: (T) -> Long,
    startedAt: (T) -> Long,
    effortId: (T) -> Long,
): Comparator<T> = compareBy(elapsed).thenBy(startedAt).thenBy(effortId)


// --- The full trophy view: the all-time top ten, and the trend behind it (#72) ---

/** How deep the ranked list goes. */
const val SEGMENT_TOP_COUNT: Int = 10

/**
 * One effort in the ranked list: where it placed, and the row the page already built for it.
 *
 * [medal] is the top three, in the same three metals a Run's own card and the record book hand out
 * (see [runSegmentEffortsUi]) — a place is a place, and a runner should not have to learn two of
 * them. Below third there is no metal, only the number.
 */
data class SegmentRankedEffortUi(
    val place: Int,
    val medal: Medal?,
    val effort: SegmentEffortUi,
)

/**
 * The quickest efforts ever run at a Segment, best first, cut at [SEGMENT_TOP_COUNT].
 *
 * The cut is to the top of the page and never to the runner's history: past ten, the page carries
 * every effort again underneath, newest first ([SEGMENT_ALL_EFFORTS_TITLE]). A page that quietly
 * stopped at ten would take runs off a runner who had done nothing but keep running.
 *
 * Ranked off the list [segmentEffortsUi] built rather than off the rows behind it, so the PR card at
 * the top of the page and the gold disc in this list cannot disagree: they are one reading of one
 * list. A tie keeps the earlier effort ahead, the rule the record book keeps — matching a time you
 * already ran is not beating it.
 */
fun segmentTopEfforts(efforts: List<SegmentEffortUi>): List<SegmentRankedEffortUi> = efforts
    .sortedWith(
        quickestFirst(
            elapsed = { it.elapsedMillis },
            startedAt = { it.startedAtMillis },
            effortId = { it.effortId },
        )
    )
    .take(SEGMENT_TOP_COUNT)
    .mapIndexed { index, effort ->
        SegmentRankedEffortUi(
            place = index + 1,
            // Off the enum itself, the way the record book decides how deep the metals go
            // ([com.example.runningapp.analysis.Medal]).
            medal = Medal.entries.getOrNull(index),
            effort = effort,
        )
    }

/**
 * What the ranked list is called, which depends on whether it is leaving anything out.
 *
 * A page holding every effort there has ever been must not call itself a top ten: that would tell
 * the runner something was cut when nothing was, and send them hunting for a rest of the list that
 * does not exist. Where efforts really are left out, the count says how many, because "top 10" out
 * of eleven and out of two hundred are very different facts about the same ten times.
 */
fun segmentTopTitle(total: Int): String =
    if (total <= SEGMENT_TOP_COUNT) "Every effort, quickest first"
    else "Top $SEGMENT_TOP_COUNT of ${segmentEffortCountLabel(total)}"

/** One day on the trend chart: when it was, how far into the chart it sits, and what it took. */
data class SegmentTrendPoint(
    val effortId: Long,
    val date: LocalDate,
    /** Days since the first point, which is the x the chart is drawn against. */
    val dayOffset: Int,
    val seconds: Long,
    val dateLabel: String,
    val timeLabel: String,
)

/**
 * The trend of the times at a Segment, oldest first — one point per day, at that day's quickest.
 *
 * One point per day and not one per effort, because the x axis is the calendar: two efforts on one
 * date have nowhere to sit apart on it, and the quickest is the time the runner would quote for that
 * day anyway. Every effort is still listed under the chart — in the ranked ten, and past ten in the
 * newest-first list under that ([SEGMENT_ALL_EFFORTS_TITLE]).
 *
 * Placed by the calendar rather than evenly, so a two-year gap is drawn as a two-year gap. Even
 * spacing would make the chart's own claim — whether the runner is getting quicker across months and
 * years — a lie about their own history.
 *
 * Empty where there is no trend to draw: fewer than two days with an effort on them. One point is
 * not a line, and two points sharing a date is a vertical mark rather than a trend. The page shows
 * the list on its own in both cases rather than an empty frame that reads as a chart that broke.
 */
fun segmentTrendPoints(efforts: List<SegmentEffortUi>): List<SegmentTrendPoint> {
    val quickest = quickestFirst<SegmentEffortUi>(
        elapsed = { it.elapsedMillis },
        startedAt = { it.startedAtMillis },
        effortId = { it.effortId },
    )
    val bestPerDay = efforts
        .groupBy { it.date }
        .mapValues { (_, onTheDay) -> onTheDay.sortedWith(quickest).first() }
        .toSortedMap()
    if (bestPerDay.size < 2) return emptyList()

    val firstDay = bestPerDay.firstKey()
    return bestPerDay.map { (date, effort) ->
        SegmentTrendPoint(
            effortId = effort.effortId,
            date = date,
            dayOffset = ChronoUnit.DAYS.between(firstDay, date).toInt(),
            seconds = effort.elapsedMillis.roundedToSeconds(),
            dateLabel = effort.dateLabel,
            timeLabel = effort.timeLabel,
        )
    }
}

/**
 * The whole number of days the chart's x axis steps in.
 *
 * Vico steps an axis by the greatest common divisor of the gaps between its x values rather than by
 * one, so the label spacing has to be counted in those steps and not in points. Six efforts a
 * fortnight apart are six ticks a fortnight wide, not seventy daily ones.
 */
fun segmentTrendStepDays(points: List<SegmentTrendPoint>): Int {
    var step = 0
    points.forEach { step = greatestCommonDivisor(step, it.dayOffset) }
    return step.coerceAtLeast(1)
}

/**
 * How many positions the chart's bottom axis has to label.
 *
 * Not the number of points: the axis steps in whole [segmentTrendStepDays], and a chart drawn
 * against the calendar has a tick at every step whether an effort landed on it or not. Handed to
 * [threeLabelPlacer], which is the Progress screen's own rule for how many of them get a date on
 * them — written once so the two charts cannot drift into labelling differently (#63).
 */
fun segmentTrendAxisTicks(points: List<SegmentTrendPoint>): Int {
    if (points.isEmpty()) return 1
    return (points.last().dayOffset / segmentTrendStepDays(points)) + 1
}

/**
 * What the trend chart is, said in one sentence for a runner who is being read the page.
 *
 * A chart is a picture, and a picture says nothing out loud. The two ends are what the chart is for
 * — the stretch of calendar it covers, and whether the time at the end of it is quicker than the
 * time at the start.
 *
 * Both ends are a day's quickest rather than a day's last, because that is what the chart plots
 * ([segmentTrendPoints]). Calling the last point the most recent time would name a slower later
 * attempt on that day as the one being read out, which is not the time on the chart.
 */
fun segmentTrendDescription(points: List<SegmentTrendPoint>): String? {
    if (points.isEmpty()) return null
    val first = points.first()
    val last = points.last()
    return "Your quickest here from ${first.dateLabel} to ${last.dateLabel}: " +
        "${first.timeLabel} on the first day, ${last.timeLabel} on the latest."
}

/** The seconds up the side of the chart, read back as the times they are. */
fun segmentTrendTimeLabel(seconds: Float): String = formatDuration(seconds.roundToLong())

/** What the runner is told the trend chart is for, under its heading. */
const val SEGMENT_TREND_TITLE: String = "Your times here"

/** What the rest of the efforts are called, where there are more of them than the ranked ten. */
const val SEGMENT_ALL_EFFORTS_TITLE: String = "Every effort, newest first"

/** Why the chart holds fewer points than the list below it does. */
const val SEGMENT_TREND_SUBTITLE: String = "Your quickest time on each day you ran it."

private tailrec fun greatestCommonDivisor(a: Int, b: Int): Int = if (b == 0) a else greatestCommonDivisor(b, a % b)
