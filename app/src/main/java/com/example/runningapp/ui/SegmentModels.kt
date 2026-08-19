package com.example.runningapp.ui

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
        compareBy<SegmentEffortRow> { it.elapsedMillis }.thenBy { it.startedAtMillis }
    )
    return efforts
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

/** The quickest time ever run at a Segment, or null where nobody has run it yet. */
fun segmentRecordLabel(efforts: List<SegmentEffortRow>): String? =
    efforts.minOfOrNull { it.elapsedMillis }?.let { formatDuration(it.roundedToSeconds()) }

/**
 * How many times the runner has been over a Segment.
 *
 * The count is the other half of what a PR means. "4:32" on its own says nothing about whether it
 * was the best of two attempts or of fifty.
 */
fun segmentEffortCountLabel(efforts: Int): String = when (efforts) {
    0 -> "No efforts yet"
    1 -> "1 effort"
    else -> "$efforts efforts"
}

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
