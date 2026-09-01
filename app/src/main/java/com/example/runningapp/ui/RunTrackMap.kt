package com.example.runningapp.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.analysis.TrackMap
import com.example.runningapp.ui.theme.RunningUiTokens
import com.example.runningapp.ui.workout.zoneChartColor
import com.mapbox.maps.extension.compose.MapboxMapComposable
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotation
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin

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
 * The dot the chart's scrubber puts on the route (#48): bigger than the start and finish markers,
 * because it is the thing the runner is moving and has to be able to follow with their eye.
 *
 * In the app's amber, the same colour a stretch with no heart rate is drawn in — which is a
 * collision only in the abstract. That stretch is a thin, faded line that is part of the route; this
 * is a fat ringed disc that exists only while a finger is on the chart and moves as it moves.
 */
private const val ScrubDotRadius = 9.0
private const val ScrubDotStrokeWidth = 3.0

/**
 * The Run's route at the top of its detail page (#47): auto-framed, coloured by the zone the
 * runner's heart was in, and tappable for a full-screen version of the same map.
 *
 * The preview never pans. It sits inside a page that scrolls, and a map that swallowed the drag
 * would trap the runner's finger halfway down their own run. Two things hold that: the map's own
 * gestures are off, and a transparent layer over it takes the taps — [MapCardTapOverlay] hands
 * vertical drags back to the scrolling column rather than claiming them. That layer has a hole in
 * it at Mapbox's own bottom-left corner, so the attribution "i" it draws there can still be tapped
 * (#409).
 *
 * [scrubber] is where the runner's finger is on the chart further down the page, and it is what puts
 * the dot on the route (#48) — so a pace dip or a heart-rate spike is answered with the place it
 * happened. A page whose Run has no distance chart to drag simply never puts anything in it, and so
 * never draws a dot.
 */
@Composable
fun RunTrackMapCard(
    trackMap: TrackMap,
    onOpenFullScreen: () -> Unit,
    scrubber: ChartScrubber,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(PreviewHeight)) {
            TrackMapSurface(
                trackMap = trackMap,
                interactive = false,
                showScaleBar = false,
                // Read here, inside the map's own content, rather than passed in as a value: a drag
                // then repaints the dot and leaves the rest of the page — this card, the chart's
                // frame, the cards around them — exactly as it was.
                scrubbedFix = { scrubber.distanceMeters?.let(trackMap::fixAt) },
                modifier = Modifier.fillMaxSize()
            )
            // Over the map, and holed at Mapbox's own corner — see [MapCardTapOverlay], which
            // states both rules once for this card and the in-run one
            // ([com.example.runningapp.ui.workout.MapCard]).
            MapCardTapOverlay(onClick = onOpenFullScreen)
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
    /** Where on the route the chart's scrubber is pointing, or null when nothing is (#48). */
    scrubbedFix: () -> MapFix? = { null },
) {
    val noHeartRateColor = MaterialTheme.colorScheme.primary
    val markerFill = MaterialTheme.colorScheme.onSurface
    val markerStroke = MaterialTheme.colorScheme.surface
    val scrubDotFill = MaterialTheme.colorScheme.primary

    RouteMapSurface(
        framedFixes = trackMap.framedFixes,
        interactive = interactive,
        modifier = modifier,
        showScaleBar = showScaleBar,
        topInsetPixels = topInsetPixels,
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
        // Last, so it is drawn over the route and over both markers: the runner's finger has to be
        // findable even where it is on top of where they set off from.
        ScrubDot(scrubbedFix = scrubbedFix, fill = scrubDotFill, stroke = markerStroke)
    }
}

/**
 * The dot itself, in a composable of its own so that where the finger is on the chart is read here
 * and nowhere else (#48).
 *
 * Read a level up — inside the map's content — the whole route recomposes on every frame of a drag,
 * because the route's lines and the dot would then share one scope: an hour-long Run rebuilds three
 * thousand map points sixty times a second, and the phone drops to fifteen frames. Measured on a
 * 55-minute Run: 65 ms a frame that way, 20 ms this way, which is the difference between a dot that
 * follows the finger and one that lags behind it.
 */
@Composable
@MapboxMapComposable
private fun ScrubDot(scrubbedFix: () -> MapFix?, fill: Color, stroke: Color) {
    val fix = scrubbedFix() ?: return
    CircleAnnotation(point = fix.asPoint()) {
        circleRadius = ScrubDotRadius
        circleColor = fill
        circleStrokeColor = stroke
        circleStrokeWidth = ScrubDotStrokeWidth
    }
}
