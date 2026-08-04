package com.example.runningapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.analysis.TrackMap
import com.example.runningapp.map.SunriseSunsetCalculator
import com.example.runningapp.ui.theme.RunningUiTokens
import com.example.runningapp.ui.workout.zoneChartColor
import com.mapbox.geojson.Point
import com.mapbox.geojson.MultiPoint
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

private val PreviewHeight = 200.dp

/** Thicker than the live map's trail: this one is read at a glance rather than followed. */
private const val RouteLineWidth = 5.0

/**
 * How a stretch the Run recorded no heart rate over is drawn: thinner, and part way to transparent.
 *
 * The colour alone would not say it. The app's amber (`colorScheme.primary`, `0xFFFFA000`) sits a
 * hair from Tempo's own `0xFFF2C037`, and at five pixels of line on a map the two are the same
 * colour — so a Strap that dropped out would read as Zone 3, which is exactly the misreading this
 * map exists to prevent. Thin and faint is the drawing saying it does not know, in a way that
 * survives being the only stretch on the screen with nothing beside it to compare against.
 */
private const val NoHeartRateLineWidth = 3.0
private const val NoHeartRateLineOpacity = 0.65

private const val MarkerRadius = 7.0
private const val MarkerStrokeWidth = 2.5

/**
 * How much room is left around the route when the camera is framed on it, in pixels.
 *
 * Enough that the start and finish markers — drawn either side of the fix they mark — are not
 * clipped by the edge of the card.
 */
private const val FramePaddingPixels = 48.0

/** The frame is the Run's own shape; there is nothing to animate to on arrival. */
private const val FrameImmediately = 0L

/**
 * The Run's route at the top of its detail page (#47): auto-framed, coloured by the zone the
 * runner's heart was in, and tappable for a full-screen version of the same map.
 *
 * The preview never pans. It sits inside a page that scrolls, and a map that swallowed the drag
 * would trap the runner's finger halfway down their own run. Two things hold that: the map's own
 * gestures are off, and a transparent layer over it takes the taps — [clickable] hands vertical
 * drags back to the scrolling column rather than claiming them.
 */
@Composable
fun RunTrackMapCard(trackMap: TrackMap, onOpenFullScreen: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(PreviewHeight)) {
            TrackMapSurface(
                trackMap = trackMap,
                interactive = false,
                showScaleBar = false,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(onClickLabel = "Open full-screen map", onClick = onOpenFullScreen)
            )
        }
    }
}

/**
 * The same route, full-bleed and interactive — pan and zoom, and back to return (#47).
 *
 * Shown in place of the detail page rather than as a destination of its own, so it opens with the
 * route already worked out and closes onto the page the runner left. [BackHandler] is what makes
 * the system back gesture close it: without it, back would pop the whole detail page.
 */
@Composable
fun RunTrackMapFullScreen(trackMap: TrackMap, title: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // The bar lies over the map rather than beside it, so the route is framed into the space left
    // under it. Measured rather than assumed: the bar is as tall as the status bar above it makes
    // it, and a guessed height puts the top of the route behind the title.
    var barHeightPixels by remember { mutableStateOf(0) }
    Box(modifier = Modifier.fillMaxSize()) {
        TrackMapSurface(
            trackMap = trackMap,
            interactive = true,
            topInsetPixels = barHeightPixels.toDouble(),
            modifier = Modifier.fillMaxSize()
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .onSizeChanged { barHeightPixels = it.height },
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = RunningUiTokens.CardPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(RunningUiTokens.MinTouchTarget)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The route on a map, shared by the preview and the full-screen view so the two cannot draw the
 * same Run differently.
 *
 * Each stretch is its own line in its own colour — the app's chart zone colours, and its amber where
 * the Run recorded no heart rate to colour by. The route's own breaks are already cut into the
 * stretches ([TrackMap]), so nothing here draws across ground the recording never witnessed.
 *
 * Day or night by the sun where the Run finished, and by the clock *now* rather than when it was
 * run: this is a map being read this evening, and a run from a bright morning should not be handed
 * back in daylight colours in the dark.
 */
@Composable
private fun TrackMapSurface(
    trackMap: TrackMap,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    showScaleBar: Boolean = true,
    /** Room to leave at the top for whatever is drawn over the map — see [RunTrackMapFullScreen]. */
    topInsetPixels: Double = 0.0,
) {
    val isDaytime = remember(trackMap) {
        SunriseSunsetCalculator.isDaytime(
            latitude = trackMap.finish.latitude,
            longitude = trackMap.finish.longitude,
            epochMillis = System.currentTimeMillis()
        )
    }
    val standardStyleState = rememberStandardStyleState()
    SideEffect {
        standardStyleState.configurationsState.lightPreset =
            if (isDaytime) LightPresetValue.DAY else LightPresetValue.NIGHT
    }

    val mapState = rememberMapState {
        // Pan and zoom in full screen and nothing at all in the preview. Tilt and rotation are off
        // in both: a route read from an angle is a route read wrong, and there is no puck here whose
        // heading the map would be following.
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
    val framed = remember(trackMap) {
        MultiPoint.fromLngLats(trackMap.framedFixes.map { it.asPoint() })
    }
    // Framed on the whole route rather than on a guessed zoom: Mapbox works out the camera that
    // holds every fix inside the space this map has, which is the only way a 500 m loop and a half
    // marathon both arrive filling the card.
    //
    // Through the viewport's overview state rather than by working the camera out once, because
    // once is too early: the frame is asked for as this map enters composition, before it has been
    // laid out and so before its size is known, and a route framed for the wrong size runs off the
    // edge. The overview state holds the route in view until the runner pans away from it.
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

    val noHeartRateColor = MaterialTheme.colorScheme.primary
    val markerFill = MaterialTheme.colorScheme.onSurface
    val markerStroke = MaterialTheme.colorScheme.surface

    MapboxMap(
        modifier = modifier,
        // No scale bar on the preview: it lands across the top of a card two hundred pixels tall,
        // over the route it is supposed to be helping read. The full-screen map has room for it.
        scaleBar = { if (showScaleBar) ScaleBar() },
        mapViewportState = mapViewportState,
        mapState = mapState,
        style = { MapboxStandardStyle(standardStyleState = standardStyleState) }
    ) {
        trackMap.stretches.forEach { stretch ->
            val zone = stretch.zone
            PolylineAnnotation(points = stretch.fixes.map { it.asPoint() }) {
                lineColor = if (zone == null) noHeartRateColor else zoneChartColor(zone)
                lineWidth = if (zone == null) NoHeartRateLineWidth else RouteLineWidth
                lineOpacity = if (zone == null) NoHeartRateLineOpacity else 1.0
                lineJoin = LineJoin.ROUND
            }
        }
        // Start hollow, finish filled — the same two colours swapped, so the pair reads on a dark
        // map and a light one and needs no colour vision to tell apart.
        CircleAnnotation(point = trackMap.start.asPoint()) {
            circleRadius = MarkerRadius
            circleColor = markerStroke
            circleStrokeColor = markerFill
            circleStrokeWidth = MarkerStrokeWidth
        }
        CircleAnnotation(point = trackMap.finish.asPoint()) {
            circleRadius = MarkerRadius
            circleColor = markerFill
            circleStrokeColor = markerStroke
            circleStrokeWidth = MarkerStrokeWidth
        }
    }
}

private fun MapFix.asPoint(): Point = Point.fromLngLat(longitude, latitude)
