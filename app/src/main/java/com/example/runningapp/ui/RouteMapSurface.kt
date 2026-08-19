package com.example.runningapp.ui

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
import com.mapbox.maps.extension.compose.MapboxMapComposable
import com.mapbox.maps.extension.compose.MapboxMapScope
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.rememberMapState
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.extension.compose.style.standard.rememberStandardStyleState
import com.mapbox.maps.plugin.gestures.generated.GesturesSettings
import com.mapbox.maps.plugin.viewport.data.OverviewViewportStateOptions

/**
 * How much room is left around whatever is drawn when the camera is framed on it, in pixels.
 *
 * Enough that markers — drawn either side of the fix they mark — are not clipped by the edge.
 */
private const val FramePaddingPixels = 48.0

/** Whatever is drawn is already its own shape; there is nothing to animate to on arrival. */
private const val FrameImmediately = 0L

/**
 * A map framed on a set of fixes, with everything every route map in this app agrees about — and
 * nothing about what is drawn on it.
 *
 * Two maps read the same ground for different reasons: a Run's route, coloured by the zone the
 * runner's heart was in ([RunTrackMapCard]), and a Segment, which has no heart rate to colour by
 * ([SegmentMapSurface]). What they must never disagree about is the *reading* of a map — which way
 * is up, whether it is night, and whether the whole of the thing is in view — so those live here
 * once and the drawing is [content]'s.
 *
 * Day or night by the sun where the fixes are, and by the clock *now* rather than when they were
 * recorded: this is a map being read this evening, and a run from a bright morning should not be
 * handed back in daylight colours in the dark.
 */
@Composable
fun RouteMapSurface(
    /** Everything that must be in view. The camera is framed to hold all of it. */
    framedFixes: List<MapFix>,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    showScaleBar: Boolean = false,
    /** Room to leave at the top for whatever is drawn over the map — see [RunTrackMapFullScreen]. */
    topInsetPixels: Double = 0.0,
    content: @Composable @MapboxMapComposable MapboxMapScope.() -> Unit,
) {
    val isDaytime = remember(framedFixes) {
        val here = framedFixes.lastOrNull()
        here != null && SunriseSunsetCalculator.isDaytime(
            latitude = here.latitude,
            longitude = here.longitude,
            epochMillis = System.currentTimeMillis()
        )
    }
    val standardStyleState = rememberStandardStyleState()
    SideEffect {
        standardStyleState.configurationsState.lightPreset =
            if (isDaytime) LightPresetValue.DAY else LightPresetValue.NIGHT
    }

    val mapState = rememberMapState {
        // Tilt and rotation are off wherever this app draws a route: a route read from an angle is
        // a route read wrong, and there is no puck here whose heading the map would be following.
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
    // Framed on everything drawn rather than on a guessed zoom: Mapbox works out the camera that
    // holds every fix inside the space this map has, which is the only way a 500 m loop and a half
    // marathon both arrive filling it.
    //
    // Through the viewport's overview state rather than by working the camera out once, because
    // once is too early: the frame is asked for as this map enters composition, before it has been
    // laid out and so before its size is known, and a route framed for the wrong size runs off the
    // edge. The overview state holds it in view until the runner pans away from it.
    LaunchedEffect(framed, topInsetPixels) {
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
                .padding(EdgeInsets(topInsetPixels, 0.0, 0.0, 0.0))
                .animationDurationMs(FrameImmediately)
                .build()
        )
    }

    MapboxMap(
        modifier = modifier,
        // Off unless asked for: on a card two hundred pixels tall it lands across the top, over the
        // route it is supposed to be helping read. A full-screen map has room for it.
        scaleBar = { if (showScaleBar) ScaleBar() },
        mapViewportState = mapViewportState,
        mapState = mapState,
        style = { MapboxStandardStyle(standardStyleState = standardStyleState) },
        content = content,
    )
}

/** One fix as Mapbox wants it. Longitude first, which is the mistake this exists to make once. */
internal fun MapFix.asPoint(): Point = Point.fromLngLat(longitude, latitude)
