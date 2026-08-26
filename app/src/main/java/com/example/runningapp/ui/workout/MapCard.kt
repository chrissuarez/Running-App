package com.example.runningapp.ui.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.analysis.MapFix
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.map.SunriseSunsetCalculator
import com.example.runningapp.routes.CourseLine
import com.example.runningapp.routes.RoutePoint
import com.example.runningapp.routes.courseRemainingMeters
import com.mapbox.geojson.Point
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotation
import com.mapbox.maps.extension.compose.style.standard.LightPresetValue
import com.mapbox.maps.extension.compose.style.standard.MapboxStandardStyle
import com.mapbox.maps.extension.compose.style.standard.rememberStandardStyleState
import com.mapbox.maps.plugin.PuckBearing
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import kotlin.math.roundToInt

private val MapCardHeight = 180.dp
private const val TrailLineWidth = 6.0

/**
 * The course line, drawn a shade wider than the trail and underneath it (#56).
 *
 * Wider and under, so where the runner is exactly on the course both lines are visible: an amber
 * trail on a broader blue band reads as "on it", and the blue showing on its own reads as "not yet".
 * Equal widths would have the trail hide the course completely and leave the runner unable to tell
 * the two states apart.
 */
private const val CourseLineWidth = 9.0

/**
 * Live map card for the outdoor in-run screen (#40). Tapping it opens the full-screen map (#41)
 * via [onClick]. Rendering only while visible (screen off, backgrounded, or navigated away) comes
 * for free: [MapboxMap] ties its internal MapView to the host [androidx.lifecycle.LifecycleOwner]
 * and stops rendering on the Activity's onStop (screen off, backgrounded); navigating to another
 * route removes this composable from composition entirely, via Navigation-Compose's NavHost.
 */
@Composable
fun MapCard(
    sessionId: Long,
    sessionRepository: SessionRepository,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Open full-screen map", onClick = onClick)
    ) {
        MapSurface(
            sessionId = sessionId,
            sessionRepository = sessionRepository,
            modifier = Modifier
                .fillMaxWidth()
                .height(MapCardHeight)
        )
    }
}

/**
 * Shared map rendering for the in-run [MapCard] and the [FullScreenMapScreen]: camera follows the
 * device's location puck, the session trail is drawn from accuracy-accepted track points, the course
 * the Run set out to follow is drawn under it in its own colour (#56), how much of that course is
 * left sits in the bottom corner (#57), and the style switches between day/night presets from
 * on-device sunrise/sunset.
 *
 * One composable and not two, which is why the course reaches the full-screen map for nothing: the
 * two views differ in how much of the screen they take and in what is drawn over them, never in what
 * the map itself says.
 */
