package com.example.runningapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.segments.segmentCutOf
import com.example.runningapp.segments.unbrokenStretchesOf
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotation
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin

/** The chosen stretch, drawn to be followed; the same weight the run's own route is drawn at. */
private const val SegmentLineWidth = 6.0

/**
 * How the rest of the Run is drawn behind the chosen stretch: thinner and part way to transparent.
 *
 * It is context rather than the subject. A runner marking out a hill needs to see where that hill
 * sits in the outing they ran, and a background drawn at the same weight as the choice would leave
 * the two indistinguishable at the moment the choice is being made.
 */
private const val BehindLineWidth = 3.0
private const val BehindLineOpacity = 0.45

private const val MarkerRadius = 8.0
private const val MarkerStrokeWidth = 3.0

/**
 * What the camera is framed on: the Run the choice is being cut out of, wherever there is one, and
 * the choice itself only on a Segment's own page where there is not.
 *
 * The Run rather than the choice, because the choice moves and the Run does not. Framing on the
 * stretch under the handles would hand the map a new shape to fit on every step of the slider, so a
 * runner who zoomed in to place a mark precisely would be thrown back out to the whole route by the
 * first pixel of the drag — aiming at a target that moves as they aim. Nothing is lost by it: a
 * stretch is cut from one unbroken piece of the recording ([unbrokenStretchesOf] is what
 * [segmentCutOf] cuts), so the Run already holds every fix the choice can ever be made of.
 */
internal fun segmentFramingFixes(
    segment: List<MapFix>,
    runBehind: List<List<MapFix>>,
): List<MapFix> = if (runBehind.isEmpty()) segment else runBehind.flatten()

/**
 * A stretch of ground on a map: the Segment being marked out or the one being looked at, with
 * whatever it was cut from drawn faintly behind it (#69).
 *
 * Its own drawing rather than [RunTrackMapCard]'s, because the two answer different questions. A
 * Run's map is coloured by the zone the runner's heart was in — that is the whole reason it is
 * drawn. A Segment has no heart rate and no clock yet: it is a piece of ground, and drawing it in
 * zone colours would be inventing a reading of a Run this row deliberately does not depend on. What
 * the two *do* share — the framing, the gestures, night and day — is [RouteMapSurface]'s.
 *
 * [runBehind] is the unbroken stretches of the Run the choice is being cut out of, and is empty on
 * a Segment's own page, where there is no Run to show it inside of.
 */
@Composable
fun SegmentMapSurface(
    segment: List<MapFix>,
    runBehind: List<List<MapFix>>,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val framedFixes = remember(segment, runBehind) { segmentFramingFixes(segment, runBehind) }
    if (framedFixes.isEmpty()) {
        Box(modifier = modifier)
        return
    }

    val segmentColor = MaterialTheme.colorScheme.primary
    val behindColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerFill = MaterialTheme.colorScheme.onSurface
    val markerStroke = MaterialTheme.colorScheme.surface

    RouteMapSurface(
        framedFixes = framedFixes,
        interactive = interactive,
        modifier = modifier,
    ) {
        // The Run first, so the choice is drawn over it and never under it.
        runBehind.forEach { stretch ->
            if (stretch.size >= 2) {
                PolylineAnnotation(points = stretch.map { it.asPoint() }) {
                    lineColor = behindColor
                    lineWidth = BehindLineWidth
                    lineOpacity = BehindLineOpacity
                    lineJoin = LineJoin.ROUND
                }
            }
        }
        if (segment.size >= 2) {
            PolylineAnnotation(points = segment.map { it.asPoint() }) {
                lineColor = segmentColor
                lineWidth = SegmentLineWidth
                lineJoin = LineJoin.ROUND
            }
        }
        // Start hollow, finish filled — the same pair the Run's own map uses, so the two ends read
        // on a dark map and a light one and need no colour vision to tell apart.
        segment.firstOrNull()?.let { start ->
            CircleAnnotation(point = start.asPoint()) {
                circleRadius = MarkerRadius
                circleColor = markerStroke
                circleStrokeColor = markerFill
                circleStrokeWidth = MarkerStrokeWidth
            }
        }
        segment.lastOrNull()?.let { finish ->
            CircleAnnotation(point = finish.asPoint()) {
                circleRadius = MarkerRadius
                circleColor = markerFill
                circleStrokeColor = markerStroke
                circleStrokeWidth = MarkerStrokeWidth
            }
        }
    }
}
