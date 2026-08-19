package com.example.runningapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.map.SunriseSunsetCalculator
import com.mapbox.geojson.MultiPoint
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotation
import com.mapbox.maps.extension.compose.rememberMapState
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.extension.compose.style.standard.rememberStandardStyleState
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.plugin.gestures.generated.GesturesSettings
import com.mapbox.maps.plugin.viewport.data.OverviewViewportStateOptions

/** The chosen stretch, drawn to be followed; the same weight the run's own route is drawn at. */
private const val SegmentLineWidth = 6.0

/**
 * The rest of the Run, drawn behind the chosen stretch: thinner and part way to transparent.
 *
 * It is context rather than the subject. A runner marking out a hill needs to see where that hill
 * sits in the outing they ran, and a background drawn at the same weight as the choice would leave
 * the two indistinguishable at the moment the choice is being made.
 */
private const val ContextLineWidth = 3.0
private const val ContextLineOpacity = 0.45

private const val MarkerRadius = 8.0
private const val MarkerStrokeWidth = 3.0

/** The same room [RunTrackMapCard] leaves, so a segment sits in its card the way a route does. */
private const val FramePaddingPixels = 48.0
private const val FrameImmediately = 0L

/**
 * A stretch of ground on a map: the Segment being marked out or the one being looked at, with
 * whatever it was cut from drawn faintly behind it (#69).
 *
 * Its own surface rather than [RunTrackMapCard]'s, because the two answer different questions. A
 * Run's map is coloured by the zone the runner's heart was in — that is the whole reason it is
 * drawn. A Segment has no heart rate and no clock yet: it is a piece of ground, and drawing it in
 * zone colours would be inventing a reading of a Run this row deliberately does not depend on.
 *
 * [context] is the unbroken stretches of the Run behind the choice, and is empty on a Segment's own
 * page, where there is no Run to show it inside of.
 */
@Composable
fun SegmentMapSurface(
    segment: List<MapFix>,
    context: List<List<MapFix>>,
    interactive: Boolean,
    modifier: Modifier = Modifier,
) {
    // Framed on everything drawn, so the camera holds still while a handle is dragged: framing on
    // the choice alone would have the map lurch on every step of the slider, and the runner would
    // be aiming at a target that moves as they aim.
    val framedFixes = remember(segment, context) { context.flatten() + segment }
    if (framedFixes.isEmpty()) {
        Box(modifier = modifier)
        return
    }

    val isDaytime = remember(framedFixes) {
        SunriseSunsetCalculator.isDaytime(
            latitude = framedFixes.first().latitude,
            longitude = framedFixes.first().longitude,
            epochMillis = System.currentTimeMillis()
        )
    }
    val standardStyleState = rememberStandardStyleState()
    SideEffect {
        standardStyleState.configurationsState.lightPreset =
            if (isDaytime) LightPresetValue.DAY else LightPresetValue.NIGHT
    }

    val mapState = rememberMapState {
        // Tilt and rotation are off wherever the app draws a route: a route read from an angle is a
        // route read wrong, and there is no puck here whose heading the map would be following.
        gesturesSettings = GesturesSettings {
            scrollEnabled = interactive
            pinchToZoomEnabled = interactive
            doubleTapToZoomInEnabled = interactive
            doubleTouchToZoomOutEnabled = interactive
            quickZoomEnabled = interactive
            rotateEnabled = false
            pitchEnabled = false
        }
    }

    val mapViewportState = rememberMapViewportState()
    val framed = remember(framedFixes) {
        MultiPoint.fromLngLats(framedFixes.map { it.asPoint() })
    }
    // Through the viewport's overview state rather than by working the camera out once, for the
    // reason [RunTrackMapCard] gives: once is too early, before this map has been laid out and so
    // before its size is known.
    LaunchedEffect(framed) {
        mapViewportState.transitionToOverviewState(
            OverviewViewportStateOptions.Builder()
                .geometry(framed)
                .geometryPadding(
                    EdgeInsets(
                        FramePaddingPixels,
                        FramePaddingPixels,
                        FramePaddingPixels,
                        FramePaddingPixels
                    )
                )
                .animationDurationMs(FrameImmediately)
                .build()
        )
    }

    val segmentColor = MaterialTheme.colorScheme.primary
    val contextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val markerFill = MaterialTheme.colorScheme.onSurface
    val markerStroke = MaterialTheme.colorScheme.surface

    MapboxMap(
        modifier = modifier,
        scaleBar = {},
        mapViewportState = mapViewportState,
        mapState = mapState,
        style = { MapboxStandardStyle(standardStyleState = standardStyleState) }
    ) {
        // The Run first, so the choice is drawn over it and never under it.
        context.forEach { stretch ->
            if (stretch.size >= 2) {
                PolylineAnnotation(points = stretch.map { it.asPoint() }) {
                    lineColor = contextColor
                    lineWidth = ContextLineWidth
                    lineOpacity = ContextLineOpacity
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

private fun MapFix.asPoint(): Point = Point.fromLngLat(longitude, latitude)
