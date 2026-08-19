package com.example.runningapp.ui

import com.example.runningapp.data.Segment
import com.example.runningapp.segments.SegmentCut
import java.util.Locale
import kotlin.math.roundToInt

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
 * Where a Segment came from, on its own page.
 *
 * Said in both directions, because the deleted case is the one a runner will wonder about: a place
 * whose Run is gone is still the place, and the page says so rather than leaving a blank where the
 * provenance used to be.
 */
fun segmentSourceLabel(segment: Segment): String =
    if (segment.sourceSessionId == null) {
        "Traced from a run you have since deleted. The segment itself is unaffected."
    } else {
        "Traced from one of your runs."
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
