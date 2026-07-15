package com.example.runningapp.ui.workout

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
import androidx.compose.material3.Scaffold
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
 * Full-screen live map (#41): the same camera-follow/amber-trail/day-night [MapSurface] as the
 * in-run [MapCard], with a slim high-contrast stats strip pinned above it. Back is the only
 * tappable control, so sweaty thumbs can't reach pause/stop from here.
 */
@Composable
fun FullScreenMapScreen(
    state: HrState,
    sessionRepository: SessionRepository,
    onBack: () -> Unit
) {
    val uiState = remember(state) { mapWorkoutPlayerUiState(state) }
    val sessionId = state.activeDbSessionId

    Scaffold(
        topBar = { FullScreenMapStatsStrip(uiState = uiState, onBack = onBack) }
    ) { padding ->
        if (sessionId != null) {
            MapSurface(
                sessionId = sessionId,
                sessionRepository = sessionRepository,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }
}

@Composable
private fun FullScreenMapStatsStrip(uiState: WorkoutPlayerUiState, onBack: () -> Unit) {
    val metrics = uiState.secondaryMetrics.toMap()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
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
            MapStat(label = "Pace", value = metrics["Pace"] ?: "--:-- /km", modifier = Modifier.weight(1f))
            MapStat(label = "Distance", value = metrics["Distance"] ?: "0.00 km", modifier = Modifier.weight(1f))
            MapStat(label = "Elapsed", value = metrics["Elapsed"] ?: "00:00", modifier = Modifier.weight(1f))
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
