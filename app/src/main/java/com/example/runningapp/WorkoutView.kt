package com.example.runningapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.runningapp.data.SessionRepository
import com.example.runningapp.ui.theme.RunningUiTokens
import com.example.runningapp.ui.workout.CUE_REASON_HR_HIGH
import com.example.runningapp.ui.workout.CUE_REASON_SENSOR_LOST
import com.example.runningapp.ui.workout.CueSeverity
import com.example.runningapp.ui.workout.MapCard
import com.example.runningapp.ui.workout.TimelineMarkerType
import com.example.runningapp.ui.workout.TimelineSegmentType
import com.example.runningapp.ui.workout.mapWorkoutPlayerUiState
import com.example.runningapp.ui.workout.zoneBandColor
import kotlinx.coroutines.flow.map

@Composable
fun WorkoutView(state: HrState, sessionRepository: SessionRepository, onOpenFullScreenMap: () -> Unit) {
    val uiState = remember(state) { mapWorkoutPlayerUiState(state) }
    val errorColor = MaterialTheme.colorScheme.error

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunningUiTokens.CardPadding)
        ) {
            Text(uiState.intervalLabel, style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(uiState.phaseLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(uiState.countdownText, style = MaterialTheme.typography.displayLarge)
            LinearProgressIndicator(
                progress = uiState.progressFraction,
                modifier = Modifier.fillMaxWidth(),
                strokeCap = StrokeCap.Round
            )
            Text(uiState.progressLabel, style = MaterialTheme.typography.labelMedium)
            uiState.nextLabel?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(uiState.hrText, style = MaterialTheme.typography.titleLarge)
                    val zoneColor = zoneBandColor(uiState.zoneBand)
                    Text(uiState.zoneStatusText, color = zoneColor, style = MaterialTheme.typography.bodyMedium)
                }
                AssistChip(
                    onClick = { },
                    label = { Text(uiState.sensorFreshnessText) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (uiState.sensorStale) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                uiState.secondaryMetrics.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }

            uiState.coachCue?.let { cue ->
                Spacer(modifier = Modifier.height(12.dp))
                val cueColor = when (cue.severity) {
                    CueSeverity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
                    CueSeverity.WARNING -> MaterialTheme.colorScheme.primaryContainer
                    CueSeverity.INFO -> MaterialTheme.colorScheme.surfaceVariant
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = cueColor),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Coach", style = MaterialTheme.typography.labelMedium)
                        Text(cue.message, style = MaterialTheme.typography.bodyMedium)
                        Text("Reason: ${cue.reasonTag}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            uiState.timeline?.let { timeline ->
                Spacer(modifier = Modifier.height(12.dp))
                Text("Workout Timeline", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    if (timeline.segments.isEmpty()) return@Canvas
                    val segmentWidth = size.width / timeline.segments.size.toFloat()
                    timeline.segments.forEachIndexed { index, segment ->
                        val left = index * segmentWidth
                        val color = when (segment.type) {
                            TimelineSegmentType.RUN -> Color(0xFF78BDF4)
                            TimelineSegmentType.WALK -> Color(0xFFFFC261)
                            TimelineSegmentType.RECOVER -> Color(0xFF98E892)
                            TimelineSegmentType.OTHER -> Color(0xFF697789)
                        }
                        drawRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(left, 8f),
                            size = androidx.compose.ui.geometry.Size(segmentWidth - 2f, 20f)
                        )
                    }
                    val currentLeft = timeline.currentSegmentIndex * segmentWidth
                    val markerX = currentLeft + (segmentWidth * timeline.currentSegmentFraction.coerceIn(0f, 1f))
                    drawLine(
                        color = Color.White,
                        start = androidx.compose.ui.geometry.Offset(markerX, 4f),
                        end = androidx.compose.ui.geometry.Offset(markerX, 36f),
                        strokeWidth = 4f
                    )
                    timeline.markers.forEach { marker ->
                        val markerBase = marker.segmentIndex * segmentWidth
                        val x = markerBase + (segmentWidth * marker.fractionInSegment.coerceIn(0f, 1f))
                        when (marker.type) {
                            TimelineMarkerType.PLANNED_TRANSITION -> {
                                drawLine(
                                    color = Color.White,
                                    start = androidx.compose.ui.geometry.Offset(x, 8f),
                                    end = androidx.compose.ui.geometry.Offset(x, 28f),
                                    strokeWidth = 2f
                                )
                            }
                            TimelineMarkerType.HR_TRIGGER -> {
                                drawCircle(
                                    color = errorColor,
                                    radius = 5f,
                                    center = androidx.compose.ui.geometry.Offset(x, 34f)
                                )
                                drawLine(
                                    color = errorColor,
                                    start = androidx.compose.ui.geometry.Offset(x - 5f, 29f),
                                    end = androidx.compose.ui.geometry.Offset(x + 5f, 39f),
                                    strokeWidth = 2f
                                )
                            }
                            TimelineMarkerType.HR_RECOVERY -> {
                                drawCircle(
                                    color = Color(0xFF9CF7AD),
                                    radius = 4f,
                                    center = androidx.compose.ui.geometry.Offset(x, 34f),
                                    style = Stroke(width = 2f)
                                )
                            }
                        }
                    }
                }
            }

            // The run's pinned mode, not the live setting (see HrState.activeRunMode): an outdoor
            // run started right after the mode toggle must render its map from the first second.
            if ((state.activeRunMode ?: state.userSettings.runMode) == "outdoor") {
                val mapSessionId = state.activeDbSessionId
                if (mapSessionId != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    MapCard(sessionId = mapSessionId, sessionRepository = sessionRepository, onClick = onOpenFullScreenMap)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Connection: ${state.connectionStatus}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Debug state: ${state.lifecycle} • ${state.currentPhase}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (uiState.coachCue?.reasonTag == CUE_REASON_SENSOR_LOST || uiState.coachCue?.reasonTag == CUE_REASON_HR_HIGH) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Safety cue active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
