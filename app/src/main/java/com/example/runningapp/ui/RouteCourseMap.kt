package com.example.runningapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.MapFix
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PointAnnotationGroup
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotation
import com.mapbox.maps.extension.style.layers.properties.generated.IconRotationAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.layers.properties.generated.TextAnchor
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions

/** The same weights a Segment's page draws at, so the two pages of ground read alike. */
private const val CourseLineWidth = 6.0
private const val MarkerRadius = 8.0
private const val MarkerStrokeWidth = 3.0

/** How big an arrow is drawn: wider than the line under it, so it reads as sitting on it. */
private val ArrowSize = 22.dp

/**
 * A course's own map (#465): the line, arrows along it showing which way round it is run, and its
 * start named in words.
 *
 * [line] arrives **already in the order it is run** ([theWayRoundItIsRun]), so a flipped course is
 * drawn flipped by being handed the other way round — the drawing knows nothing about flips.
 *
 * The start is drawn over the finish rather than under it, because on a loop the two are one place
 * and the start is the one a runner needs: where to set off, with the first arrow showing which way.
 * The word "Start" beside it says the same thing to a runner who has not learnt the markers.
 *
 * Its own drawing rather than [SegmentMapSurface]'s, which draws a Segment and has no idea of a way
 * round. What they share — the framing, night and day, the gestures — is [RouteMapSurface]'s.
 */
@Composable
fun RouteCourseMap(line: List<MapFix>, modifier: Modifier = Modifier) {
    if (line.isEmpty()) {
        Box(modifier = modifier)
        return
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val markerFill = MaterialTheme.colorScheme.onSurface
    val markerStroke = MaterialTheme.colorScheme.surface
    val arrowPixels = with(LocalDensity.current) { ArrowSize.roundToPx() }
    val arrows = remember(line) { directionArrowsAlong(line) }
    val arrowIcon = remember(arrowPixels, markerFill, markerStroke) {
        directionArrowBitmap(arrowPixels, markerFill.toArgb(), markerStroke.toArgb())
    }

    RouteMapSurface(framedFixes = line, interactive = false, modifier = modifier) {
        if (line.size >= 2) {
            PolylineAnnotation(points = line.map { it.asPoint() }) {
                this.lineColor = lineColor
                lineWidth = CourseLineWidth
                lineJoin = LineJoin.ROUND
            }
        }
        // One group, with overlap allowed, so no arrow is dropped for landing near a street name or
        // near another arrow — an out-and-back course passes the same street twice, and both of its
        // arrows there are the point.
        PointAnnotationGroup(
            annotations = arrows.map { arrow ->
                PointAnnotationOptions()
                    .withPoint(arrow.at.asPoint())
                    .withIconImage(arrowIcon)
                    .withIconRotate(arrow.bearingDegrees)
            },
        ) {
            iconAllowOverlap = true
            iconIgnorePlacement = true
            // Turned against the map rather than the screen. The same thing today, since these maps
            // never rotate, but an arrow is a heading on the ground.
            iconRotationAlignment = IconRotationAlignment.MAP
        }
        line.lastOrNull()?.let { finish ->
            CircleAnnotation(point = finish.asPoint()) {
                circleRadius = MarkerRadius
                circleColor = markerFill
                circleStrokeColor = markerStroke
                circleStrokeWidth = MarkerStrokeWidth
            }
        }
        line.firstOrNull()?.let { start ->
            CircleAnnotation(point = start.asPoint()) {
                circleRadius = MarkerRadius
                circleColor = markerStroke
                circleStrokeColor = markerFill
                circleStrokeWidth = MarkerStrokeWidth
            }
            PointAnnotationGroup(
                annotations = listOf(
                    PointAnnotationOptions()
                        .withPoint(start.asPoint())
                        .withTextField(ROUTE_START_LABEL)
                        .withTextAnchor(TextAnchor.TOP)
                        .withTextOffset(listOf(0.0, 0.9))
                        .withTextSize(13.0)
                        .withTextColor(markerFill.toArgb())
                        .withTextHaloColor(markerStroke.toArgb())
                        .withTextHaloWidth(2.0)
                ),
            ) {
                textAllowOverlap = true
                textIgnorePlacement = true
            }
        }
    }
}

/** The word printed beside a course's first fix. */
const val ROUTE_START_LABEL = "Start"

/**
 * An arrowhead pointing straight up, [sizePx] square — Mapbox turns it to each arrow's heading.
 *
 * Drawn in the markers' own pair of colours (filled dark, edged light) rather than the line's, so it
 * stands out on the line it sits on and reads on a day map and a night one alike.
 */
private fun directionArrowBitmap(sizePx: Int, fill: Int, edge: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val s = sizePx.toFloat()
    val inset = s * 0.12f
    val head = Path().apply {
        moveTo(s / 2f, inset)
        lineTo(s - inset, s - inset)
        lineTo(s / 2f, s * 0.68f)
        lineTo(inset, s - inset)
        close()
    }
    canvas.drawPath(head, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fill
    })
    canvas.drawPath(head, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = s * 0.08f
        strokeJoin = Paint.Join.ROUND
        color = edge
    })
    return bitmap
}
