package com.example.runningapp

import com.example.runningapp.run.RunMode

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.example.runningapp.ui.theme.RunningUiTokens

@Composable
fun SettingsSummaryCard(
    settings: UserSettings,
    /** The picked Workout's zone number; null on a skipped or plan-less day. */
    workoutTargetZone: Int? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(RunningUiTokens.CardPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                val run = runTargetZone(workoutTargetZone, settings.targetHrZone)
                val target = run.zone
                Text(
                    "Zone ${target.number} · ${target.zoneName}: ${targetRangeLabel(target, settings.hrProfile)} BPM",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (run.source == TargetZoneSource.WORKOUT) "From today's workout" else "Your open-run zone",
                    style = MaterialTheme.typography.bodySmall
                )
                val modeLabel = if (settings.runMode == RunMode.OUTDOOR) "Outdoor Run" else "Treadmill Run"
                Text("Mode: $modeLabel", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (settings.coachingEnabled) "Coaching ON" else "Coaching OFF",
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.coachingEnabled) Color(0xFF9CF7AD) else MaterialTheme.colorScheme.error
            )
        }
    }
}
