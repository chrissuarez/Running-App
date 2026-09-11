package com.example.runningapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.runningapp.analysis.MapFix
import com.mapbox.maps.extension.compose.MapboxMapComposable
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotationGroup
import com.mapbox.maps.plugin.annotation.generated.CircleAnnotationOptions

/**
 * How brightly everything this app draws on a map lights itself: fully, so it keeps its own colour.
 *
 * The Standard style lights its whole scene, and a line or dot added to it is lit with the rest
 * unless it says otherwise. At night that turned an amber course dark brown on a dark map — the one
 * thing on the page a runner could not see (#468). Lit from inside, a line is the colour it was given
 * by day and by night alike. The icons and words drawn over it never needed telling.
 */
internal const val SelfLit = 1.0

/**
 * One dot on the ground — a start, a finish, the scrubber's place — lit from inside like every line
 * ([SelfLit]).
 *
 * A group of one rather than Mapbox's single
 * [com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation], because only a group
 * can be told how brightly to light itself: a dot's emissive strength belongs to the layer it is
 * drawn in, and a single circle does not let its layer be reached. One group per dot keeps each in a
 * layer of its own, drawn in the order it is composed.
 *
 * A group does not move its dot; it deletes it and draws a new one. That is every frame of a drag
 * for the scrubber's dot, and it costs nothing a runner can see: measured on the phone, a drag
 * across the chart still draws in 16 ms a frame.
 */
@Composable
@MapboxMapComposable
internal fun GroundDot(at: MapFix, radius: Double, fill: Color, stroke: Color, strokeWidth: Double) {
    val dot = remember(at, radius, fill, stroke, strokeWidth) {
        listOf(
            CircleAnnotationOptions()
                .withPoint(at.asPoint())
                .withCircleRadius(radius)
                .withCircleColor(fill.toArgb())
                .withCircleStrokeColor(stroke.toArgb())
                .withCircleStrokeWidth(strokeWidth)
        )
    }
    CircleAnnotationGroup(annotations = dot) {
        circleEmissiveStrength = SelfLit
    }
}
