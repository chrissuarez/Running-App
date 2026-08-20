package com.example.runningapp.ui

import com.example.runningapp.analysis.Medal
import com.example.runningapp.data.RunSegmentEffortRow
import com.example.runningapp.data.Segment
import com.example.runningapp.data.SegmentEffortRow
import com.example.runningapp.data.formatMinutesPerKm
import com.example.runningapp.ranOn
import com.example.runningapp.segments.SegmentCut
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    val dateLabel: String,
    val timeLabel: String,
    val paceLabel: String,
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
            SegmentEffortUi(
                effortId = effort.effortId,
                sessionId = effort.sessionId,
                dateLabel = EFFORT_DATE_FORMAT.format(
                    ranOn(effort.startedAtMillis, effort.ranAtUtcOffsetSeconds, zone)
                ),
                timeLabel = formatDuration(effort.elapsedMillis.roundedToSeconds()),
                paceLabel = segmentPaceLabel(effort.elapsedMillis, distanceMeters),
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