@Composable
fun MapSurface(sessionId: Long, sessionRepository: SessionRepository, modifier: Modifier = Modifier) {
    val trackPoints by produceState(initialValue = emptyList<TrackPoint>(), sessionId, sessionRepository) {
        sessionRepository.getTrackPointsForMapFlow(sessionId).collect { value = it }
    }
    val trailPoints = remember(trackPoints) {
        trackPoints.map { Point.fromLngLat(it.longitude, it.latitude) }
    }
    // The course this Run set out to follow, in the order the Run is running it — reversed already
    // where the runner said they were setting off the other way round (#56, #57) — or empty for a Run
    // following none. The drawing does not care which order it arrives in: running a course backwards
    // covers the same ground in the same places, so the same line is drawn either way.
    val routePoints by produceState(initialValue = emptyList<RoutePoint>(), sessionId, sessionRepository) {
        sessionRepository.routeLineForRunFlow(sessionId).collect { value = it }
    }
    val coursePoints = remember(routePoints) {
        routePoints.map { Point.fromLngLat(it.longitude, it.latitude) }
    }
    // How far is left of the course, worked out from the same two lists the map is drawn from (#57).
    // Screen-only: nothing here speaks, and nothing here judges whether the runner is still on the
    // course — that is the off-course ticket's, reading the same module.
    val courseLine = remember(routePoints) { CourseLine.of(routePoints) }
    val remainingLabel = remember(courseLine, trackPoints) {
        courseRemainingLabel(
            courseRemainingMeters(courseLine, trackPoints.map { MapFix(it.latitude, it.longitude) })
        )
    }

    val isDaytime = remember(trailPoints) {
        val anchor = trailPoints.lastOrNull()
        anchor == null || SunriseSunsetCalculator.isDaytime(
            latitude = anchor.latitude(),
            longitude = anchor.longitude(),
            epochMillis = System.currentTimeMillis()
        )
    }
    val standardStyleState = rememberStandardStyleState()
    SideEffect {
        standardStyleState.configurationsState.lightPreset =
            if (isDaytime) LightPresetValue.DAY else LightPresetValue.NIGHT
    }

    val mapViewportState = rememberMapViewportState()
    val trailColor = MaterialTheme.colorScheme.primary
    // Blue against the trail's amber: the two lines have to be told apart at a glance, in daylight,
    // at arm's length, by someone running.
    val courseColor = MaterialTheme.colorScheme.secondary

    Box(modifier = modifier) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = mapViewportState,
            style = { MapboxStandardStyle(standardStyleState = standardStyleState) }
        ) {
            MapEffect(Unit) { mapView ->
                mapView.location.updateSettings {
                    locationPuck = createDefault2DPuck(withBearing = true)
                    puckBearingEnabled = true
                    puckBearing = PuckBearing.HEADING
                    enabled = true
                }
                // Follow the runner, and not the course, even on a routed Run (#56). The camera's job
                // during a Run is to show where they are and what is immediately in front of them; a
                // camera pulled back far enough to hold the whole of a ten-kilometre course would show
                // them neither. So the whole course line is drawn, and how much of it is on screen at
                // any moment is the zoom's business — the full-screen map is the way to see the rest.
                mapViewportState.transitionToFollowPuckState()
            }
            // Before the trail, so the runner's own path is drawn on top of the plan rather than under
            // it. Where they are on the course, what they want to see is where they have been.
            if (coursePoints.size >= 2) {
                PolylineAnnotation(points = coursePoints) {
                    lineColor = courseColor
                    lineWidth = CourseLineWidth
                }
            }
            if (trailPoints.size >= 2) {
                PolylineAnnotation(points = trailPoints) {
                    lineColor = trailColor
                    lineWidth = TrailLineWidth
                }
            }
        }
        if (remainingLabel != null) {
            DistanceRemainingBadge(
                label = remainingLabel,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
            )
        }
    }
}

/**
 * How much of the course is left, sat in the bottom-right corner of whichever map is on screen (#57).
 *
 * The bottom of the map is where this has to go: the full-screen map's stats strip owns the top, and
 * the card is small enough that a badge anywhere in the middle of it would sit on the runner's own
 * puck. That leaves two corners, and only one of them is ours. Mapbox draws its logo and the little
 * attribution "i" in the bottom-left of every map it renders, and the terms this app uses its maps
 * under say both of those stay where a person can see them — so a badge there would be covering the
 * one thing that is not allowed to be covered. Bottom-right is empty on both views, and it costs
 * nothing: on the full-screen map the corner is bare, and on the card the only thing behind it is
 * more map.
 *
 * Nothing here is tappable — a sweaty thumb on this map must not be able to do anything but open it
 * or leave it.
 */
@Composable
private fun DistanceRemainingBadge(label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * What is left of the course, in the words the badge shows — or null for a Run following no course,
 * where the badge is not drawn at all (#57).
 *
 * Metres near the end and kilometres before it, because both are what a runner would say out loud:
 * "four hundred metres to go" and "two and a half kilometres to go". The metres are rounded to ten
 * before either is chosen, so the last of the kilometres never reads as "1000 m to go".
 *
 * Ten metres and not one, because a metre is a lie about a number read off GPS — and a figure whose
 * last digit flickers every second is a figure the runner stops reading.
 */
internal fun courseRemainingLabel(remainingMeters: Double?): String? {
    if (remainingMeters == null) return null
    val meters = (remainingMeters.coerceAtLeast(0.0) / 10.0).roundToInt() * 10
    return if (meters >= 1000) "%.2f km to go".format(meters / 1000.0) else "$meters m to go"
}
