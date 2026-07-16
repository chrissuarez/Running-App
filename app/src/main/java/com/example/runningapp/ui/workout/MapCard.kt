package com.example.runningapp.ui.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.data.TrackPoint
import com.example.runningapp.map.SunriseSunsetCalculator
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

private val MapCardHeight = 180.dp
private const val TrailLineWidth = 6.0

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
 * device's location puck, the session trail is drawn from accuracy-accepted track points, and the
 * style switches between day/night presets from on-device sunrise/sunset.
 */
@Composable
fun MapSurface(sessionId: Long, sessionRepository: SessionRepository, modifier: Modifier = Modifier) {
    val trackPoints by produceState(initialValue = emptyList<TrackPoint>(), sessionId, sessionRepository) {
        sessionRepository.getTrackPointsForMapFlow(sessionId).collect { value = it }
    }
    val trailPoints = remember(trackPoints) {
        trackPoints.map { Point.fromLngLat(it.longitude, it.latitude) }
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

    MapboxMap(
        modifier = modifier,
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
            mapViewportState.transitionToFollowPuckState()
        }
        if (trailPoints.size >= 2) {
            PolylineAnnotation(points = trailPoints) {
                lineColor = trailColor
                lineWidth = TrailLineWidth
            }
        }
    }
}
