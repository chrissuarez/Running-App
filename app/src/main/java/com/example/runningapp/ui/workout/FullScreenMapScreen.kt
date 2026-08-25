package com.example.runningapp.ui.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.HrState
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.ui.theme.RunningUiTokens

/**
 * Full-screen live map (#41): the same camera-follow/amber-trail/course-line/day-night [MapSurface]
 * as the in-run [MapCard], full-bleed, with a slim high-contrast stats strip overlaid on top. Back is
 * the only tappable control, so sweaty thumbs can't reach pause/stop from here. The system
 * back button/gesture is intercepted via [BackHandler] and routed through the same [onBack] as
 * the strip's back arrow — otherwise it would pop this destination off Navigation-Compose's
 * `navigateTo`-cleared back stack and exit the app mid-run instead of returning to coaching.
 */
@Composable
fun FullScreenMapScreen(
    state: HrState,
    sessionRepository: SessionRepository,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val uiState = remember(state) { mapWorkoutPlayerUiState(state) }
    val sessionId = state.activeDbSessionId

    Box(modifier = Modifier.fillMaxSize()) {
        if (sessionId != null) {
            MapSurface(
                sessionId = sessionId,
                sessionRepository = sessionRepository,
                modifier = Modifier.fillMaxSize()
            )
        }
        FullScreenMapStatsStrip(
            state = state,
            uiState = uiState,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
private fun FullScreenMapStatsStrip(
    state: HrState,
    uiState: WorkoutPlayerUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
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
            Spacer(modifier = Modifier.width(4.dp))
            MapStat(
                label = "HR",
                value = uiState.hrText,
                valueColor = zoneBandColor(uiState.zoneBand),
                modifier = Modifier.weight(1f)
            )
            MapStat(
                label = "Pace",
                value = if (state.paceMinPerKm > 0) formatPace(state.paceMinPerKm) else "--:-- /km",
                modifier = Modifier.weight(1f)
            )
            MapStat(label = "Distance", value = formatDistanceKm(state.distanceKm), modifier = Modifier.weight(1f))
            MapStat(label = "Elapsed", value = formatStopwatch(state.secondsRunning), modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MapStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
